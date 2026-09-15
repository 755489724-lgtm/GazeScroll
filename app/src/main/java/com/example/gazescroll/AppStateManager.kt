package com.example.gazescroll

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tracks which app is currently in the foreground and whether it is one of the
 * user's chosen paging targets.
 *
 * ## Where the foreground package comes from
 *
 * HyperOS is unusually locked down, so three sources are tried in order:
 *
 *  1. **The accessibility window list** — the only one that works here. HyperOS
 *     filters accessibility *events* from third-party packages (the launcher and
 *     systemui come through; Douyin and WeChat never do), so `onAccessibilityEvent`
 *     alone is not enough. Within a window we first try `root.packageName`, then
 *     the window title, which for an application window is the app name (e.g.
 *     "抖音").
 *  2. **`UsageStatsManager.queryEvents`** — works intermittently.
 *  3. **`UsageStatsManager.queryUsageStats`** (most recently used app).
 *
 * The usage-stats routes need `PACKAGE_USAGE_STATS`, which this device's system
 * keeps resetting to `ignore`, so they are strictly a bonus.
 *
 * ## Debouncing
 *
 * During an app switch the active window briefly reports the *outgoing* app, and
 * accessibility events add more noise on top. Every source therefore only
 * *proposes* a package: a proposal resets a [SETTLE_MS] timer, and the package is
 * only committed once it has survived that long without being contradicted. This
 * is what stops the camera pipeline flapping on and off while switching apps.
 *
 * ## Fail-open, twice
 *
 * Before anything has been observed the target counts as active, so restarting
 * the service inside Douyin does not silently disable paging. And if observations
 * stop arriving altogether — some HyperOS windows expose neither a package nor a
 * title — [checkBlind] gives up on saving power and goes back to running. Paging
 * is the product; power saving is the nice-to-have.
 */
object AppStateManager {

    private const val TAG = "AppState"

    /** How often the foreground poll runs. Fast, so waking up feels instant. */
    private const val POLL_INTERVAL_MS = 800L

    /** Look-back for the events-based fallback. */
    private const val POLL_LOOKBACK_MS = 60_000L

    /** Look-back for the usage-stats fallback (must cover the current day bucket). */
    private const val USAGE_STATS_LOOKBACK_MS = 12 * 60 * 60 * 1000L

    /**
     * How long a reported package must survive before it is acted on.
     *
     * Short on purpose: the user asked for effectively instant start/stop. 400 ms
     * is imperceptible next to the app switch itself, while still filtering the
     * sub-200 ms blips HyperOS produces mid-transition.
     */
    /**
     * Settle time when the app coming forward IS a target. Very short, because the
     * user's complaint was that the first seconds inside Douyin did nothing; the
     * only job here is to drop sub-100 ms transition blips.
     */
    private const val SETTLE_TO_ACTIVE_MS = 80L

    /** Settle time when the app coming forward is NOT a target (going to sleep). */
    private const val SETTLE_TO_STANDBY_MS = 400L

    /**
     * If the foreground cannot be observed for this long while asleep, assume the
     * detector is blind rather than that the user really is elsewhere.
     */
    private const val BLIND_TIMEOUT_MS = 8_000L

    /**
     * 「前台其实是目标应用、我们却认为不是」需要持续多久才判定为判据出错（v5.7）。
     *
     * 2 秒足够跨过一整个应用切换的过渡期（[SETTLE_TO_STANDBY_MS] 只有 400ms，
     * 加上窗口读数本身的抖动也就几百毫秒），又远短于用户能察觉到"没反应"的时间。
     * 真正的应用切换不会误命中：那时活动窗口确实不是目标应用。
     */
    private const val CONTRADICTION_GRACE_MS = 2_000L

    /**
     * Packages that report themselves as foreground without being the app the
     * user is actually using. systemui does this for a moment on every launch.
     */
    private val TRANSIENT_PACKAGES = setOf(
        "com.android.systemui",
        "android",
        "com.miui.aod",
    )

    /** Last committed foreground package, or null before any report. */
    @Volatile
    var foregroundPackage: String? = null
        private set

    /** True while a selected target app is in the foreground (or global paging is on). */
    @Volatile
    var targetActive: Boolean = true
        private set

    /**
     * 全局使用翻页（v4.7）。打开后白名单检查整个跳过，桌面 / 任何应用都算「允许翻页」。
     *
     * 从 [AppPrefs] 读入，[refresh] 和每次轮询都会重新同步，所以用户在设置里一开就生效。
     */
    @Volatile
    var globalPaging: Boolean = false
        private set

    /** Diagnostics: true once either usage-stats route returned something. */
    @Volatile
    var usageStatsAvailable: Boolean = false
        private set

    /**
     * Set while the user has the settings screen open, so the live eye / pitch
     * readout has something to show even though this app is not a target app.
     */
    @Volatile
    var forceActive: Boolean = false

    private val listeners = CopyOnWriteArrayList<(Boolean, String) -> Unit>()
    private val handler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var pollRunnable: Runnable? = null

    /** Candidate package awaiting confirmation. */
    private var pendingPackage: String? = null

    /** When the foreground was last successfully observed. */
    private var lastObservationAtMs = 0L

    /** Previous raw window reading, only for the poll diagnostic log. */
    private var lastPolledWindow: String? = null
    private var loggedFirstPoll = false

    /** 连续观察到「前台其实是目标应用、我们却认为不是」的起始时刻（v5.7）。 */
    private var contradictionSinceMs = 0L

    private val settleRunnable = Runnable { commitPending() }

    /**
     * Immediately fires the current value, so a new listener starts in sync.
     *
     * 回调带一个 [reason]：`enter` / `leave` / `refresh`。服务据此决定要不要做一次
     * 「强恢复」——v4.7 修的正是「从桌面切回目标应用却要手动滑一下才生效」，
     * 所以每次**真实进入**都重新武装整条流水线，而不是只依赖一个布尔值的变化。
     */
    fun addListener(listener: (Boolean, String) -> Unit) {
        listeners.add(listener)
        runCatching { listener(targetActive, "initial") }
    }

    fun removeListener(listener: (Boolean, String) -> Unit) {
        listeners.remove(listener)
    }

    // -------------------------------------------------------------- lifecycle --

    fun startPolling(ctx: Context) {
        appContext = ctx.applicationContext
        if (pollRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                runCatching { pollOnce() }
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        pollRunnable = runnable
        handler.postDelayed(runnable, POLL_INTERVAL_MS)
        Log.i(TAG, "foreground tracking started")
    }

    /**
     * Run one foreground check right now instead of waiting for the next tick.
     *
     * Called when the accessibility service connects, so "is a target app already
     * in front?" is answered immediately rather than up to [POLL_INTERVAL_MS]
     * later.
     */
    fun pollNow(ctx: Context) {
        appContext = ctx.applicationContext
        runCatching { pollOnce() }.onFailure { Log.w(TAG, "pollNow failed", it) }
    }

    fun stopPolling() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
        handler.removeCallbacks(settleRunnable)
    }

    // ------------------------------------------------------------------ input --

    /**
     * Report a foreground package. Called by the accessibility service on every
     * window event, and by the poller. Never applied directly — see the class doc.
     */
    fun onForegroundPackage(ctx: Context, packageName: String?) {
        appContext = ctx.applicationContext
        if (packageName != null && packageName in TRANSIENT_PACKAGES) return
        propose(ctx.applicationContext, packageName)
    }

    /** Re-evaluate immediately with the same package, e.g. after editing the list. */
    fun refresh(ctx: Context) {
        appContext = ctx.applicationContext
        handler.removeCallbacks(settleRunnable)
        pendingPackage = foregroundPackage
        syncGlobalPaging(ctx)
        // Force it through even though the package is unchanged: the answer to
        // "is this a target?" is exactly what just changed.
        commitPending(force = true, reason = "refresh")
    }

    /** 把「全局使用翻页」开关从设置里同步进来。 */
    private fun syncGlobalPaging(ctx: Context) {
        globalPaging = AppPrefs.isGlobalPagingEnabled(ctx)
    }

    /** 当前前台应用是否允许翻页（全局模式下一律允许）。 */
    private fun isAllowed(ctx: Context, packageName: String?): Boolean {
        if (globalPaging) return true
        if (packageName == null) return true
        val targets = AppPrefs.targetPackages(ctx)
        // An empty selection means "always on": the on-demand gate is disabled
        // entirely, which is the escape hatch if foreground detection misbehaves.
        return targets.isEmpty() || packageName in targets
    }

    private fun propose(ctx: Context, packageName: String?) {
        appContext = ctx
        lastObservationAtMs = SystemClock.elapsedRealtime()
        pendingPackage = packageName
        syncGlobalPaging(ctx)

        val wouldBeActive = isAllowed(ctx, packageName)

        // Chain launch, with no settling at all: the moment a target app reaches
        // the front, make sure the camera service exists. Building it takes a
        // moment anyway, so starting it now is what makes the first blink land.
        if (wouldBeActive) {
            GazeCameraService.ensureRunning(ctx)
        }

        handler.removeCallbacks(settleRunnable)
        handler.postDelayed(
            settleRunnable,
            if (wouldBeActive) SETTLE_TO_ACTIVE_MS else SETTLE_TO_STANDBY_MS,
        )
    }

    private fun commitPending(force: Boolean = false, reason: String = "change") {
        val ctx = appContext ?: return
        val packageName = pendingPackage
        if (!force && packageName == foregroundPackage) return

        val previousPackage = foregroundPackage
        val previousActive = targetActive
        foregroundPackage = packageName
        val active = isAllowed(ctx, packageName)
        if (previousPackage != packageName) {
            Log.i(TAG, "window changed: $previousPackage -> $packageName (allowed=$active)")
        }
        Log.i(TAG, "foreground=$packageName (target=$active)")

        // 只有「允许翻页」的状态真的变了、或者前台应用换了才通知。
        // 前台应用换了也要通知：服务需要重新武装检测器（v4.7 的恢复修复）。
        val packageChanged = previousPackage != packageName
        if (active == previousActive && !packageChanged && reason != "refresh") return

        targetActive = active
        val effectiveReason = when {
            reason == "refresh" -> "refresh"
            active != previousActive -> if (active) "enter" else "leave"
            packageChanged -> "package-change"
            else -> "change"
        }
        Log.i(TAG, "target state -> $active (reason=$effectiveReason)")
        for (listener in listeners.toList()) {
            runCatching { listener(active, effectiveReason) }
        }
    }

    /**
     * Safety net for HyperOS's blind spots.
     *
     * Some third-party windows expose neither a package nor even a title — Douyin
     * on its splash does exactly this — so no observation arrives at all. Sit in
     * standby and paging would silently stop, which is the one failure a user
     * actually notices. So after [BLIND_TIMEOUT_MS] with no observation, go back
     * to running and say so in the log.
     */
    private fun checkBlind() {
        if (targetActive) return
        val last = lastObservationAtMs
        if (last == 0L) return
        val now = SystemClock.elapsedRealtime()
        if (now - last <= BLIND_TIMEOUT_MS) return

        Log.w(
            TAG,
            "no foreground observation for ${(now - last) / 1000}s — failing open, staying active",
        )
        lastObservationAtMs = now
        pendingPackage = null
        commitPending(force = true, reason = "fail-open")
    }

    /**
     * 「前台其实是目标应用，我们却认为不是」的兜底（v5.7）。
     *
     * ## 为什么需要它
     *
     * 这是「隔一会重开抖音又不触发」里**最难自查**的一种失效：整条恢复链都挂在
     * [targetActive] 上，一旦它错成 false，`shouldAnalyze()` 就恒为 false，
     * 相机、看门狗、liveness 探针**全部被同一个条件挡住** —— 谁也不会去纠正它。
     * 而 [checkBlind] 救不了这个方向：它治的是「前台判不出来」（fail-open），
     * 不是「判错了」（fail-closed 到错误的一边）。
     *
     * 所以这里做一件独立的事：**不信任自己记的前台**，直接向无障碍服务再问一次
     * "现在活动的窗口是谁"。若它明确是目标应用、而我们却认为不是，并且持续了
     * [CONTRADICTION_GRACE_MS]，就强制按"允许翻页"重算一次。
     *
     * 判据要求"明确"：读不到窗口（HyperOS 会隐藏标题）时返回 null，此时**不做任何事**，
     * 保持现有的待机行为，避免把正常的省电待机判成故障。
     *
     * @return true 表示已强制纠正过状态，调用方应跳过本轮的常规 propose 流程
     */
    private fun fixContradiction(ctx: Context, actual: String?): Boolean {
        if (actual == null || actual == ctx.packageName) {
            contradictionSinceMs = 0L
            return false
        }
        // 用 isAllowed 而不是直接比对白名单：它已经处理了「全局使用翻页」和
        // 「白名单为空 = 永远允许」两种情况，语义与 commitPending 完全一致。
        if (!isAllowed(ctx, actual)) {
            contradictionSinceMs = 0L
            return false
        }
        if (targetActive) {
            // 状态是对的（或者已经有人纠正过），没什么可做的。
            contradictionSinceMs = 0L
            return false
        }

        val now = SystemClock.elapsedRealtime()
        if (contradictionSinceMs == 0L) {
            contradictionSinceMs = now
            return false
        }
        if (now - contradictionSinceMs < CONTRADICTION_GRACE_MS) return false

        Log.w(
            TAG,
            "contradiction: active window is $actual (allowed) but targetActive=false " +
                "for ${now - contradictionSinceMs}ms — forcing an immediate re-evaluation " +
                "(this is the 'reopen Douyin, nothing works' state)",
        )
        contradictionSinceMs = 0L
        lastObservationAtMs = now
        pendingPackage = actual
        syncGlobalPaging(ctx)
        commitPending(force = true, reason = "contradiction-fail-open")
        return true
    }

    // ---------------------------------------------------------------- polling --

    private fun pollOnce() {
        val ctx = appContext ?: return
        checkBlind()

        // 设置页可能刚改了「全局使用翻页」，每次轮询都重新同步一次，
        // 保证用户一打开开关就立刻生效，而不用等下一次窗口事件。
        syncGlobalPaging(ctx)

        // Chain-launch watchdog: a target app is in front but the camera service
        // is gone (killed by the system, or never started) — bring it straight back.
        if (targetActive) GazeCameraService.ensureRunning(ctx)

        // v5.3：**主动存活检查**，这条是「重开抖音必须下拉状态栏才生效」的根因修复。
        //
        // 以前整条恢复链只在「前台包名发生变化」时才会跑（见 commitPending 里的
        // `packageName == foregroundPackage` 早退）。于是出现过这样的死角：
        // 状态里记的前台已经是抖音（或这次切换根本没被观察到），包名没变 → 什么都不通知
        // → onTargetEntered() 不执行 → 相机不重绑 → 功能静默失效。
        //
        // 下拉状态栏之所以"有效"，正是因为它**人为制造了一次包名变化**：
        // 先变成 com.android.systemui，收起时再变回抖音，等于替我们触发了两次通知。
        //
        // 现在改成不依赖包名变化：只要「当前允许翻页」而流水线实际是死的，就强制重新武装。
        if (targetActive) GazeCameraService.ensurePipelineAlive(ctx)

        // 1. The source that actually works here.
        val service = GazeAccessibilityService.instance
        if (service != null) {
            val activeWindowPackage = activeWindowPackage(service)
            if (activeWindowPackage != lastPolledWindow) {
                lastPolledWindow = activeWindowPackage
                Log.i(TAG, "poll: activeWindow=$activeWindowPackage")
            }

            // v5.7：先做一次"前台其实是目标应用、我们却认为不是"的独立核对。
            // 命中并纠正后本轮不再 propose，避免用同一份读数把它又改回待机。
            // 放在 `== ctx.packageName` 的早退**之前**：无论读数是否恰好是我们自己，
            // 这个核对都该跑，它自己会处理"读不到 / 是自己"的情况。
            if (fixContradiction(ctx, activeWindowPackage)) return

            // An opaque top window has to count as UNKNOWN, not as "still the last
            // app". HyperOS hides Douyin's splash completely — no root, not even a
            // title — and reporting nothing used to leave us in standby on a stale
            // "launcher" reading, which is exactly why the first seconds inside
            // Douyin did nothing until the user swiped and made the window
            // readable. Unknown maps to active, so paging works immediately.
            if (activeWindowPackage == ctx.packageName) return
            propose(ctx, activeWindowPackage)
            return
        }

        // 2/3. Usage stats, when the platform lets us read them.
        if (!loggedFirstPoll) {
            loggedFirstPoll = true
            Log.w(TAG, "poll: accessibility service not connected; falling back")
        }
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val now = System.currentTimeMillis()
        val latest = latestResumedPackage(ctx, usm, now)
            ?: latestUsedPackage(ctx, usm, now)

        if (latest == null) {
            if (!usageStatsAvailable) {
                Log.w(TAG, "usage stats empty so far — is GET_USAGE_STATS granted?")
            }
            return
        }
        usageStatsAvailable = true
        propose(ctx, latest)
    }

    /**
     * Package of the currently focused window, via the accessibility service.
     *
     * Tiers, because HyperOS degrades each one:
     *  1. the active window's `root.packageName` — the clean answer;
     *  2. the active window's title, matched against the target apps' labels.
     *     HyperOS hides third-party window content (`hasChildren=false`, so `root`
     *     is null) and sometimes the title too, but when the title is there it is
     *     the app name — e.g. "抖音";
     *  3. `getRootInActiveWindow()`, which is null whenever no window holds
     *     accessibility focus.
     */
    private fun activeWindowPackage(service: GazeAccessibilityService): String? {
        val fromWindows = runCatching {
            val windows = service.windows ?: return@runCatching null
            val target = windows.firstOrNull { it.isActive }
                ?: windows.firstOrNull { it.isFocused }
                ?: return@runCatching null

            target.root?.packageName?.toString()?.let { return@runCatching it }

            // Content is hidden; fall back to the window title, but only accept it
            // when it names one of the target apps. An arbitrary title is not a
            // package name and must not be proposed as one.
            val title = target.title?.toString()?.trim()
            if (title.isNullOrEmpty()) return@runCatching null
            TargetApps.ALL.firstOrNull { it.label == title }?.packageName
        }.getOrNull()
        if (fromWindows != null) return fromWindows

        return runCatching {
            service.rootInActiveWindow?.packageName?.toString()
        }.getOrNull()
    }

    /** Primary usage-stats route: the most recent ACTIVITY_RESUMED. */
    private fun latestResumedPackage(ctx: Context, usm: UsageStatsManager, now: Long): String? {
        val events = runCatching {
            usm.queryEvents(now - POLL_LOOKBACK_MS, now)
        }.getOrNull() ?: return null

        val resumedType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_FOREGROUND
        }

        var latest: String? = null
        var latestAt = 0L
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != resumedType) continue
            val name = event.packageName ?: continue
            if (!isRelevant(ctx, name)) continue
            if (event.timeStamp >= latestAt) {
                latestAt = event.timeStamp
                latest = name
            }
        }
        return latest
    }

    /** Fallback route: whichever app the user touched most recently. */
    private fun latestUsedPackage(ctx: Context, usm: UsageStatsManager, now: Long): String? {
        val stats = runCatching {
            usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - USAGE_STATS_LOOKBACK_MS,
                now,
            )
        }.getOrNull() ?: return null

        var best: String? = null
        var bestAt = 0L
        for (entry in stats) {
            val name = entry.packageName ?: continue
            if (!isRelevant(ctx, name)) continue
            if (entry.lastTimeUsed > bestAt) {
                bestAt = entry.lastTimeUsed
                best = name
            }
        }
        return best
    }

    private fun isRelevant(ctx: Context, name: String): Boolean =
        name != ctx.packageName && name !in TRANSIENT_PACKAGES
}

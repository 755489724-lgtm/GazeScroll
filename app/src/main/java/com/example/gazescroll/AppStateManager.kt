package com.example.gazescroll

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

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
     * v5.67：诊断心跳间隔（见 `pollOnce`）。
     *
     * 10 秒一行、每行约 90 字节 → 一天不到 1 MiB，相对功耗可忽略；
     * 而它换来的是「轮询还活着吗」这个第一分叉的确定答案。
     */
    private const val HEARTBEAT_INTERVAL_MS = 10_000L

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

    /** v5.67：上一次写诊断心跳的时间。见 `pollOnce`。 */
    private var lastHeartbeatAtMs = 0L

    /** v5.68：上一次记录「无障碍实例为空导致轮询放弃」的时间（10 秒节流）。 */
    private var lastNoA11yAtMs = 0L

    /** v5.69：上一次 pollOnce 进入 / 退出的时刻，用来量"消息队列被饿死"的空档。 */
    private var lastPollEnterMs = 0L
    private var lastPollExitMs = 0L

    /**
     * v5.70：`readWindow` 这一段历史上出现过的最长耗时（毫秒）。
     *
     * 故意**不用 @Volatile**：它只在主线程上读写（读窗口本来就在主循环里），
     * 加了反而误导后来人以为有跨线程访问。
     */
    private var longestWindowReadMs = 0L

    /**
     * v5.69：空档超过这个值就记一条 `poll-gap`。
     *
     * 轮询间隔是 [POLL_INTERVAL_MS]（800ms），加上一轮的工作量，正常空档不到 1 秒。
     * 3 秒留了足够余量：只有真的被"饿"了才会记，不会误报。
     */
    private const val SLOW_GAP_MS = 3_000L

    /**
     * v5.69：单段耗时超过这个值就记一条 `slow-phase`（点名是哪一段慢）。
     *
     * 500ms 远大于正常水平（各段都在毫秒级），所以正常运行时一条都不会写。
     */
    private const val SLOW_PHASE_MS = 500L

    /**
     * v5.70：读一次活动窗口最多等多久（毫秒）。
     *
     * 实测正常是**毫秒级**，卡住时是 8 秒起（另一次 78 秒）。500ms 留了足够余量：
     * 正常读不会被误判超时，而一旦它开始卡，主循环最多被拖 500ms × 2 段
     * （windows + rootInActiveWindow），仍然远小于 800ms 的轮询间隔，
     * 循环不会失速。超时的代价只是这一轮"读不到窗口"，而读不到按 fail-open 处理。
     */
    private const val WINDOW_READ_TIMEOUT_MS = 500L
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
                // v5.68：原来的 `runCatching { pollOnce() }` **不留任何痕迹** ——
                // 一旦 pollOnce 每轮都抛异常，心跳与"轮询停了"在诊断文件里长得一模一样，
                // 实测排查时正是被这一点卡住（2026-09-20 那次复现）。
                // 异常现在在 pollOnce() 里显式落盘成 poll-error，并能区分
                // "卡在轮询内部"与"消息队列根本没轮到轮询"（见 poll-gap）。
                pollOnce()
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        pollRunnable = runnable
        handler.postDelayed(runnable, POLL_INTERVAL_MS)
        Log.i(TAG, "foreground tracking started")
        DiagLog.append(appContext, "poll-start", "foreground tracking started")
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
        // v5.67：这是「老毛病」最重要的一行 —— targetActive 是整条恢复链的总闸，
        // 它错成 false 时相机/看门狗/liveness 探针会被同一个条件全部挡住。
        // logcat 里这一行活不过 2 秒（见 DiagLog 的说明），必须落盘。
        DiagLog.append(
            appContext,
            "target",
            "active=$active reason=$effectiveReason " +
                "pkg=$packageName prev=$previousPackage prevActive=$previousActive",
        )
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
        DiagLog.append(
            appContext,
            "blind",
            "no observation for ${(now - last) / 1000}s -> fail-open, commit pending=$pendingPackage",
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
        DiagLog.append(
            appContext,
            "contradiction",
            "window=$actual is allowed but targetActive=false for ${now - contradictionSinceMs}ms " +
                "-> force fail-open",
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
        // v5.69：enter / exit 一对标记。
        //
        // 为什么需要它们（2026-09-20 实测现场）：主线程整整 78 秒没有处理任何消息
        // （`beat`/`selfcheck`/`diag` 全停，进程活着、CPU 为 0、非 frozen），
        // 外力（下拉状态栏 / kill -3）一戳就恢复 —— 说明是**消息队列被长时间饿死**。
        // 但"饿死"有两种，修法完全不同：
        //   ① 卡在 pollOnce **内部**（某个阻塞调用）→ exit 与下一次 enter 之间有空档，
        //      而且 `slow-phase` 会点名是哪一段慢；
        //   ② 卡在**轮询之外**（消息队列根本没轮到它跑）→ 上一次 exit 之后长时间没有新 enter。
        // 所以 enter 与 exit 都必须记，缺一不可。
        val enterMs = SystemClock.elapsedRealtime()
        val gap = if (lastPollExitMs == 0L) 0L else enterMs - lastPollExitMs
        lastPollEnterMs = enterMs
        if (gap >= SLOW_GAP_MS) {
            DiagLog.append(
                appContext,
                "poll-gap",
                "gap=${gap}ms between exit and enter — the loop did NOT run; " +
                    "starvation is outside pollOnce() (message queue), not inside it",
            )
        }
        runCatching { pollOnceInner() }
            .onFailure {
                DiagLog.append(appContext, "poll-error", "${it.javaClass.name}: ${it.message}")
                Log.w(TAG, "pollOnceInner failed", it)
            }
        lastPollExitMs = SystemClock.elapsedRealtime()
    }

    private fun pollOnceInner() {
        // v5.68：心跳必须放在**最开头**，在任何早退之前。
        //
        // 原来它放在无障碍检查之后，于是三种情况在日志里分不开：
        //   ① 轮询彻底没跑；② 跑了但 `service == null` 早退；③ pollOnce 每轮抛异常。
        // 2026-09-20 那次复现就卡在这个盲点上（日志停在某一秒，之后一行都没有）。
        // 放到最前面之后，只要还有心跳就说明轮询活着，问题就缩小到早退/异常；
        // 心跳彻底断掉才是调度侧的问题。
        val beatNow = SystemClock.elapsedRealtime()
        if (beatNow - lastHeartbeatAtMs >= HEARTBEAT_INTERVAL_MS) {
            lastHeartbeatAtMs = beatNow
            DiagLog.append(
                appContext,
                "beat",
                "targetActive=$targetActive fg=$foregroundPackage lastWindow=${lastPolledWindow ?: "null"}",
            )
        }

        val ctx = appContext ?: return
        val t0 = SystemClock.elapsedRealtime()
        checkBlind()
        val tCheckBlind = SystemClock.elapsedRealtime()

        // 设置页可能刚改了「全局使用翻页」，每次轮询都重新同步一次，
        // 保证用户一打开开关就立刻生效，而不用等下一次窗口事件。
        syncGlobalPaging(ctx)
        val tSync = SystemClock.elapsedRealtime()

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
        val tAlive = SystemClock.elapsedRealtime()

        // 1. The source that actually works here.
        val service = GazeAccessibilityService.instance
        if (service != null) {
            val activeWindowPackage = activeWindowPackage(service)
            val tWindow = SystemClock.elapsedRealtime()
            // 分段耗时：只有真的慢了才记，正常一轮什么都不写。
            val slowest = maxOf(
                tCheckBlind - t0,
                tSync - tCheckBlind,
                tAlive - tSync,
                tWindow - tAlive,
            )
            if (slowest >= SLOW_PHASE_MS || tWindow - t0 >= SLOW_PHASE_MS) {
                DiagLog.append(
                    appContext,
                    "slow-phase",
                    "total=${tWindow - t0}ms " +
                        "checkBlind=${tCheckBlind - t0}ms " +
                        "syncGlobal=${tSync - tCheckBlind}ms " +
                        "ensureAlive=${tAlive - tSync}ms " +
                        "readWindow=${tWindow - tAlive}ms " +
                        "maxReadWindowEver=${longestWindowReadMs}ms " +
                        "=> activeWindow=$activeWindowPackage",
                )
            }
            if (activeWindowPackage != lastPolledWindow) {
                lastPolledWindow = activeWindowPackage
                Log.i(TAG, "poll: activeWindow=$activeWindowPackage")
                // v5.67：窗口读数**变了**才落盘。这是判定「老毛病」根因的关键证据 ——
                // 抖音在前台时这个读数是 null（HyperOS 隐藏了它的窗口包名和标题），
                // 而 null 会走 propose(null) → isAllowed(null)=true → 判成"允许翻页"。
                // 到底有没有走到那一步，看这一行就知道。
                DiagLog.append(
                    appContext,
                    "window",
                    "activeWindow=$activeWindowPackage targetActive=$targetActive " +
                        "pending=$pendingPackage fg=$foregroundPackage",
                )
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
        // v5.68：这个早退以前是**静默**的（只在整个进程里打过一行 logcat），
        // 而它是「待机醒不过来」的头号嫌疑：拿不到无障碍实例 → 每轮都在这里返回
        // → 前台永远判不出来 → targetActive 永远错。每次早退都落盘，
        // 但按 10 秒节流，免得一行/帧地把文件刷爆。
        if (beatNow - lastNoA11yAtMs >= HEARTBEAT_INTERVAL_MS) {
            lastNoA11yAtMs = beatNow
            DiagLog.append(appContext, "no-a11y", "accessibility instance is null — poll gives up")
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
        val readStartMs = SystemClock.elapsedRealtime()        // v5.70：**有界等待**。
        //
        // ## 为什么必须加（2026-09-20 实测，真机复现两次）
        //
        // `service.windows` / `rootInActiveWindow` 都是**跨进程同步调用** ——
        // 要问 system_server 的无障碍管理器。原来这里是裸调，没有任何超时保护，
        // 于是在「亮屏 → 解锁 → 点开抖音」这一段（system_server 正忙）实测卡住：
        //
        //     slow-phase total=8043ms checkBlind=0ms syncGlobal=0ms
        //                ensureAlive=0ms readWindow=8043ms
        //
        // **8 秒全部耗在这一行上**，主线程整块停住：日志全停、心跳不跳、
        // 抖音认不出来 —— 这就是用户报了很久的「息屏后再开抖音没效果」。
        // 另一次实测卡了 78 秒，然后外力（下拉状态栏 / kill -3 触发的一次
        // 消息处理）一戳就恢复，也印证了是"被阻塞"而不是"判定错"。
        //
        // 加了有界等待之后，读不出来最多等 [WINDOW_READ_TIMEOUT_MS]，
        // 循环照常跑：心跳不断、诊断不断、下一轮还能再试。
        // 读不到时返回 null == "未知"，而 [isAllowed] 把"未知"当成允许翻页
        // （fail-open），所以这个方向是安全的 —— 宁可多开一会儿相机，
        // 也不能让整条链路被一个慢调用拖死。
        //
        // ## 为什么不会堆积后台任务
        //
        // 超时后被放弃的那次调用还在后台线程上跑完（跨进程调用无法取消），
        // 但**它不持有锁、也不再有人等它**，结束后自然被回收；下一次读是
        // 一个全新的任务，不会越积越多。
        val windows = runBounded<List<AccessibilityWindowInfo>?>(
            WINDOW_READ_TIMEOUT_MS,
        ) { service.windows?.toList() }
        val readWindowMs = SystemClock.elapsedRealtime() - readStartMs
        if (readWindowMs > longestWindowReadMs) longestWindowReadMs = readWindowMs

        val fromWindows = runCatching {
            if (windows == null) return@runCatching null
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

        // 这一路同样是有界等待：它也是跨进程调用，同样会卡。
        return runBounded<String?>(WINDOW_READ_TIMEOUT_MS) {
            service.rootInActiveWindow?.packageName?.toString()
        }
    }

    /**
     * 在后台线程上执行 [block]，最多等 [timeoutMs]；超时返回 null（**不抛异常**）。
     *
     * 见 [activeWindowPackage] 里 v5.70 的说明：这是给"不能被拖住的主循环"用的，
     * 专门包住那些**无法取消、但可以被放弃等待**的跨进程调用。
     */
    private fun <T> runBounded(timeoutMs: Long, block: () -> T): T? {
        val executor = boundedExecutor
        if (executor == null || executor.isShutdown) return null
        return runCatching {
            val task = FutureTask(block)
            executor.execute(task)
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    /**
     * 给 [runBounded] 用的单线程池：**daemon 线程**，进程结束就消失，不阻止退出。
     *
     * 单线程是刻意的：读窗口本身很快，只有一个线程意味着即使连续的调用都卡住，
     * 也只会有一个"被放弃的任务"在跑，不会并发堆积。
     */
    private val boundedExecutor: ExecutorService? by lazy {
        runCatching {
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "gaze-window-read").apply { isDaemon = true }
            }
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

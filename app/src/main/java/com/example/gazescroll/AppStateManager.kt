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

    /** True while a selected target app is in the foreground. */
    @Volatile
    var targetActive: Boolean = true
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

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
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

    private val settleRunnable = Runnable { commitPending() }

    /** Immediately fires the current value, so a new listener starts in sync. */
    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
        runCatching { listener(targetActive) }
    }

    fun removeListener(listener: (Boolean) -> Unit) {
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
        // Force it through even though the package is unchanged: the answer to
        // "is this a target?" is exactly what just changed.
        commitPending(force = true)
    }

    private fun propose(ctx: Context, packageName: String?) {
        appContext = ctx
        lastObservationAtMs = SystemClock.elapsedRealtime()
        pendingPackage = packageName

        val targets = AppPrefs.targetPackages(ctx)
        val wouldBeActive = packageName == null || targets.isEmpty() ||
            packageName in targets

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

    private fun commitPending(force: Boolean = false) {
        val ctx = appContext ?: return
        val packageName = pendingPackage
        if (!force && packageName == foregroundPackage) return

        foregroundPackage = packageName
        val targets = AppPrefs.targetPackages(ctx)
        // An empty selection means "always on": the on-demand gate is disabled
        // entirely, which is the escape hatch if foreground detection ever
        // misbehaves on a given device.
        val active = packageName == null || targets.isEmpty() || packageName in targets
        Log.i(TAG, "foreground=$packageName (target=$active)")
        if (active == targetActive) return

        targetActive = active
        Log.i(TAG, "target state -> $active")
        for (listener in listeners.toList()) {
            runCatching { listener(active) }
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
        commitPending(force = true)
    }

    // ---------------------------------------------------------------- polling --

    private fun pollOnce() {
        val ctx = appContext ?: return
        checkBlind()

        // Chain-launch watchdog: a target app is in front but the camera service
        // is gone (killed by the system, or never started) — bring it straight back.
        if (targetActive) GazeCameraService.ensureRunning(ctx)

        // 1. The source that actually works here.
        val service = GazeAccessibilityService.instance
        if (service != null) {
            val activeWindowPackage = activeWindowPackage(service)
            if (activeWindowPackage != lastPolledWindow) {
                lastPolledWindow = activeWindowPackage
                Log.i(TAG, "poll: activeWindow=$activeWindowPackage")
            }

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

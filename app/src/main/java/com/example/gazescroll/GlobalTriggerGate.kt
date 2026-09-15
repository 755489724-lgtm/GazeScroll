package com.example.gazescroll

/**
 * 全局触发冷却闸门（防误触）。
 *
 * ## 为什么需要它
 *
 * 摄像头是逐帧（约 15 fps）分析的，而**点头和眨眼共用同一张脸**。一次真实的
 * 头部动作往往会在连续 3~5 帧里都满足触发条件；更糟的是，点头时眼睛会被额头
 * 或眼镜框遮住，眨眼检测器会把这同一次动作也判成一次眨眼。
 *
 * 结果就是：点一次头 → 点头检测器和眨眼检测器各响几次 → 上滑、下滑交替出现
 * → 页面疯狂乱滚。
 *
 * 因此冷却不能只放在单个检测器内部（那样两边各自计时，谁也管不住谁），而要
 * 放在**所有触发路径汇合的唯一出口**——也就是注入手势之前的那一步。本类就是
 * 那个出口上的闸门：不管这次触发是眨眼、点头、仰头还是调试用的视线模式，
 * 只要闸门一开始冷却，后续信号**全部忽略**。
 *
 * ## 语义
 *
 * ```
 * 触发 → 闸门立刻进入冷却 → 冷却期内所有信号被吞掉 → 冷却结束 → 才允许下一次触发
 * ```
 *
 * [allow] 是"检查并立即开始冷却"的原子操作：一旦返回非 0，冷却就已经起跳了。
 * 因此同一帧里第二个检测器再来问，必然会被拒掉——不会出现上下滑同时发出。
 *
 * ## 线程
 *
 * 所有触发都在 ML Kit 的同一回调线程（analyzer 单线程 executor）上产生，
 * 这里仍然加锁，以免将来新增后台触发源时出现竞态。
 */
class GlobalTriggerGate {

    /** 冷却开关；由配置每帧同步过来。 */
    @Volatile
    var enabled: Boolean = false

    /** 冷却时长（毫秒）；由配置每帧同步过来。 */
    @Volatile
    var cooldownMs: Long = DEFAULT_COOLDOWN_MS

    /** 距上次闸门放行的毫秒时间戳（调用方传 `SystemClock.elapsedRealtime()`）。 */
    private var lastTriggerAtMs = 0L

    /** 当前这一轮冷却的结束时刻；0 表示不在冷却中。 */
    private var cooldownUntilMs = 0L

    /**
     * 询问闸门是否放行。
     *
     * @return `true` 表示允许本次触发，并且冷却**已经**开始计时；
     *         `false` 表示正在冷却，调用方必须直接放弃这次触发。
     */
    @Synchronized
    fun allow(nowMs: Long): Boolean {
        if (!enabled) {
            // 关掉冷却 = 回到旧行为，同时清掉残留状态，免得重新打开时被历史时间戳挡住。
            cooldownUntilMs = 0L
            lastTriggerAtMs = nowMs
            return true
        }
        if (nowMs < cooldownUntilMs) return false
        lastTriggerAtMs = nowMs
        cooldownUntilMs = nowMs + cooldownMs
        return true
    }

    /** 冷却剩余毫秒数；0 表示现在可以触发。仅用于界面显示，不产生副作用。 */
    @Synchronized
    fun remainingMs(nowMs: Long): Long =
        if (!enabled) 0L else (cooldownUntilMs - nowMs).coerceAtLeast(0L)

    /**
     * 每帧把最新的开关 / 时长同步进来。
     *
     * - 关掉开关：立刻解除冷却。
     * - 冷却期内改时长：按新时长重新计算结束时刻（而不是等旧时长走完，
     *   否则用户在设置里把 5 秒调回 1.5 秒后还要再等 5 秒才能生效）。
     */
    @Synchronized
    fun syncConfig(enabled: Boolean, cooldownMs: Long, nowMs: Long) {
        val wasEnabled = this.enabled
        this.enabled = enabled
        this.cooldownMs = cooldownMs
        if (!enabled) {
            cooldownUntilMs = 0L
            return
        }
        if (!wasEnabled) {
            // 刚打开开关：从"现在"开始算，不要沿用打开前的时间戳。
            lastTriggerAtMs = nowMs
            cooldownUntilMs = 0L
            return
        }
        if (cooldownUntilMs != 0L && lastTriggerAtMs != 0L) {
            cooldownUntilMs = lastTriggerAtMs + cooldownMs
        }
    }

    /** 重建流水线 / 进入目标应用时清空冷却，避免刚切回来还是"冷却中"。 */
    @Synchronized
    fun reset() {
        cooldownUntilMs = 0L
        lastTriggerAtMs = 0L
    }

    companion object {
        /** 默认冷却时长：1.5 秒（与需求一致）。 */
        const val DEFAULT_COOLDOWN_MS = 1500L
    }
}

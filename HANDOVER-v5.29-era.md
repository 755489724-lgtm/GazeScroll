# GazeScroll / 万能翻页 —— 交接说明（给下一个会话）

> 生成时间：2026-09-16 21:10 左右，23:20 更新。当前版本 **v5.29**（versionCode 79）已装到手机。
> 这份文档的目的：让下一个会话在**不读历史对话**的情况下接手全部工作。
> 用法：整份复制给下一个会话即可。

---

## ⚠️ 0. 最重要：这个仓库现在有**并行会话**

2026-09-16 23:00 之后，另一个会话在**同一个仓库**里连续提交了 v5.30 ~ v5.37
（`single-eye wink → 音量`、`head-tilt(roll) 音量`、`tilt baseline 冻结`、
`still-head onset gate`、`global 2s action gap`、`更灵敏的默认值` 等）。
`git log` 现在是：

```
d7e4cc8 v5.37: keep tilt baseline across resets, re-anchor after 2.5s, still-head onset gate, void latch
fee253a v5.35: replace wink volume control with head-tilt (roll) volume control
b542970 v5.30: single-eye wink (>=1s hold) -> volume up/down, with per-eye reverse switches
6ee13a5 v5.29: kill two 'no movement but paged' false triggers (blink channel + eye-dip evidence)   ← 本文档描述的最后一次「翻页」改动
```

**操作前先做两件事**：
1. `cd 'D:\ruanjian\deepseek harness\GazeScroll'; git log --oneline -5; git status --short`
   —— 如果 `git status` 里有**不是你改的**文件，说明对面会话正在编辑，**不要** `git checkout` / `git add -A`，
   更不要用整文件覆盖式写入（曾差点把对方的 `TiltDetector.kt` 大改冲掉）。
2. 版本号从 `app/build.gradle.kts` 的当前值继续往上加（**不要**用 5.30~5.37 这些已占用的号）。
   我这边起草过一版 "v5.30 travel-gate"，**已作废并回退**，补丁留在
   `D:\ruanjian\deepseek harness\v530-travel-gate.patch`（需要时 `git apply`，
   内容见本文 §9 第一条）。

另外：手机上现在装的是 **v5.29**（我 21:07 装的），但对面会话可能已经装过它自己的构建 ——
接手时先 `adb shell dumpsys package com.example.gazescroll | Select-String versionName` 确认。

---

## 0. 一句话背景

用户用小米 13 的前置摄像头做**免手翻页**（刷抖音 `com.ss.android.ugc.aweme`）：
眨眼 3 次 = 下一个视频（上滑）、**快速**点头 = 上一个（下滑）、**快速**仰头 = 下一个（上滑）、
左右扭头 = 左右滑、张嘴 = 点击。
代码是纯本地 Android Kotlin 工程，**不联网、不推 GitHub**，所有改动都在本机完成。

---

## 1. 硬约束（用户明确要求，务必遵守）

1. **不联网、不推 GitHub**：全程本地开发、本地 git 提交。
2. **不要要求用户提供日志**。自己用 adb 抓（无线调试，见 §3）。用户明确说过这点。
3. **一步一小改**：一个版本只改一件事（最多一组相关的事），改完实测、留证据。
4. **改前备份、保留回退点**（见 §7）。
5. **版本号递增**，并同步 `README.md` / `CHANGELOG.md`（用户会检查）。
6. **不要动**（除非用户点名要动）：
   - 30cm 静止硬锁定（`HARD_LOCK_NEAR_RATIO=0.55` / `HARD_LOCK_STILL_MS=1200`）
   - 方向仲裁（v5.8 那套）与**远距离**的全部行为
   - 阈值表本身（点头 2.5° / 仰头 3.8° / 扭头 20°）
   - 眨眼档位（设备上是 **3** 次）、用户的设置项
7. 用户会主动说「**其余的不要动**」= 只改他点名的那一处，其余行为一个字节都别变。
8. 用户的验收方式：自己拿手机实测 → 口头反馈哪一项好用/不好用 → 我读日志核对。
   所以**每次交付都要在日志里留下可核对的新字段**（这是既有的工作方式，很有效）。

---

## 2. 关键路径与环境

| 用途 | 路径 |
| --- | --- |
| 工程源码 | `D:\ruanjian\deepseek harness\GazeScroll` |
| APK 产物 | `D:\ruanjian\deepseek harness\apk\gazescroll-<ver>-debug.apk` |
| 实测日志 | `D:\ruanjian\deepseek harness\apk\v5xx-verify.log` |
| 备份/回退点 | `D:\ruanjian\deepseek harness\backup\` |
| adb | `D:\ruanjian\deepseek harness\.android-build\android-sdk\platform-tools\adb.exe` |
| JDK | `D:\ruanjian\deepseek harness\.android-build\jdk\jdk-17.0.20.1+1` |
| Android SDK | `D:\ruanjian\deepseek harness\.android-build\android-sdk` |

**构建**（PowerShell）：
```powershell
$env:JAVA_HOME='D:\ruanjian\deepseek harness\.android-build\jdk\jdk-17.0.20.1+1'
$env:ANDROID_HOME='D:\ruanjian\deepseek harness\.android-build\android-sdk'
cd 'D:\ruanjian\deepseek harness'
.\GazeScroll\gradlew.bat -p GazeScroll --console=plain --offline assembleDebug
```
（AGP 8.6.1 已缓存在 `C:\Users\WIT_User\.gradle`，必须加 `--offline`。）

**发布 + 安装 + 启动**：
```powershell
$adb='D:\ruanjian\deepseek harness\.android-build\android-sdk\platform-tools\adb.exe'
Copy-Item 'D:\ruanjian\deepseek harness\GazeScroll\app\build\outputs\apk\debug\app-debug.apk' 'D:\ruanjian\deepseek harness\apk\gazescroll-5.29-debug.apk' -Force
& $adb install -r -d 'D:\ruanjian\deepseek harness\apk\gazescroll-5.29-debug.apk'
& $adb shell am start -n com.example.gazescroll/.MainActivity
```
安装后用 `adb shell dumpsys package com.example.gazescroll | Select-String versionName` 核对版本。
建议再用 aapt2 + dex 字符串确认新逻辑真的进了包（附录 A 有现成命令）。

**抓日志**（长跑，带 mDNS 重连与追加写）：
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "D:\ruanjian\deepseek harness\GazeScroll\tools\capture-loop.ps1" -Out "D:\ruanjian\deepseek harness\apk\v5xx-verify.log" -Minutes 120
```
⚠️ 必须用 `powershell` 启动，**不是 `pwsh`**（这台机器上 PATH 里没有 `pwsh`，会报
`The term 'pwsh' is not recognized`）。后台跑用 run_in_background。

**设备**：小米 13（`fuxi`/2211133C，HyperOS 3.0.308.0，Android 14 / SDK 34），
无线调试序列号形如 `192.168.3.32:<port>`，**端口每次息屏后会变**，用
`adb mdns services` 找到 `_adb-tls-connect` 的地址重新 `adb connect`（capture-loop.ps1 已内置）。

**日志标签**：`GazeDiag HeadPose Blink PhoneMotion RefPoint GazeA11y GazeCameraService GazeSelfCheck AppState A11yBootstrap`。
注意 Android 会给短标签补空格，正则要允许**零个或多个空格**：
`I/Blink\s*\(\s*\d+\):`，`I/RefPoint\(\s*\d+\):`（有的没有补空格）。

---

## 3. 测量到的设备事实（不要凭猜，这些是实测值）

| 量 | 实测 |
| --- | --- |
| 帧间隔 | **63~116 ms**（约 11 fps），分析器会丢帧 |
| `faceRatio` | 30cm ≈ **0.48~0.55**；50cm ≈ **0.31~0.35** |
| `baselineDeg`（俯仰中位数） | 30cm 俯视 ≈ **8~14°**；50cm 平视 ≈ **0~2°** |
| 真实点头/仰头角速度 | 0.04~0.13 °/ms（慢动作 ≤0.006） |
| 真实扭头 | 20~45°，0.13~0.25 °/ms |
| 真实动作「onset→触发」延迟 | 俯仰 **53~249 ms**，扭头 **65~217 ms** |
| 眨眼（有意） | 单次闭眼 **139~758 ms**，两只眼都掉到 0.02~0.28 |
| 俯视眯眼（误计数） | 单眼低（`0.90/0.17`、`0.83/0.05`）、时长 845~3103 ms |
| 眨眼造成的姿态跳变 | **单帧 3.5°**（v5.14 抓到，v5.29 又抓到一次 3.1°） |
| 偏航对俯仰的耦合 | 扭头时会带出 **±3~±10°** 的俯仰分量（远距离通常够不到阈值） |

**用户设置**（SharedPreferences，会被 UI 覆盖，用 adb 改没用）：
灵敏度 `headPoseAngleThreshold = 6.0°`、`blinkTriggerCount = 3`、
`headPoseInvertPitch = false`（用户自己在 UI 里拨过）、全局冷却 2000 ms、
`horizontalSwipeAngleThreshold = 20°`、`horizontalSwipeInvertYaw = false`、
`staticLockEnabled = true`、`staticLockFactor = 1.5`、
`headPoseMotionWindowMs = 500`、`headPoseHoldMs = 150`。

**滑动几何（地面真值，`AdaptiveSwipe.pathFor`）**：
`UP → 0.70→0.30`（手指上移 = 下一个视频）、`DOWN → 0.30→0.70`（上一个视频）。
**眨眼通道硬编码为 UP**（下一个视频）。日志里 `GazeA11y: swipe UP/DOWN/LEFT/RIGHT` 是注入真值。

---

## 4. 代码结构（谁负责什么）

| 文件 | 职责 |
| --- | --- |
| `FaceGazeAnalyzer.kt` | CameraX ImageAnalysis → ML Kit 人脸检测 → `AnalyzedFrame`（pitch/yaw、双眼开合概率、嘴张开比、faceRatio、chinRatio，以及归一化关键点 `noseNormY/chinNormY/eyeNormY/noseRelEye/chinRelEye`） |
| `HeadPoseDetector.kt`（约 2800 行，核心） | 俯仰（点头/仰头）与偏航（左/右扭头）两套状态机、全部阈值与门控、所有诊断日志 |
| `BlinkDetector.kt` | 闭眼计数 → 眨眼触发；`eyeDip`（本帧眼睛是否跌破阈值）；近/中距离自适应收紧 |
| `MouthOpenDetector.kt` | 张嘴（点击） |
| `ReferencePointDetector.kt` | 用户提出的「参考点位移」通道：abs 轨道（关键点绝对 Y）+ rel 轨道（关键点相对眼睛，平移无关）；`relVerdict` = 它**自己确认**的方向，供俯仰通道当方向证人 |
| `PhoneMotionMonitor.kt` | 陀螺仪 + 线性加速度 → 「手机自己被顿了一下」（急停/急刹/手晃） |
| `GazeCameraService.kt` | 前台摄像头服务：逐帧串起上面所有检测器、距离档、遮挡抑制、全局冷却门、`SwipeInjector` |
| `GazeAccessibilityService.kt` / `SwipeInjector` / `AdaptiveSwipe` | 手势注入（无障碍 `dispatchGesture`，Shizuku 兜底） |
| `MainActivity.kt` / `activity_main.xml` / `AppPrefs.kt` / `GazeModel.kt` | 设置界面、持久化、`GazeConfig` 默认值与范围钳制 |
| `GazeSelfCheck`（服务内） | 每 30 秒一行全链路自检（相机是否有帧、无障碍是否连着、注入是否可用……） |

### 阈值表（灵敏度 6.0° 时）

- 远距离点头 4.5°（含注视增益）/ 6.0°；近距平视点头 4.08°
- **近距 + 俯视点头 2.5°**（`NEAR_DOWN_NOD_BOOST = 0.42`）
- **近距 + 俯视仰头 3.8°**（`NEAR_LOOKUP_BOOST = 0.63`）；其余仰头 6.0°
- 扭头 = 用户设定 20°（v5.8 起与距离解耦）
- 轻通道（仅点头方向）：`signedLight = signedPitch - settledPitch`，
  `settledPitch` = 连续 3 帧峰峰值 <1.0° 时的中点（"运动起点"），位移上限 12°

### 现有防护门（顺序即 `evaluatePitch` 里的判定顺序）

1. `below-onset`：|俯仰| < 0.4×阈值 → 清空本次动作
2. `yaw-swing-arbitration`（v5.28，近距离）：原始偏航 500ms 内摆幅 ≥12° → 让位（在 onset 之前）
3. `recenter-lock`：回中锁定（v5.27 重写：最短 400ms，解除需「不在动」且
   「回到中性区稳定 150ms」或「停在自己当前姿势上」，硬上限 3000ms）
4. `below-threshold`
5. `no-travel`（v5.28，近距离）：判定域里从运动起点走过 <0.5×阈值 → 拒
6. `ref-veto-up/down`（v5.27）：参考点通道**自己确认了相反方向**且位移越过它自己的阈值 → 拒
7. `eyes-unreliable`（v5.14，50ms）：闭眼后的姿态不可信；**强候选（阈值+2°，仅近距离）豁免**（v5.27）
8. `shake`：方向反转次数 ≥3（6 帧窗口）→ 判为抖动
9. `phone-motion`：手机自身被顿挫
10. `slow-rise`：onset→越阈值用时 > 动作窗口（**近距离：俯仰 300ms**，v5.28）
11. `jump-confirm`：单帧跳变要多撑 60ms；`cameFromRamp` 需要"前一帧已在动"且台阶 ≤4°
12. `hold-not-met` / `light-confirm` / `speed-gate`：**强候选跳过**（v5.27，仅近距离）
13. `yaw-dominant-arbitration`（v5.8/v5.11）：偏航在动 + 速度占优 + 幅度 ≥0.3×阈值

扭头通道（`evaluateYaw`）对应地有：`below-threshold` → `eyes-unreliable`（**强扭头豁免**，v5.28）
→ `phone-motion` → `slow-rise`（近距离 400ms）→ `hold-not-met` → `speed-gate`
→ `pitch-dominant-arbitration`。

### 日志长什么样（读日志必备）

- 每 3 秒一行 `GazeDiag`：`face= eyeL= eyeR= pitch= base= yaw= faceRatio= dist= staticLock=
  staticHardLock= recenterLock= pitchTh= pitchThUp= yawTh= shake= rev= accel= gyro= phoneMotion=
  eyeDip= needBlinks= lastTrigger= cooldown= cooling= …` 后面还跟两段参考点状态
  `abs dy=… opt=… | rel dy=… opt=…`
- 触发时：`nodDown triggered … light=yes(起点→终点) strong=… travel=… yawSwing=… shake=… rev=…
  latency=…ms (fast|held) — ctx 24f: -1000ms|pitch/yaw/faceRatio …`（`ctx` 是触发前 1.2 秒逐帧读数，
  判断"真动作 vs 抖动/跳变"最有用）
- 被拒时：`<dir> candidate rejected: pitch= speed= gate= threshold= dist= posture= shake= rev=
  boost= strong= refV=… thr=… yawSwing=… travel=… reason=…`
- 扭头触发：`右 turn triggered yaw=… latency=… yawSwing=… pitchNow=… — ctx …`
- 眨眼：`blink #N closure=…ms minEye=L/R below=… needFrames=… gap=… run=x/3 dist=near`
  以及 `closure rejected: …`、v5.29 新增 `closure ignored: …ms > 900ms`
- 注入真值：`GazeA11y: swipe UP/DOWN/LEFT/RIGHT pts=… 150ms = true`

---

## 5. 当前状态（v5.29，已装）

- **已验证好用**：近距离俯视的**上下滑**（v5.27 用户确认「好多了/没啥问题」）、
  **左右滑**（v5.28 用户确认「左右滑还好，没啥问题」）。
- **v5.29 修的两处「人没动却翻页」**（用户 21:00:28 报，日志抓到两次）：
  1. **眨眼通道**：原判据「任一只眼低于阈值即算闭眼」，而俯视时 ML Kit 常只读低**一只**眼
     → 7 秒凑够 3 次假眨眼 → 上滑。改为**两只眼都低于阈值**才算闭眼；并加
     **单次闭眼 >900ms 不算眨眼**（有意眨眼 139~758ms，误计数那批 845~3103ms）。
  2. **俯仰通道**：`eyes-unreliable` 被拒的帧**仍被记成 `previousFramePitch`**，
     导致闭眼造成的单帧 3.1° 姿态跳变被跳变确认判成"渐进动作"、零延迟放行。
     改为**闭眼帧不作证据**（不更新上一帧、不留速度样本），真实动作最多多等一帧。
- **待用户复测**：上面两条是否把「静止误触」清掉；眨眼 3 次是否仍然灵敏。
- 抓日志任务：`apk\v529-verify.log`（120 分钟窗口，后台 job）。

---

## 6. 版本历史（只记 v5.22 以后，每条都有实机证据）

| 版本 | 内容 |
| --- | --- |
| v5.22 | 用户提出「用参考点位移判断点头/仰头，不要硬算角度」→ 落地 `ReferencePointDetector`（只观测不接管） |
| v5.23 | 实测：用户的符号约定成立（点头 = 脸下移），但绝对位移噪声底与真实动作一样大 → 增设"平移无关"轨道 |
| v5.24 | 逐关键点统计，找仰头方向的干净信号 |
| v5.25 | 仰头慢的根因是**阈值太高** → 近距离俯视仰头阈值开到 **3.8°**；仰头判据用"鼻子相对眼睛"位移 |
| v5.26 | 只认可**快速**动作：轻点头速度门槛回到 0.020、轻通道位移上限 12°、证人否决双向对称 |
| v5.27 | 三根因治"仰头延迟/不触发/判反"：① 证人否决改看**证人自己的结论** `relVerdict`（旧判据看原始位移，起手瞬间必然≈0，2 分钟误否 **89 次**）；② `eyes-unreliable` 只拦边缘候选（仰头会把眼皮挤成半闭，实测连续 4 帧把一次 17.2° 仰头拦了 1.3 秒）；③ 强候选免等保持/速度门；④ 回中锁定去掉「400ms 无条件解锁」。**用户确认好用** |
| v5.28 | ① `yaw-swing-arbitration`：同一帧里扭头通道看到 29.5°、俯仰通道只看到 1.5°（偏航基准线被重学）→ 扭头被判成上滑；改用**与基准线无关的原始偏航摆幅**（500ms ≥12°，真扭头摆 25~59°、真点头仅 1.7~10.4°）；② 强扭头豁免闭眼门（25 次扭头候选 12 次死在 `eyes-unreliable`）；③ `no-travel` 位移判据；④ 突然性窗口（近距离 俯仰 300ms / 扭头 400ms）。**用户确认左右滑正常** |
| v5.29 | 见 §5（眨眼两只眼 + 900ms 上限；闭眼帧不作证据） |

回退点：git tag `v5.27-stable` / `v5.28-stable` / `v5.29-stable`，分支 `backup/v5.27`；
本地备份目录 `backup\GazeScroll-v5.29-stable`（含 APK 与 `ROLLBACK.txt`）、
另有 `GazeScroll-v5.8-stable`、`GazeScroll-v5.3-stable`。

---

## 7. 工作方法（这套流程是从多次翻车里总结出来的）

1. **先取证再改**：读上一版日志，把"用户描述的现象"翻译成计数与分布
   （例如"延迟"→ `ref-veto-up` 89 次 + 4 帧连续 `eyes-unreliable`）。
   在 `CHANGELOG.md` 里保留这些数字。
2. **一次只改一处**，并且**只在用户点名的场景生效**（通常是 `nearDistance` 前置条件），
   远距离与其它通道一个字不改 —— 这样若变差，回退范围很小。
3. **改完立刻在日志里加可核对字段**（`strong=` / `travel=` / `yawSwing=` / `refV=`），
   下一轮就能直接从日志判断判据是否生效。
4. **改前先想好"会不会误杀真实动作"**：用实测分布定门限
   （例如突然性窗口 300ms 的依据是真实动作延迟 53~249ms）。
5. 用户报"变差了"要**如实承认并回退**，不要辩解（历史上 v5.14/v5.17 都退过）。

### 已知陷阱（踩过的坑，别再踩）

- **域混用**：轻通道的位移是"相对运动起点的位移"，其余是"相对基线量"。两者相减 = 方向整体翻转
  （v5.17 事故）。位移判据的起点值必须与当前值**同域**记录（v5.28 的 `lastDecisionValue`）。
- **不要用触发延迟当"慢"的判据**：延迟里包含门控等待（远距离那两次 575/624ms 其实是被闭眼门
  等了几帧的**快速**仰头）。要量"onset→第一次越阈值"的用时。
- **基准线会被重学**（丢脸/换姿势 → `baseline cleared, re-learning`），依赖基准线的判据会突然失效
  （v5.28 的扭头判据就是因此失效）。跨通道仲裁尽量用**原始读数**（如原始偏航摆幅）。
- **`eyes-unreliable` 是双刃剑**：它能挡住闭眼造成的姿态跳变（3.5°），但也会挡住真实动作
  （仰头/扭头本身就把眼皮挤成半闭）。现行折中：强候选豁免 + 只有"闭眼帧"不作证据。
- **SharedPreferences 以 UI 为准**：用 adb 改设置会被用户在设置页的保存覆盖。
- **adb 无线调试端口会变**，息屏就掉；capture-loop.ps1 会自动重连。
- 不要用 `-replace` 直接改 markdown（曾注入 NUL/CR 把中文搞坏）；要改用 .NET 字符串写入
  `[System.IO.File]::WriteAllText($p,$t,(New-Object System.Text.UTF8Encoding($false)))`。

---

## 8. 未解决 / 下一步候选（按优先级）

0. **先与并行会话对齐**（见 §0）：确认当前 `git log` / `git status`，别覆盖对方的文件。
1. **v5.29 两小时实机日志的结论**（`apk\v529-verify.log`，21:08 ~ 23:09，用户一直在用）：
   - **眨眼假触发基本消失**：两小时里只翻了 4 次「眨眼页」（21:09:34、21:09:39、22:03:36、22:49:09），
     而 v5.27/v5.28 是 **5 分钟 24 次**。238 次计数眨眼的 `minEye` 全部是「两只眼都低」
     （0.00~0.29），原先那种 `0.90/0.17` 单眼假眨眼再没出现；`closure ignored`（>900ms）28 次。
     用户有意的三连眨仍然有效（run=3/3 的间隔 305~1455ms）。
   - `no-travel` 拒了 **64 次**、`yaw-swing-arbitration` 15 次、`recenter-lock` 106 次、
     `eyes-unreliable` 116 次、`jump-confirm` 135 次；俯仰触发 168 次，绝大多数都有参考点证人确认
     （`refV=…opt=ok`、`rel dy` -0.05~-0.11）。左右滑正常（21 次扭头触发方向全对）。
   - **遗留**：仍有约 8 次触发的证人读数完全沉默（`abs dy=+0.003 opt=below-onset | rel dy=-0.001`）
     —— 疑似姿态估计跳变但关键点没动，约 15 分钟一次。若要继续压，方向是
     「跳变型候选（`cameFromRamp=false`）要求证人不能沉默」。
   - **触发日志里 `travel=-` 是我自己的日志 bug**（`clearExcursion()` 在拼日志之前就把标志清了），
     `no-travel` 判据本身是有效的（64 次拒绝都打出了真实位移值）。修法见 §9 第一条补丁。
2. **`no-travel` 的域切换跳过**（v5.28 遗留，见 §9 第一条）：v5.28 用"判定域起点 + Valid 标志"，
   域切换时直接跳过判据；实测触发日志全部 `travel=-` 说明跳过得很频繁。
   改成用**原始俯仰**算位移（两个域通用）即可，补丁已备好。
3. 轻通道参考点过期：`settledPitch` 落后时小位移会被放大成"越阈值"（实测 20:47:22：原始
   0.7°→0.1° 却报出 -5.5° 位移）。可给轻通道加原始位移下限。
4. 若仍误触：把近距离俯视点头阈值 2.5° → 3.2°（`NEAR_DOWN_NOD_BOOST` 0.42→0.53），属灵敏度取舍，
   必须让用户知情后再改。
5. 若仰头/点头变迟钝：检查 §4 判定顺序里的 slow-rise(突然性窗口)、jump-confirm、hold-not-met。

---

## 9. 我起草但**已回退**的改动（补丁在 `D:\ruanjian\deepseek harness\v530-travel-gate.patch`）

1. **位移判据改成原始俯仰域 + 修日志**（因为并行会话在改同一文件，我把它撤出工作区了）：
   - `excursionStartRaw = previousFramePitch`（onset 时记上一帧的**原始**有符号俯仰）；
   - 判据 `travelDeg = |signedPitch - excursionStartRaw| ≥ 0.5 × 阈值`（两个判定域通用，
     不再需要 `excursionStartValid` 那套域检查）；
   - 触发/拒绝日志统一打 `travel=<travelDeg>`（原来触发日志恒为 `-`）。
   理由见 §8 第 2 条。补丁基于 `d7e4cc8`（对方 v5.37）生成，可直接 `git apply`。

---

## 附录 A：常用核对命令

```powershell
# 版本是否装对
& $adb shell dumpsys package com.example.gazescroll | Select-String versionName
# 全链路自检（每 30 秒一行）
& $adb logcat -d -s GazeSelfCheck:* -v time | Select-Object -Last 1
# 本场所有触发与注入真值
& $adb logcat -d -v time | Select-String "triggered|GazeA11y.*swipe"
# 从抓取文件里统计被拒原因
(Get-Content 'D:\ruanjian\deepseek harness\apk\v529-verify.log' -Encoding UTF8) |
  Select-String "candidate rejected" |
  ForEach-Object { if ($_.Line -match "reason=(\S+)") { $Matches[1] } } |
  Group-Object | Sort-Object Count -Descending
# 确认新逻辑真的进了 APK（解压 classes*.dex 后扫字符串）
Add-Type -AssemblyName System.IO.Compression.FileSystem
# …（见历史命令；关键：dex 在 zip 里是压缩的，必须先解出来再扫字符串）
```

## 附录 B：当前抓日志的后台任务

- `apk\v529-verify.log`（v5.29，120 分钟）—— 用户复测后先读这个文件。
- 旧的 `v526/v527/v528-verify.log` 保留着，作为各版本证据链。

# GazeScroll 交班说明（锚点版本：v5.36）

> **⚠️ 2026-09-17 补充（v5.39）**：工作区现在**不是** v5.36，而是
> **v5.39 = v5.36 + 「注视数据采集」测试功能**（默认关闭、只记录不判定，
> 不开时逐帧开销与 v5.36 完全一致 → 行为等同锚点）。锚点仍是 **v5.36**，
> 一键装回用 `tools\install-v536.ps1`。细节见 `CHANGELOG.md` 的 `[5.39]` 一节、
> `README.md` 的「注视数据采集（v5.39，测试功能）」一节、`backup\GazeScroll-v5.39\ROLLBACK.txt`。
> 版本号之所以是 5.39：**v5.37 / v5.38 已被上一轮那次失败实验占用**（tag / backup / apk / 文档条目）。
> 本文件其余内容（v5.36 的状态清单、判据全表、事故复盘、经验教训）**依然有效**。

> 写给下一个接手的人（新的 DSH 会话）。这份文档**自包含**：不需要读历史对话，
> 也不需要猜上一轮发生了什么。所有结论都有实机日志或离线回放支撑，出处都写在文中。
>
> 生成时间：2026-09-17 凌晨 · 生成者：上一轮会话
> 当前工作区代码 = **v5.36**（用户认可的锚点版本），`git tag v5.36-anchor`。

---

## 📋 新会话开场白（把下面这段直接粘给下一个会话）

```text
接手一个已经在做的 Android 项目：免手刷抖音的 GazeScroll。
工作区：D:\ruanjian\deepseek harness\GazeScroll
请先完整读 GazeScroll\HANDOVER.md（自包含交班文档，包含硬约束、当前锚点 v5.36 的全部状态、
环境与命令、功能与判据全表、历史事故与教训、两个已知小问题、下一步建议）。
读完先给我一句话总结，然后等我派活。本轮不要改任何代码。

要点提醒：
- 当前锚点版本 v5.36（用户认可），手机上已装；工作区代码就是 v5.36，git tag v5.36-anchor。
- 不联网、不推 GitHub；自己用 adb 抓日志，不要找我要日志。
- 一次只改一件小事，改前备份 + git tag，改完装机实测，并在日志里留可核对的新字段。
- 除了我点名的功能，其他一律不动。
```

---

## 0. 一句话

小米 13 前置摄像头做免手翻页（刷抖音 `com.ss.android.ugc.aweme`）：眨眼 3 次 = 下一个、
点头/仰头 = 上下翻、左右扭头 = 左右滑、张嘴 = 点击屏幕中央；**v5.30~v5.35 试过"单眼闭眼控音量"，
四轮都做不稳，已废弃**；v5.35 起改成 **歪头（roll）→ 音量加/减**，v5.36 是用户认可的稳定锚点。
纯本地 Android/Kotlin 工程，不联网、不推 GitHub。

---

## 1. 硬约束（用户的要求，务必遵守 —— 违反会直接被退回）

1. **不联网、不推 GitHub**，全程本地开发 + 本地 git 提交。
2. **不要要求用户提供日志**：自己用 adb 抓（无线调试，见 §3）。用户明确说过这点。
3. **不要问用户"你当时在做什么"**：用户会描述现象（"23:00:50 左右音量不降反增"），
   剩下的靠日志自己还原。
4. **一步一小改**：一版只改一件事（最多一组相关的事），改完实测、留证据。
5. **改前备份、留回退点**：`backup\GazeScroll-vX.Y` + `git tag`。
6. **版本号递增**，并同步 `README.md` / `CHANGELOG.md`（用户会检查）。
7. **不要动（除非用户点名）**：阈值表本身、方向仲裁（v5.8 那套）、远距离行为、
   30cm 静止硬锁定（`HARD_LOCK_NEAR_RATIO=0.55` / `HARD_LOCK_STILL_MS=1200`）、
   眨眼档位、用户的设置项。
8. 用户说"其余的不要动" = **只改他点名的那一处**，其他行为一个字节都别变。
9. **用户验收方式**：自己拿手机实测 → 口头反馈 → 我读日志核对。
   所以每次交付都要**在日志里留下可核对的新字段**（这套做法很有效，请延续）。
10. **用户觉得变差了就如实承认并回退**，不要辩解。本会话就回退过一次（v5.37 → v5.36）。

---

## 2. 当前状态（事实清单，全部核对过）

| 项目 | 值 |
| --- | --- |
| 手机上安装的版本 | **v5.36**（`versionCode=86`，`versionName=5.36`），已确认 `dumpsys package` |
| 工作区代码 | **v5.36**；`git checkout v5.36 -- app ... tools` 的结果；HEAD = `a55f965` |
| 关键校验 | 用工作区源码**重新构建**出的 APK 与 `apk\gazescroll-5.36-debug.apk` **逐字节一致**（hash 相同）→ 装的就是当初认可的那一份 |
| git 标签 | `v5.36-anchor`（当前锚点）、`v5.36`、`v5.29-stable`、`v5.3-stable`、`v5.8-stable`；v5.30~v5.38 的标签也都在 |
| 备份 | `backup\GazeScroll-v5.36`（**用户认可的一版**，含源码 + APK + ROLLBACK）、`GazeScroll-v5.38` / `-v5.37` / `-v5.35` / `-v5.34`（历史上还有 v5.29/v5.8/v5.3） |
| APK 归档 | `apk\gazescroll-5.3*.apk`（5.30~5.38 都在）；**要装的是 `gazescroll-5.36-debug.apk`** |
| 验证日志 | `apk\v536-anchor-verify.log`（当前锚点会话，含 `Tilt:` 明细）；历史 `v5xx-verify.log` 一大堆 |
| 一键装回 | `GazeScroll\tools\install-v536.ps1`（找设备 → `install -r -d` → 核对版本 → 重启抓取） |

**用户当前设置**（以设置页为准；日志里观测到的值）：
灵敏度 6.0°、眨眼次数 **3**（会话里也出现过 2）、`headPoseInvertPitch=false`、
全局冷却 2000ms（开）、hSwipe 20°、`invertYaw=false`、静止锁定开/1.5、
动窗 500ms / 保持 150ms、自适应滑动 26%、张嘴灵敏度 MEDIUM；
**歪头控音量：开、左=调高/右=调低**、触发角度**试过 10°，最后一次用的是 13°**、保持 0.3 秒、
每次 1 档。**不要主动覆盖用户的这些设置。**

---

## 3. 环境、路径与常用命令（照抄即可）

```powershell
# 路径
$proj  = 'D:\ruanjian\deepseek harness\GazeScroll'                       # 工程源码
$apkD  = 'D:\ruanjian\deepseek harness\apk'                              # APK 与验证日志
$adb   = 'D:\ruanjian\deepseek harness\.android-build\android-sdk\platform-tools\adb.exe'
$jdk   = 'D:\ruanjian\deepseek harness\.android-build\jdk\jdk-17.0.20.1+1'
$sdk   = 'D:\ruanjian\deepseek harness\.android-build\android-sdk'

# 构建（必须 --offline：AGP 8.6.1 已缓存在 C:\Users\WIT_User\.gradle）
$env:JAVA_HOME=$jdk; $env:ANDROID_HOME=$sdk
cd 'D:\ruanjian\deepseek harness'
.\GazeScroll\gradlew.bat -p GazeScroll --console=plain --offline assembleDebug

# 发布 + 安装 + 启动
Copy-Item "$proj\app\build\outputs\apk\debug\app-debug.apk" "$apkD\gazescroll-5.36-debug.apk" -Force
& $adb install -r -d "$apkD\gazescroll-5.36-debug.apk"     # -d = 允许降级
& $adb shell "dumpsys package com.example.gazescroll | grep versionName"
& $adb shell am start -n com.example.gazescroll/.MainActivity

# 抓日志（必须用 powershell，不是 pwsh；这台机器 PATH 里没有 pwsh）
powershell -NoProfile -ExecutionPolicy Bypass -File "$proj\tools\capture-loop.ps1" `
    -Out "$apkD\v536-anchor-verify.log" -Minutes 180
```

**设备**：小米 13（fuxi/2211133C，HyperOS 3.0.308.0，Android 14 / SDK 34）。
无线调试序列号形如 `192.168.3.32:<port>`，**端口每次息屏后会变**；掉了就用
`& $adb mdns services` 找 `_adb-tls-connect` 再 `& $adb connect <ip:port>`；
`capture-loop.ps1` 已内置重连（它会在日志里写 `device not reachable, retrying`）。
若 mDNS 什么都搜不到、但手机还能 ping 通（`Test-Connection 192.168.3.32`），
说明**手机上无线调试被关了** → 让用户去「开发者选项 → 无线调试」打开，别干等。

---

## 4. v5.36 有什么（功能与判据全表）

### 4.1 触发通道

| 通道 | 动作 | 结果 | 代码位置 |
| --- | --- | --- | --- |
| 眨眼 | 连眨 N 次（用户设 3） | 上滑（下一个视频），硬编码 | `BlinkDetector.kt` |
| 点头 | 俯仰向下越阈值 | 下滑（上一个） | `HeadPoseDetector.kt` |
| 仰头 | 俯仰向上越阈值 | 上滑 | 同上 |
| 左/右扭头 | 偏航越阈值 | 左滑/右滑 | 同上 |
| 张嘴 | 一次 | 屏幕中央点击（暂停/播放） | `MouthOpenDetector.kt` |
| **歪头** | 相对头姿基准线歪到阈值并保持 | **音量 ±N 档**（默认左=加、右=减） | `TiltDetector.kt` |

### 4.2 歪头控音量（v5.35 引入，v5.36 定稿）—— 本会话的主要新功能

判据（全部满足才触发）：

1. **`tilt = roll − baseline`**，`roll` 是 ML Kit 的 `headEulerAngleZ`（滚转/歪头）。
   `baseline` = 最近 **45 帧**（≈4 秒）滚转角的中位数。
2. **歪着的时候不更新基准线**：只有 `|tilt| ≤ 阈值` 时才把这一帧喂进基准线窗口，
   否则一次故意歪头（停 1~2 秒）会把中位数带走。（这是 v5.36 的修复，见 §5.3）
3. `|tilt| ≥ 阈值`：可选 **10 / 13（默认）/ 16 / 20°**（设置页「灵敏度 · 触发角度」）。
4. 保持 **0.2 / 0.3（默认）/ 0.5 / 0.8 秒**（「灵敏度 · 保持时间」）。
5. **一次歪头只调一组档位**：触发后必须回到中位带（`|tilt| ≤ 0.4×阈值`）并保持 400ms。
6. **两秒动作间隔、没有提前量**（用户原话要求）：起手那一帧必须晚于
   `上一次任何动作（翻页/调音量/张嘴）+ 2 秒`，早了整段作废（不顺延）。
7. 读数 >55°（躺下/侧脸野值）→ 丢弃基准线窗口重学。
8. 一次调几档：1（默认）/2/3/5，多档**一步跳到位**、只弹一次音量面板。

其它：这是"控制指令"，**不走全局冷却**（不占用也不被挡）；不需要无障碍/Shizuku
（`AudioManager` 直调 `STREAM_MUSIC`）。**歪头期间会暂停翻页判定**（见 §5.4 的代价）。

### 4.3 日志字段（读日志必备）

- 每 3 秒一行 `I/GazeDiag`：
  `face= eyeL= eyeR= pitch= base= yaw= **roll=** yawBase= mouth= … faceRatio= dist= staticLock=
  staticHardLock= recenterLock= **pitchTh= pitchThUp= yawTh=** yawNow= pitchNow= …
  shake= rev= accel= gyro= eyeDip= **abs dy=… opt=… | rel dy=… opt=…**
  lastTrigger= lastTriggerAgeMs= blinkBelow= blinkFrames= blinks= **tiltVol= tiltDir= tilt=<倾斜角>
  base=<基准线> thr=<阈值> held=<已保持> peak=<峰值> fired=<累计> last=<最近一次> tiltSteps=<累计档位>**
  **gapRemain=<距两秒动作间隔还剩>** baselineSettling= triggers= needBlinks= cool…`
- 歪头触发：`I/Tilt: tilt 左歪头 held=387ms tilt=-33.5° peak=37.6° thr=13° base=0.9°
  neutralBefore=0ms dist=near -> volume UP 1 档 (1档=10) 20->30/150 applied=true
  roll=-32.6° pitch=7.0° chin=0.294 faceRatio=0.51`
- 歪头被作废：`I/Tilt: 右歪头 起手太早（还剩 89ms 才满两秒）→ 这一段不算，回正后重新歪`
- 触发：`I/HeadPose: nodDown/tiltUp triggered … travel= yawSwing= shake= rev= — ctx 24f: …`
  （ctx 是触发前约 1.2 秒的逐帧 `pitch/yaw/faceRatio`）
- 被拒：`… candidate rejected: pitch= speed= gate= threshold= dist= posture= shake= rev=
  boost= strong= refV=… thr=… yawSwing=… travel=… reason=…`
- 眨眼：`I/Blink: blink #N closure=…ms minEye=L/R …` / `closure rejected:` / `closure ignored:`
- 注入真值：`I/GazeA11y: swipe UP/DOWN/LEFT/RIGHT …`、`tap center (x, y) = true`
- 前台与恢复：`I/AppState: window changed:/foreground=…`、
  `I/GazeDiag: foreground change: A -> B (target=… reason=…)`、
  `I/GazeCameraService: camera released: warm window elapsed|screen off`、
  `I/A11yBootstrap: enabled but not bound — forcing a rebind`、
  `I/GazeSelfCheck: periodic … targetActive= cameraBound= stale= framesAgoMs= a11yConnected=`

**注意**：Android 会给短 tag 补空格，正则要写成 `I/Blink\s*\(`、`I/Tilt\s*\(` 这样。

---

## 5. 本会话做过什么（每一步的证据与结论，避免重复踩坑）

### 5.1 v5.30~v5.34：「单眼闭眼控音量」四轮都失败 → **这条路不要再走**

用户最初要的是"单闭右眼=音量+、单闭左眼=音量−"。做了四版，全部被实机否掉：

| 版本 | 判据 | 实测结果 |
| --- | --- | --- |
| v5.30 | 一只眼 <0.55 + 另一只 >0.70 + 保持 1 秒 | **一场 90 秒里误调 11 次，音量从 50 打到 0**（低头看屏幕时 ML Kit 把一只眼**反复读低**几十秒） |
| v5.31 | 加"闭前必须明确睁着(>0.65) ≥600ms" | 把**真单闭也挡掉**（"一点动静没有"，22:17:56~22:18:05 连续 5 次） |
| v5.32 | 删那条、另一只眼门槛降到"不是闭着" | **眨眼也能调音量**（22:25:56：两只眼一起半闭 0.54/0.65，差只有 0.11） |
| v5.33 | 核心判据换成"两眼读数差 ≥0.30" + "合眼 ≤500ms" | 误触发没了，但**真单闭又被挡掉大半**（"还是不灵敏"）—— 真单闭的 onset 是 55~1732ms，与 v5.30 那批误触的 1.6~3.2s **完全重叠** |
| v5.34 | 换时间域判据："最近 5 秒闭眼占比 ≤0.30" | 低头刚开始那一下仍会误调；用户最终放弃这条通道 |

**四条硬结论（别再试了）**：

1. **绝对阈值分不开"眨眼/半闭"与"单眼闭"**：眨眼时两只眼一起落在 0.5~0.7，真单闭时闭的那只
   也能停在 0.54。0.70 / 0.65 / 0.55 三条线都试过，要么误触发要么漏触发。
   能分开的只有**两只眼差多少**（真单闭实测 0.47~0.88，误触发 0.11）。
2. **`eyeOpenProbability` 的下降快慢不等于眼皮的物理快慢**：ML Kit 是逐帧软分类，真闭眼也会
   拖几帧甚至一秒多才掉到底（实测真单闭 onset 658/1216/1732ms）。**这条不能当判据。**
3. 用户提过的「闭一只眼就读不到另一只眼」**不成立**：全部历史日志（v5.10 起 33 个文件）
   **3153 帧有脸画面里，没有任何一帧只缺一只眼的读数**（ML Kit 有脸就给两只眼的概率）。
4. 时间域统计（duty）比瞬时快慢稳，但只能挡"**反复**被读低"，挡不住低头**刚开始**那一下。

### 5.2 v5.35：换成歪头（roll）→ 音量

- 关键发现：**`headEulerAngleZ`（滚转）以前从来没采集过**（v5.10~v5.34 的 `AnalyzedFrame`
  只带 X/Y）。用户 22:38:50 之后"歪头"在日志里只看到副作用：歪头让俯仰串扰几度，
  被近距离档（阈值 2.5°）当成仰头，**顺带翻了两页**。
- 新增 `AnalyzedFrame.headEulerAngleZ` + `TiltDetector.kt` + 设置页一整套（方向开关 / 角度 / 保持 / 档位）
  + 删除 `WinkDetector.kt` 与 `tools/wink-replay`。
- 同时加了「**歪头期间暂停翻页判定**」（喂 `headPoseDetector` 时跳过）+ 歪头结束时
  `recalibrate()`。**这个暂停后来成了 v5.37 事故的一半原因**，见 5.4。

### 5.3 v5.36：用户认可的锚点（**当前版本**）

改了三处（都在歪头通道内）：

1. **基准线在歪着时不更新**（防"歪着头停 1~2 秒 → 中位数被带走 → 回正被读成反方向"）。
2. **全局两秒 + 没有提前量**（用户明确要求）：新增动作时钟，歪头起手必须晚于
   `上一次任何动作 + 2 秒`，早了整段作废；反向也成立 —— 调音量后 2 秒内不翻页
   （`GlobalTriggerGate.extendCooldown()`）。
3. **灵敏度整体调灵一档**：角度 12/15/18/22 → **10/13/16/20**（默认 13）；
   保持 0.3/0.5/0.8/1.0 → **0.2/0.3/0.5/0.8**（默认 0.3）。
4. 设置页实时区加了 **两秒动作间隔倒计时 + 最近的作废原因**（用户说"判断不好时间"）。

**v5.36 的实机战绩**（`v536-anchor-verify.log`，23:27~23:36）：
**歪头成功调音量 11 次**、被作废 17 次；翻页 `swipe UP 42 / DOWN 4`；
被拒原因 Top：`below-threshold 35 / jump-confirm 31 / eyes-unreliable 19 /
recenter-lock 17 / hold-not-met 12 / slow-rise 11 / no-travel 4`。

### 5.4 v5.37 → v5.38 → 回退 v5.36：一次完整的事故与复盘

用户报「回正脖子时音量不降反增」（23:00:50）。日志取证（`v536-verify.log`）：

```
23:00:19.977  roll=+3.1°  基准线=3.8°   tilt=-0.9°     ← 正常
23:00:23.042  roll=+0.6°  基准线=32.6°  tilt=-28.5°    ← 基准线突然变成 32.6°
23:00:26.090  roll=+5.4°  基准线=32.7°  tilt=-22.4° → 触发"左歪头"= 升 ❌
```

本人真实头姿只有 +1~5°，基准线却被锚在 **+32.6°** → "什么也没做"被读成"往左歪 28°" → 升音量。
**根因**：45 帧基准线窗口被服务侧 `reset()`（遮挡/静止硬锁定/换应用/重绑都会调）清空后，
在"用户正歪着头"的那几帧上重建；而 v5.36 的"歪着时冻结"又让它永远错下去。

v5.37 我加了三条判据去修它，结果**三条都过头了**（用户："真不如上一版灵敏，
这一版还把我近距离俯视给仰头给弄死了"）：

1. 「连续歪着 >2.5 秒 → 判定为姿势、重锚基准线 + 之后自禁判 2 秒」→ **重锚风暴**：
   `23:08:42 -29.3° / 23:08:50 -14.2° / 23:09:00 37.0° / 23:09:03 5.7° / 23:09:06 11.0°`
   —— **25 秒 5 次**，用户"歪住不动"的动作每次都命中，可用时间被吃掉大半。
2. 「起手前 1.2 秒内必须出现过中位带」→ 基准线一动就自己否自己：
   `23:09:10 左歪头 起手之前没有中位（距上次中位 从未）→ 这一段不算`。
3. 它与"歪头期间暂停翻页"叠加：把 `|tilt| > 0.4×阈值(5.2°)` 的**占空比从 v5.36 的 12% 拉到 35%**
   —— 翻页通道三分之一时间拿不到头部数据（`onHeadPose` 被跳过 + 每次切换还让头部基准线重学），
   近距离俯视仰头（3.8° 档、依赖轻通道+稳定基准线）因此"死"了。

**v5.38** 把这三条删干净、并把暂停门槛收回成 `|tilt| ≥ 阈值`；但用户此时已决定
**以 v5.36 为锚点**（"退回 5.36，我先多体验一下，确认大问题没有再说，小问题慢慢修"），
所以最终动作是：**工作区回退到 v5.36**（`a55f965`，`tag v5.36-anchor`），
v5.37/v5.38 的代码与 APK 保留（`tag v5.37/v5.38`）。

**唯一被验证过"零灵敏度代价"的修复**（还没有装进 v5.36，等用户点头）：
**让 `TiltDetector.reset()` 不清基准线窗口**（只清手势状态）。它是上面那次"基准线锚到 32.6°"
的真正根因修复；但 v5.37 同时加的另外两条**不能一起带回来**。

---

## 6. 经验与教训（用户点名要传下来的）

### 6.1 方法（这套做法在本项目被证明有效，请照做）

1. **先取证再改**：把用户的现象翻译成日志里的计数/分布，并**把数字写进 CHANGELOG**。
   例："延迟" → `ref-veto-up 89 次 + 连续 4 帧 eyes-unreliable`；"不降反增" →
   `基准线 3.8° → 32.6°`。
2. **一次只改一处，且只在用户点名的场景生效**（通常是 `nearDistance` 前置条件或某个通道内部），
   其他通道一个字不改 —— 变差时回退范围才小。
3. **每次交付都加可核对字段**（`sep=` / `duty=` / `travel=` / `gapRemain=` / `peak=` / `roll=`）。
   用户会拿手机试、我读日志核对，这是最快的闭环。
4. **能用离线回放验证的，就先离线跑**：`tools/tilt-replay/run.ps1` 会把**真实的
   `TiltDetector.kt`** 用本机 Gradle 缓存里的 kotlinc 编译起来（只把 `android.util.Log`
   换成打印桩），回放实测序列。**它已经抓出过两个我自己写的 bug**（基准线在窗口没填满时照样被带走；
   1 档会变成 2 档）。这套做法值得复制到别的纯逻辑检测器上（BlinkDetector 也可以）。
5. **备份+标签**：`backup\GazeScroll-vX.Y` + `git tag vX.Y`；用户认可的版本要额外标出来
   （见 `backup\GazeScroll-v5.36\ROLLBACK.txt` 开头）。
6. **改完立刻实测**：装到手机上跑，别只看代码。

### 6.2 判据设计（血泪）

1. **绝对阈值不如相对量**：两只眼的**差**、相对**本人基准线**的角度，比"某只眼 < 0.7"稳得多。
2. **不要把"软的、有惯性的分类输出"当成物理量**（ML Kit 的 `eyeOpenProbability` 就是这样）。
   判断"动作快慢"要用几何量（角度、位移），不要用概率。
3. **冻结某个参考值（基准线）时必须回答两个问题**：
   ① 它会不会被"动作本身"带走？② `reset()`/丢脸/换应用时该不该清？
   本会话两次事故都出在这里（v5.36 的清掉、v5.37 的重锚）。
4. **任何"暂停/冻结另一条通道"的改动，都要量化它的占空比**。
   我就是用诊断行里 `|tilt| > 0.4×阈值` 的占比（12% → 35%）才找出"仰头变钝"的元凶。
5. **过严的门会自己打死自己**：本项目反复踩同一个坑（v5.11 加帧数、v5.31 加"睁着 ≥600ms"、
   v5.33 加 onset、v5.37 加三条）。加严之前先问："它会不会挡住真实动作？"
6. **判定顺序很敏感**：`HeadPoseDetector.evaluatePitch` 的顺序是
   `below-onset → yaw-swing-arbitration → recenter-lock → below-threshold → no-travel →
   ref-veto → eyes-unreliable → shake → phone-motion → slow-rise → jump-confirm →
   hold-not-met/light-confirm/speed-gate → yaw-dominant-arbitration`。
   往这个链条里插东西之前，先读 CHANGELOG 里它每一步的来历。
7. **域混用是经典事故**：轻通道位移是"相对运动起点的位移"，其余是"相对基线量"，
   两者相减 = 方向整体翻转（v5.17 事故）。位移判据的起点必须与当前值**同域**记录。

### 6.3 工具链的坑

1. **抓取标签必须和代码里的 tag 对上**：v5.35/v5.36 我漏了 `Tilt:V`，导致那两场日志里
   `I/Tilt` 明细**一行都没有**，害我多花很多时间。`tools/capture-loop.ps1` 的 `$tags`
   现在是 `GazeDiag HeadPose Blink Tilt PhoneMotion RefPoint GazeA11y GazeCameraService
   GazeSelfCheck AppState A11yBootstrap`。**加新 log tag 时第一件事就是把它加进去。**
2. **抓日志用 `powershell`，不要用 `pwsh`**（这台机器 PATH 里没有 pwsh，会报
   "The term 'pwsh' is not recognized"）。
3. **不要用 PowerShell 的文本替换去改 markdown/源码**：曾注入 NUL/CR 搞坏中文。
   要用 `edit` 工具，或 `[System.IO.File]::WriteAllText($p,$t,(New-Object System.Text.UTF8Encoding($false)))`
   并在写完后核对 `BOM`/`NUL`/`CR`。**`.ps1` 文件保持纯 ASCII**（Windows PowerShell 5.1 会把
   无 BOM 的 .ps1 按 ANSI 读，中文注释会炸）。
4. **SharedPreferences 以 UI 为准**：用 adb 直接改会被 App 覆盖。读用户设置用
   `& $adb shell "run-as com.example.gazescroll cat /data/data/com.example.gazescroll/shared_prefs/gaze_scroll_prefs.xml"`。
5. **adb 无线调试端口每次息屏都会变**；手机上无线调试被关掉时，只要手机还能 ping 通，
   就让用户去开发者选项打开，别反复重试。
6. **APK 指纹**：换版本后核对 `& $adb shell dumpsys package com.example.gazescroll | grep versionName`；
   要确认"装的就是某个源码状态"，可以重新构建后比对 APK 的 SHA256（本会话验证 v5.36 时用过，
   逐字节一致）。
7. 离线回放的 kotlinc 调用需要这些 jar（都在本机 Gradle 缓存里）：
   `kotlin-compiler-embeddable-2.0.21` + `kotlin-stdlib-2.0.21` + `kotlinx-coroutines-core-jvm-1.7.3`
   + `trove4j` + `org.jetbrains:annotations:23.0.0`；`javac` 要加 `-encoding UTF-8`。
   完整命令见 `tools/tilt-replay/run.ps1`（照抄就能给别的检测器搭一套）。

---

## 7. 已知问题（用户 2026-09-17 凌晨反馈，都还**没修**）

> 用户原话：「大的问题没有，但还是有一些小问题……不过这些触发率都很低」
> **这两个问题用户明确说"慢慢修"，动之前先问他。**

### 7.1 从待机状态打开抖音，有时不触发；拉一下通知栏、或提前打开万能翻页就好了

- 现象：手机待机 / 在桌面 → 打开抖音 → 动作不触发；**下拉通知栏（或先打开本 App）后恢复**。
- 机制（日志佐证）：手机离开目标应用后，相机在"warm window"到点会被**主动释放**：
  `I/GazeCameraService: camera released: warm window elapsed`（23:34:23）、
  `camera released: screen off`（23:36:23）。回到抖音时靠"前台窗口变化"重新武装：
  `AppState: window changed: … -> com.ss.android.ugc.aweme (allowed=true)`、
  `GazeDiag: foreground change: … -> com.ss.android.ugc.aweme (target=true reason=enter)`、
  `window changed to com.ss.android.ugc.aweme -> detector reactivated (reason=enter)`。
  **HyperOS 会过滤第三方 App 的无障碍窗口事件**（见 README「当前痛点」），
  所以"打开抖音"这一下未必产生事件 → 不重新绑定相机 → 不出帧 → 不触发；
  而"拉通知栏"会产生 SystemUI 的窗口事件 → 顺手把前台判定与流水线唤醒。
- 排查起点（按顺序看）：
  `AppStateManager`（`onForegroundPackage` / `pollNow` / `forceActive` / 目标列表判定）、
  `GazeCameraService.onTargetEntered` / `ensurePipelineForActive` / `onTargetLeft` /
  warm window 的释放策略（搜 `warm`、`camera released`、`FRAME_TIMEOUT_MS`）、
  `AccessibilityBootstrap.repairIfNeeded`（`enabled but not bound — forcing a rebind`）、
  以及 `GazeSelfCheck` 行里的 `framesAgoMs / stale / targetActive / cameraBound / rebindings`。
- 候选方向（**未验证**）：① 在前台判定为"目标 App"但 `framesAgoMs > 阈值` 时
  **强制走一次完整重绑**（看门狗现在只在"应该分析"时武装，被主动释放的相机不在它的射程内）；
  ② 提高前台轮询频率 / 让轮询结果也能触达"重新武装"；③ 缩短 warm window / 目标 App 前台时不释放。

### 7.2 近距离俯视：有时灵、有时误触、有时延迟（频率都不高）

- 这一档（`distMode=near` + `posture=down`）阈值最低、最灵敏，天生难调：
  点头 2.5° / 仰头 3.8°（`NEAR_DOWN_NOD_BOOST=0.42` / `NEAR_LOOKUP_BOOST=0.63`）。
- **延迟**来自判定链上的多道门（v5.36 场统计）：
  `slow-rise 11 次`、`hold-not-met 12 次`、`jump-confirm 31 次`、`recenter-lock 17 次`、
  `eyes-unreliable 19 次` —— 这些是真实动作被"等/拦"的地方，日志里逐条可查。
- **误触**的候选来源：轻通道（`signedLight = signedPitch − settledPitch`）的"运动起点"过期
  （v5.36 曾实测原始俯仰只动 0.7° 却报出 5.1° 位移）；`travel=-` 残留（判定域两帧之间切换时
  位移判据被跳过，v5.28 引入、至今没修）。
- 可用旋钮（**都要先让用户知情**）：`NEAR_DOWN_NOD_BOOST` 0.42 → 0.53（2.5° → 3.2°）；
  给轻通道加"原始位移下限"（v5.36 CHANGELOG 里记过这个方案）；修 `travel=-`。

### 7.3 其它已记录的残留

- 歪头方向万一和实际相反：设置页两个开关各拨一下（日志 `roll=` / `tilt=` 可核对）。
- 隐藏坑：`baselineSettling`（v5.31 加的"基准线重建期禁触发"）会在丢脸回来后约 1.1 秒内
  停掉头部触发；这是有意为之，别误判成 bug。

---

## 8. 下一步建议（等用户点头再动）

1. **先让用户把 v5.36 用熟**（他正在做这件事）。期间不要改任何行为。
2. 用户回来抱怨具体某一条时，按 §6.1 的流程走：
   抓日志 → 量化 → 选**最小**改动 → 离线回放 → 装机 → 留可核对字段 → 让用户复测。
3. 如果用户回头说"回正脖子偶尔音量方向反了"（§5.4 那条），
   **只带一条修复**：`TiltDetector.reset()` 不清基准线窗口（其余两条别带）。
4. 如果用户说"近距离俯视仰头不够灵"，**先量**：诊断行里 `|tilt| > 0.4×阈值` 的占比
   （v5.36 基线是 12%）。偏高就把暂停门槛改成 `|tilt| ≥ 阈值`（v5.38 已经这么做过，
   只删自己的东西、不动阈值表）。

---

## 9. 附录：常用核对命令

```powershell
$adb='D:\ruanjian\deepseek harness\.android-build\android-sdk\platform-tools\adb.exe'
$log='D:\ruanjian\deepseek harness\apk\v536-anchor-verify.log'

# 版本 / 服务状态
& $adb shell "dumpsys package com.example.gazescroll | grep versionName"
& $adb logcat -d -s GazeSelfCheck:* -v time | Select-Object -Last 1

# 触发与注入真值
& $adb logcat -d -v time | Select-String "triggered|GazeA11y.*swipe"

# 歪头（当前版本的可核对行）
(Get-Content $log -Encoding UTF8) | Select-String 'I/Tilt'

# 被拒原因统计
(Get-Content $log -Encoding UTF8) | Select-String 'candidate rejected' |
  ForEach-Object { if ($_.Line -match 'reason=(\S+)') { $Matches[1] } } |
  Group-Object | Sort-Object Count -Descending

# 用户设置
& $adb shell "run-as com.example.gazescroll cat /data/data/com.example.gazescroll/shared_prefs/gaze_scroll_prefs.xml"

# 离线回放（歪头判据，15 组 25 项；末尾会打印「=== 结果：全部通过 ===」）
powershell -NoProfile -ExecutionPolicy Bypass -File 'D:\ruanjian\deepseek harness\GazeScroll\tools\tilt-replay\run.ps1'

# 装回锚点版本（含自动重连 + 重启抓取）
powershell -NoProfile -ExecutionPolicy Bypass -File 'D:\ruanjian\deepseek harness\GazeScroll\tools\install-v536.ps1'
```

**git 速查**：`git log --oneline -8`、`git tag`；回退到锚点：
`git checkout v5.36 -- app/src/main/java/com/example/gazescroll app/src/main/res app/src/main/AndroidManifest.xml app/build.gradle.kts tools`
（然后重新构建 + `adb install -r -d`）。

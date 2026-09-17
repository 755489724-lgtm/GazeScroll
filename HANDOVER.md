# GazeScroll 交班说明（当前版本：**v5.50**）

> ### 📌 v5.47 ~ v5.50 补充说明（2026-09-17 晚，**先读这一段**）
>
> 本文件正文描述的是 **v5.46** 的状态（检测逻辑与判据部分**仍然完全有效**，一个字没变）。
> 但**界面已经重排**，而且版本号已经从 5.46 走到 **5.50**：
>
> | 版本 | 内容 |
> | --- | --- |
> | v5.47 | **界面重排（纯 UI）**：一页到底的设置清单 → **首页功能卡片 + 右侧设置页**。10 张卡片一级只留「标题 + 一句话 + 开关」，长解释与档位收进**三角**（二级详情），每张卡展开后显示**自己那条通道的实时读数**；设置页**往左滑呼出**（`DrawerLayout` + 自定义 `SwipeToOpenLayout`），依次是权限与状态 / 外观 / 首页卡片 / 测试与诊断 / 关于；主题三档 **白色（默认）/ 黑夜 / 跟随系统**；打开 App **停在首页**（不再自动退后台） |
> | v5.48 | 首页卡片**长按拖动排序**（顺序存 `ui_prefs.xml`；设置页有「恢复默认顺序」） |
> | v5.49 | 中间构建：修「ScrollView 抢走纵向手势」。**未交付**，APK 已删 |
> | v5.50 | **修好拖动排序**（第二处缺陷：拖动中 `removeView+addView` 会取消自己的触摸）。**当前交付版本** |
>
> **检测逻辑一行没动**：阈值、判据、方向仲裁、服务、无障碍注入、`AppPrefs` 全部原样；
> `git diff` 只出现在 `res/`、`MainActivity.kt`（画界面部分）和新增的 `UiPrefs.kt` /
> `SwipeToOpenLayout.kt`。**用户的检测设置存在 `gaze_scroll_prefs.xml`，界面代码不读不写**；
> 主题与卡片顺序存在**独立的 `ui_prefs.xml`** —— §7.1 那次「手改 prefs 清空设置」的事故
> 在结构上不可能再发生。
>
> **两条只有装机才会暴露的坑（写进 §7 的血泪里）**：
> 1. **`ScrollView` 会抢纵向手势**：子 View 只收到 `ACTION_CANCEL` → 拖动期间必须
>    `requestDisallowInterceptTouchEvent(true)`，否则「长按抬起来了、一拖变成滚页面」。
> 2. **`removeView()` 会当场取消被移除视图的触摸**：拖动中实时换位的话，`ACTION_CANCEL`
>    会先跑进收尾函数（那时"已改序"标记还是 false）→ **顺序永远存不下来**，剩下的事件还落回
>    ScrollView 又滚一下。修法是**拖动期间一个视图都不重排**，松手后才换位并落盘。
>    教训：**涉及手势的改动，离线/截图全绿也可能在真机上一败涂地 —— 必须真手指或真手势验证。**
>
> **本轮的验证手段（UI 版没有日志可查，证据是截图）**：新增 `tools/ui-shot.ps1`
> （装/启/截图一条龙），截图存在 `backup\GazeScroll-v5.47|v5.48|v5.50\data\`。
> **这台小米的 adb 模拟点击默认是被禁的**（`input tap/swipe/keyevent` 全报 `INJECT_EVENTS`
> 权限不足）→ 需要用户在**开发者选项 →「USB 调试（安全设置）」**里打开；本轮已由用户打开，
> 但**重新配对/重启后可能要再确认一次**。
> 另外：**拆分多次 `input motionevent` 拼不出一次连续手势**（MOVE 会被当成新事件流），
> 要复现"长按后拖动"只能用**慢速 `input swipe`**（例：`input swipe 400 407 400 700 10000`，
> 前 800ms 只走 22px 不越过 touchSlop，长按成立后才越过下一张卡的中线）。
>
> 数据与回退点：`backup\GazeScroll-v5.47|v5.48|v5.50\`（各自带 APK 与 ROLLBACK.txt）、
> `apk\gazescroll-5.47|5.48|5.50-debug.apk`、tag `v5.47`(9f89f65) / `v5.48`(1c8382a) /
> `v5.50`(498e6b1)。

---

> 写给下一个接手的人（新的 DSH 会话）。这份文档**自包含**：不需要读历史对话。
> 更新于 2026-09-17 晚（上一轮会话生成）· 上一版交班文档对应 v5.36，本文件覆盖到 v5.46。
>
> **配套文档**（按需读，不用全读）：
> - `CHANGELOG.md` 的 `[5.46]`~`[5.39]` 七节 = **本轮完整工作日志**（每版都带实测证据与决策依据）
> - `backup\GazeScroll-v5.46\ROLLBACK.txt` = 本版回退点；`backup\GazeScroll-v5.36\ROLLBACK.txt` = 锚点回退点
> - `backup\GazeScroll-v5.46\data\` = 本轮全部原始数据（9 场验证日志、5 份采集 CSV、A/B 对照、prefs 现场）
> - `HANDOVER-v5.36-era.md`（仓库根目录，同一份也在 `backup\GazeScroll-v5.46\`）
>   = **v5.36 时代的完整交班文档**：里面是 v5.30~v5.38 的全部细节（单眼闭眼四轮复盘、
>   v5.37 事故、恢复机制清单、当时的功能与判据全表）。本文件是它的**续篇 + 覆盖到 v5.46 的更新**，
>   要查 v5.36 之前的历史细节就去那份。

---

## 📋 新会话开场白（把这段粘给下一个会话）

```text
接手一个已经在做的 Android 项目：免手刷抖音的 GazeScroll。
工作区：D:\ruanjian\deepseek harness\GazeScroll
请先完整读 GazeScroll\HANDOVER.md（自包含交班文档：**最上面一段是 v5.47~v5.50 的界面改造补充，
先读它**；然后是硬约束、v5.46 状态、环境与命令、功能与判据全表、v5.39~v5.46 的经过与证据、
坑与教训、下一步建议）。
读完先给我一句话总结，然后等我派活。本轮不要改任何代码。

要点提醒：
- 当前版本 v5.50（界面重排 + 卡片自由排序），手机上已装；用户认可的锚点仍是 v5.36（git tag v5.36-anchor）。
- 不联网、不推 GitHub；自己用 adb 抓日志，不要找我要日志。
- 一次只改一件小事，改前备份 + git tag，改完装机实测，并在日志里留可核对的新字段。
- 除了我点名的功能，其他一律不动。
- 用户的设置项**不要代改**（我手改 SharedPreferences 曾把他的设置清空过一次，见 §7.1）。
```

---

## 0. 一句话

小米 13 前置摄像头做免手翻页（刷抖音）：眨眼=下一个、点头/仰头=上下翻、左右扭头=左右滑、
张嘴=点击屏幕中央、歪头=音量加减；**v5.43 起新增「注视门」**（眼睛得盯着屏幕才允许触发，
用户点名要的功能，默认「观察」模式，可拨「拦截」）；v5.39~v5.42 加了「注视数据采集」测试功能
（盖住摄像头控制起止、逐帧写 CSV），用来把注视门的阈值**从真实数据里定出来**而不是猜。

---

## 1. 硬约束（用户的要求，违反会被退回）

1. **不联网、不推 GitHub**，全程本地开发 + 本地 git 提交。
2. **不要要求用户提供日志**：自己用 adb 抓（无线调试，见 §3）。用户明确说过。
3. **不要问用户"你当时在做什么"**：他描述现象（"38分40秒左右我眼睛没盯着屏幕却触发了仰头"），
   剩下的靠日志还原。
4. **一步一小改**：一版只改一件事（最多一组相关的事），改完实测、留证据。
5. **改前备份、留回退点**：`backup\GazeScroll-vX.Y` + `git tag vX.Y`，并写 `ROLLBACK.txt`。
6. **版本号递增**，同步 `README.md` / `CHANGELOG.md`（用户会检查）。
7. **不要动（除非用户点名）**：阈值表本身、方向仲裁（v5.8 那套）、远距离行为、
   30cm 静止硬锁定（`HARD_LOCK_NEAR_RATIO=0.55` / `HARD_LOCK_STILL_MS=1200`）、
   眨眼档位、**用户的设置项**。
8. 用户说"其余的不要动" = 只改他点名的那一处，其他行为一个字节都别变。
9. **验收方式**：他拿手机实测 → 口头反馈现象 → 我读日志核对。所以每次交付都要
   **在日志里留下可核对的新字段**（这套做法被证明最有效，请延续）。
10. 用户觉得变差了就**如实承认并回退**，不要辩解。他要"保留某一版继续优化"时，就
    以那一版为基础继续改（不要动别的东西）。

---

## 2. 当前状态（事实清单，全部核对过）

> ⚠️ **本节描述的是 v5.46 那一刻的状态**（检测/判据这条线，本文档 §3~§9 都以它为准）。
> **界面与版本号后来被另一个会话推进到了 v5.50**（v5.47 界面重排 / v5.48+v5.50 卡片拖动排序，
> 纯 UI、未动检测逻辑）—— 见**本文件最上面那段「📌 v5.47 ~ v5.50 补充说明」**，
> 以及 `CHANGELOG.md` 的 `[5.50]`/`[5.48]`/`[5.47]` 三节。**版本号以那段为准。**

| 项目 | 值 |
| --- | --- |
| 手机上安装的版本 | **v5.50**（`versionCode=100`；v5.46 是 `versionCode=96`，两者检测行为一致） |
| 工作区代码 | **v5.50**（检测这条线仍是 v5.46 的内容）；HEAD 见 `git log --oneline -1` |
| git 标签 | `v5.36-anchor`（**用户认可的锚点**）、`v5.36`~`v5.50` 各版、`v5.29-stable` / `v5.8-stable` / `v5.3-stable` |
| 备份 | `backup\GazeScroll-v5.36`（锚点，含源码+APK+ROLLBACK）、`GazeScroll-v5.39`~`-v5.46`、`-v5.47` / `-v5.48` / `-v5.50`（UI 那几版） |
| APK 归档 | `apk\gazescroll-5.50-debug.apk`（最新）；5.3~5.50 全部在 `apk\` |
| 本轮验证日志 | `apk\v539~v546-verify.log`（每版一场；`v546-verify.log` 是最长的一场真实使用数据，2.4 小时） |
| 一键装回锚点 | `GazeScroll\tools\install-v536.ps1` |
| 各版一键装回 | `tools\install-v539.ps1` / `-v540.ps1`（UI 那几版见各自 `ROLLBACK.txt`） |

**用户当前的设置**（以设置页为准，日志里观测值）：灵敏度 6.0°、`blinkTriggerCount=2`、
`globalCooldownMs=2000`、静止锁定开/1.5、`listSwipeDistance=0.26`、`mouthSensitivity=MEDIUM`、
歪头控音量开（左=加/右=减）、`tiltThresholdDeg=13.0`、`tiltHoldMs=300`、档位 1；
**注视门 = ENFORCE（拦截）**；**注视数据采集 = 关**。
（它们都在 `shared_prefs/gaze_scroll_prefs.xml` 里，**不要手改**，见 §7.1。）

---

## 3. 环境、路径与常用命令（照抄即可）

```powershell
$proj  = 'D:\ruanjian\deepseek harness\GazeScroll'                       # 工程源码
$apkD  = 'D:\ruanjian\deepseek harness\apk'                              # APK 与验证日志
$adb   = 'D:\ruanjian\deepseek harness\.android-build\android-sdk\platform-tools\adb.exe'
$jdk   = 'D:\ruanjian\deepseek harness\.android-build\jdk\jdk-17.0.20.1+1'
$sdk   = 'D:\ruanjian\deepseek harness\.android-build\android-sdk'

# 构建（必须 --offline：AGP 8.6.1 与 kotlinc 都已缓存在 C:\Users\WIT_User\.gradle）
$env:JAVA_HOME=$jdk; $env:ANDROID_HOME=$sdk
cd 'D:\ruanjian\deepseek harness'
.\GazeScroll\gradlew.bat -p GazeScroll --console=plain --offline assembleDebug

# 发布 + 安装 + 启动
Copy-Item "$proj\app\build\outputs\apk\debug\app-debug.apk" "$apkD\gazescroll-5.46-debug.apk" -Force
& $adb install -r -d "$apkD\gazescroll-5.46-debug.apk"     # -d = 允许降级
& $adb shell "dumpsys package com.example.gazescroll | grep versionName"
& $adb shell am start -n com.example.gazescroll/.MainActivity --ez com.example.gazescroll.OPEN_SETTINGS true

# 抓日志（必须用 powershell，不是 pwsh；最好用后台 job，别用 Start-Process，见 §7.8）
powershell -NoProfile -ExecutionPolicy Bypass -File "$proj\tools\capture-loop.ps1" `
    -Out "$apkD\v547-verify.log" -Minutes 180
```

设备：小米 13（fuxi/2211133C，HyperOS 3.0.308.0，Android 14 / SDK 34）。
无线调试序列号形如 `192.168.3.32:<port>`，**端口每次息屏都会变**：
`& $adb mdns services` 找 `_adb-tls-connect` → `& $adb connect <ip:port>`。
**若 mDNS 什么都搜不到、但手机还能 ping 通（`Test-Connection 192.168.3.32`），
说明手机上无线调试被关了 → 让用户去「开发者选项 → 无线调试」打开，别干等**（本轮遇到 4 次）。

### 离线工具（本轮的成果，都值得复用）

| 工具 | 干什么 | 怎么跑 |
| --- | --- | --- |
| `tools/tilt-replay/` | 编译**真实** `TiltDetector.kt` 回放歪头判据（25+ 项） | `powershell -File .\run.ps1` |
| `tools/probe-replay/` | 编译**真实** `GazeProbeRecorder.kt`+`GazeGate.kt` 回放采集状态机与注视门（**96 项**） | 同上 |
| `tools/headpose-replay/` | 编译**真实** `HeadPoseDetector.kt` 回放"突然性窗口"（**10 项**）；`ab-old-300ms.txt` 是 300/450ms 的 A/B 对照 | 同上 |
| `tools/probe-analyze/analyze.ps1` | 采集 CSV 的**逐段统计**（-Feature eY 看单个量） | `-Csv <file>` |
| `tools/probe-analyze/timeline.ps1` | 采集 CSV 的**每 2 秒走势**（看一段里有没有分段/漂移） | `-Csv <file>` |
| `tools/probe-analyze/percentiles.ps1` | 逐段 **p5/中位/p95**（定阈值用） | `-Csv a.csv,b.csv -Features eY,eX,openL` |

**回放/分析脚本的三个坑**：① `.ps1` 必须纯 ASCII（Windows PowerShell 5.1 按 ANSI 读无 BOM 的 .ps1）；
② CSV 里**没有** `boxCy`/`eyeNoseDx` 这些派生列，要用 `analyze.ps1`（它现算）；③ 回放的**动作形状
必须照抄实测**（慢起手 + 一帧快越阈值），否则你验证的是另一道门（本轮就踩过，见 §7.5）。

---

## 4. 现在有什么（功能与判据全表）

### 4.1 触发通道

| 通道 | 动作 | 结果 | 代码 |
| --- | --- | --- | --- |
| 眨眼 | 连眨 N 次（用户设 2） | 上滑（下一个） | `BlinkDetector.kt` |
| 点头 / 仰头 | 俯仰越阈值 | 下滑 / 上滑 | `HeadPoseDetector.kt` |
| 左/右扭头 | 偏航越阈值 | 左滑 / 右滑 | 同上 |
| 张嘴 | 一次 | 点击屏幕中央 | `MouthOpenDetector.kt` |
| 歪头 | 相对基准线歪到阈值并保持 | 音量 ±N 档 | `TiltDetector.kt` |

### 4.2 注视门（v5.43，用户当前拨在**拦截**）

**四条判据**（任一不成立 = "没在看着屏幕"；读数缺失一律放行 fail-open）：

| # | 判据 | 阈值 | 挡什么 |
| --- | --- | --- | --- |
| ① | 脸在画面里 | — | 手机放桌上 / 人走开 |
| ② | 脸够大 | `faceRatio ≥ 0.20` | 离得太远 |
| ③ | 偏航相对**本人**基准线 | `≤ ±10°` | 头转开（实测转开是 ±11~13°） |
| ④ | 滚转相对基准线 | `≤ ±20°` | 躺下、侧脸 |
| ⑤ | **睁眼占比**（最近 1.5 秒） | `≥ 30%` | 眼睛离开屏幕（**只在 `faceRatio < 0.45` 时生效**） |

- **每通道豁免**：左右扭头豁免③、歪头调音量豁免④（动作本身就是那条轴）。
- ⑤用"占比"不用瞬时值：眨眼要经过闭眼那一帧，瞬时判定会把眨眼通道自己拦死。
- 实测账单（v5.46 那 1.5 小时）：`blocked=2 / would-block=6`，原因 `head-turned ×7, eyes-away ×1`
  → **约 11 分钟才判错一次**。
- **实测上限（重要）**：ML Kit 人脸检测**没有虹膜**，"头不动只把眼睛往旁边瞟"读不出来；
  "抬头看别处"和"抬头翻页"在全部信号上完全重合（见 §5 的 v5.46 一节的原始读数）。
  ⑤只是"眼皮遮住眼球"那一类的代理（50cm 下盯着屏幕中位数 **1.00**、眼睛往下看别处 **0.01**；
  30cm 下两边是 0.21 vs 0.03，**不可用**，所以近距离档跳过⑤）。

### 4.3 注视数据采集（v5.39，测试功能，用户现在**关着**）

盖住前置摄像头控制起止：**盖 ≥3 秒**→就绪，露脸即开录；录制中**盖 1~6 秒**→分段；
**盖 ≥6 秒**→结束。另有两条兜底：**帧流中断 ≥1.5 秒**、**录制中眨出一次翻页**也记分段。
"盖住"的判据（v5.41 定稿）：`没脸 且 (近距离传感器 NEAR 且 环境光<15 或 纹理<5 或 亮度<32)`。
CSV 落在 `files/probe/`，用 `adb shell "run-as com.example.gazescroll cat <path>"` 取。

### 4.4 本轮改过、且**现在生效**的关键数值

| 项 | 值 | 出处 |
| --- | --- | --- |
| 近距离档俯仰"突然性窗口" | **450ms**（v5.44 由 300 放宽） | `HeadPoseDetector.NEAR_SUDDEN_RISE_MS` |
| 扭头窗口 | 400ms（没动） | `NEAR_SUDDEN_TURN_RISE_MS` |
| 远距离窗口 | 用户设的 500ms（没动） | `motionWindowMs` |
| 遮挡判定门槛 | 人脸丢失 **≥300ms** 才算遮挡 | `FACELOST_OCCLUSION_MIN_MS` |
| 遮挡时是否清基准线 | 只有 **≥1 秒**才清 | `OCCLUSION_RECALIBRATE_MIN_MS` |
| 近档点头阈值 | **2.5°**（没动；A 方案经数据验算被否，见 §5） | `NEAR_LOOKDOWN_NOD_BOOST=0.42` |

---

## 5. 本轮做了什么（v5.39 → v5.46，每版一句话 + 关键数字）

> 完整版在 `CHANGELOG.md` 的对应小节（都带实机证据）。这里只给"要记住的结论"。

- **v5.39「注视数据采集」**：新增采集器（纯逻辑、可离线回放）+ 每帧原始几何量 + 画面亮度，
  写入 `files/probe/*.csv`；默认关，不开时逐帧开销与 v5.36 一致。
- **v5.40**：第一次采集**整场失败**（4 分钟全是 `probe=idle`）→ 根因：**前置摄像头自动曝光会把
  被手掌盖住的画面提亮**，所以"盖住=画面黑"不成立 → 改成三条独立信号并联（近距离传感器 / 纹理 / 亮度）。
- **v5.41**：① 近距离传感器会**闩锁**在 NEAR（盖过一次后两分半不回 far）→ 加环境光旁证（`NEAR 且 lux<15`）；
  ② 用户按"盖 1 秒"分段实测是 **2.1~2.3 秒**，3 秒的结束界线容错只有 800ms → 11 段只录到 5 段 →
  **结束界线 3000 → 6000ms**。（教训：**给人留的容错必须大于人对秒的感觉误差**。）
- **v5.42**：第三轮只录到 2 段 → 取证发现用户盖摄像头时**手掌压到屏幕最上方把通知栏拉下来了**
  （MIUI 日志 `StatusBar1 ACTION_DOWN/UP`，按住 9 秒）→ 抖音失去前台、相机被释放、**那 9 秒一帧都没有**
  （"盖住"判据没有输入）→ 加两条兜底：**帧流中断 ≥1.5s** 与 **录制中眨一次眼** 也记分段。
- **v5.43「注视门」**：判据与阈值全部来自四轮实机采集（分位数）。关键发现：头不动时偏航/滚转
  **完全看不出**（`eY` 中位数 −0.1 vs −2.2，分布重叠），但**睁眼概率从 50cm 的 1.00 掉到 0.01**
  （眼皮遮住眼球）→ ⑤；30cm 下不可用（0.21 vs 0.03）→ 近距离跳过。默认「观察」。
- **v5.44**：用户报"仰头失灵"→ 日志抓到 `ignored slow lean: rise 429ms > 300ms`
  （近距离档窗口 300ms 太紧，一次真实仰头被整段作废；同场这种共 6 次：336/429/429/563/684/839ms）
  → **放宽到 450ms**；新增 `actWin=` 字段；新回放 `tools/headpose-replay` + A/B 对照
  （旧常量下 429ms **0 次触发**，新常量下能触发；563ms 以上两边都仍被挡）。
  **同时否掉了原定的 A 方案**（近档点头阈值 2.5°→3.2°）：那 5 次误触幅度全在 **4.9~8°** 且都是
  真实头部动作，A 只能挡住 6 次里的 1 次；而阈值得抬到 5° 以上才挡得住，那会把他**有意的轻点头
  （3.0~4.4°）**一起废掉 —— **两者完全重叠，任何阈值都分不开（这条路不要再试）**。
- **v5.45**：用户报"仰头好多次都没用"（19:29 那 30 秒）→ 那是一场**遮挡风暴**：
  30 次 `occlusion detected`，每次都是 **92~214ms 的人脸漏检**（大角度仰头时 ML Kit 会漏 1~2 帧），
  却换来 **500~1000ms 头部通道熄火 + `recalibrate()`**（再停 ~1.1s）→ 30 秒里零候选零触发；
  而画面明明没被挡（`luma=80.6 tex=11.6 prox=far`）。→ **人脸丢失要满 300ms 才算遮挡**。
  用户当时问"能不能照抄 5.36"—— **不能，这毛病在 v5.36 里一模一样**
  （`v536-anchor-verify.log` 21 次遮挡、20 次 ≤300ms、抑制总时长 14.6 秒），
  三场日志的丢失时长**双峰无重叠**（抖动 60~218ms vs 真遮挡 335~606ms），300ms 正落空档。
- **v5.46**：用户又报"50cm 眼睛没看屏幕却触发仰头、30cm 还有一次误触"→ 两次机制不同：
  - **30cm 那次（已修）**：`handleOcclusion` 里**无条件** `recalibrate()`（该场 11 次）→ 基准线 3 秒内
    `7.1→13.9→10.4→16.9→19.0→25.5→10.0` → `signed` 翻号到 **−17.6°** → 凑出一次「点头=上一个」。
    现在只有丢脸 ≥1 秒才清基准线（日志写 `headBaseline=kept(NNNms)|reset`）。
  - **50cm 那次（修不了，如实记录）**：`ctx` 显示 signed pitch 390ms 内走 **15°**、原始俯仰到 28°、
    偏航不动、`eyeDuty=1.00`、`gyro=0.00 accel=0.0 phoneMotion=false`（手机没动）
    —— **就是一次真实的抬头**，与"抬头翻页"完全一致，任何阈值都分不开；只有注视门能碰一点
    （同场 19:38:33 那次它标了 `head-turned yaw=+10.4°`）。

**v5.46 那场（19:53~22:17，共 2.4 小时真实使用）的最终验收数据**：
- **抖动**：`flicker ignored=165` —— 165 次 1~2 帧的人脸漏检被正确忽略，不再变成遮挡
- **遮挡**：`occlusion detected=20`（v5.44 那场是 47 次），且 **20 次全部 `headBaseline=kept`**，
  只有 1 次 `reset`（丢脸 ≥1 秒）→ **基准线不再被无脑清空**
- **注视门（拦截）**：`blocked=12 / would-block=6` = 2.4 小时共 18 次判错（**约 8 分钟一次**），
  原因 `head-turned ×16 / head-tilted ×1 / eyes-away ×1` —— 正是它该拦的那三类
- **触发 / 注入**：仰头 63 / 点头 22 / 扭头 23；注入 UP 64 / DOWN 13 / 左右 23 / 点击 4
- 22:18 之后手机息屏、无线调试断开，抓取循环空转到 22:54 正常收尾（`capture window finished`）
- ⚠️ 用户**还没有口头确认**这一版的体感（他只说了"保留这一版的数据"）—— 下一轮先问体感，
  再决定是否继续动 §8.2 里的线索

---

## 6. 数据与证据清单（"保留这一版的数据"）

| 文件 | 里面是什么 |
| --- | --- |
| `apk\v546-verify.log` | **v5.46 真实使用 1.5 小时**（本轮最重要的一场） |
| `apk\v545-verify.log` | v5.45 那场（用户报"仰头好多次都没用"的证据：30 次抖动 → 遮挡风暴） |
| `apk\v544-verify.log` | v5.44 那场（47 次遮挡，43 次 ≤218ms；双峰分布的原始数据） |
| `apk\v543-verify.log` | v5.43 那场（"前两分钟仰头失灵+误触"、注视门第一份账单） |
| `apk\v539/v540/v541/v542-verify.log` | 采集功能四轮踩坑的完整过程 |
| `apk\v536-anchor-verify.log` | **锚点 v5.36 的实测场**（"这毛病 v5.36 也有"的对照证据） |
| `apk\probe-*.csv`（5 个） | 注视数据：184414=校准场（50cm 盯屏幕 14s）、184722+184832=第一轮 5 段、185631=第三轮 2 段、**190655=第四轮 4 段（注视门阈值的直接来源）** |
| `tools/headpose-replay/ab-old-300ms.txt` | 300ms vs 450ms 的 A/B 对照（只换一个常量、同一套回放） |
| `backup\GazeScroll-v5.46\prefs-backup-before-enforce.xml` | 用户设置的备份（§7.1 那次事故靠它恢复） |
| `apk\v546-prefs-before-enforce.xml` / `-after-enforce.xml` | 那次 prefs 事故的现场（重复键） |

（本轮全套日志与 CSV 已复制到 `backup\GazeScroll-v5.46\data\`。）

---

## 7. 坑与教训 ★（用户点名要保留）

### 7.1 手改 SharedPreferences 会把用户设置清空（本轮最严重的事故）

我为了把注视门拨到「拦截」，手改了 `shared_prefs/gaze_scroll_prefs.xml`。那份文件里**本来就有**
`gazeGateMode` 这一键（`saveConfig` 从 v5.43 起每次都写），我却**"追加"**了一行 →
**同名键出现两次** →

```xml
<string  name="gazeGateMode">OBSERVE</string>      <!-- App 写的 -->
<boolean name="gazeGateMode" value="ENFORCE" />    <!-- 我追加的 -->
```

→ **XML 解析失败 → SharedPreferences 认为是空配置 → 回落到出厂默认 →
`setSetupComplete` 把空 map 写回磁盘 → 用户设置全丢**。
（我的核对只查了"旧键没丢 + 新键在"，**没查重复**。）

**规矩**：① **设置项一律让用户在界面上拨，不要代劳**；
② 万一必须手改：**替换同名键而不是追加**、写完**查重复**、改前 `cp` 一份到 App 私有目录
（`run-as <pkg> cp <prefs> files/prefs-backup.xml`）；
③ **不要以为 `am force-stop` 能长时间停住 App**——它带无障碍服务绑定，系统随时会把它拉起来，
读-改-写会互相踩。恢复办法：`am force-stop` 后立刻 `run-as <pkg> cp files/prefs-backup.xml <prefs>`。

### 7.2 PowerShell 不要用来改源码/文档（本轮又踩一次）

`(Get-Content ... -Raw) -replace ... | Set-Content -Encoding utf8` 会：① 按 ANSI 读无 BOM 的 UTF-8
→ 中文/破折号变乱码（本轮把 `build.gradle.kts` 里的 `—` 搞坏了）；② 加上 BOM；③ 把 LF 变 CRLF。
**一律用 `edit` / `write` 工具**。非要用 PowerShell，就用
`[System.IO.File]::ReadAllText/WriteAllText(..., UTF8Encoding($false))` 并随后核对 BOM/NUL/CR/LF。
改完 `git diff --stat` 看一眼行数对不对（异常大就是行尾被整体重写了）。

### 7.3 用 `edit` 工具写 XML 字符串里的 `\n`

Android 字符串里的换行是字面 `\n`（反斜杠+n）。在工具参数里写 `\n` 会被当成真换行。
要写**两个字符**：JSON 里写 `\\n`。本轮第一次改 `strings.xml` 就因为这个匹配失败。

### 7.4 日志/正则的坑

- Android 给短 tag 补空格：正则要写 `I/Blink\s*\(`、`I/Tilt\s*\(`。
- 诊断行里缺失值是 `-`，`[double]"-"` 会抛异常 —— 解析前先判。
- **加了新 log tag，第一件事是把它加进 `tools/capture-loop.ps1` 的 `$tags`**
  （v5.35/v5.36 漏过 `Tilt:V`，那两场日志没有触发明细；本轮补了 `GazeProbe:V`、`GazeGate:V`）。
- 抓取用 `powershell`，**不要用 `pwsh`**（这台机器 PATH 里没有）。

### 7.5 离线回放：动作形状必须照抄实测

`tools/headpose-replay` 第一版我把"仰头"回放成"全程匀速慢爬"，结果所有用例都不触发 ——
**验证的其实是我自己写错的那道门**。实测形状是"慢起手（onset→阈值 429ms）+ **一帧快越阈值**
（speed 0.057°/ms）+ 保持"。改成这个形状后才复现出 v5.43 的失败、验证出 v5.44 的修复。
**回放前先从日志里抄一条真实的 ctx 形状。**

### 7.6 A/B 对照是证明"这处改动真的有用"的最短路径

把要验的常量复制一份改回旧值（只改 ASCII 常量、显式 UTF-8 读写），用**同一套回放**跑两遍。
本轮靠它证明了"旧常量下 429ms 是 0 次触发、新常量下能触发，而 563ms 以上两边都仍被挡"。

### 7.7 提方案之前先算它能不能解决用户报的那个现象

本轮我按 HANDOVER 的建议准备做 A（近档点头阈值 2.5°→3.2°），动手前用数据一算：用户抱怨的
5 次误触幅度全在 **4.9~8°**，A 只能挡住 6 次里的 1 次 —— **方案与症状对不上**，于是改成 B。
**"用户说要 A" 也要先用数据验算 A 是否真的解决他报的问题，然后把结论如实告诉他再动手。**

### 7.8 后台抓日志用 job，不要 `Start-Process`

`Start-Process -WindowStyle Hidden` 起的进程会随本条命令结束被杀掉（本轮白等过）；
用工具的 `run_in_background: true` 起 `capture-loop.ps1`。

### 7.9 无线调试

端口每次息屏都会变；`mDNS 搜不到 + 能 ping 通` = 手机上无线调试被关了 → 让用户去开发者选项打开。
本轮因为息屏丢了 4 次连接，每次都浪费一轮。

### 7.10 判据设计（历次血泪，仍然有效）

1. **绝对阈值不如相对量**：两只眼的差、相对本人基线，比"某只眼 <0.7"稳。
2. **不要把软的、有惯性的分类输出当物理量**（ML Kit 的 `eyeOpenProbability` 的下降快慢 ≠ 眼皮快慢）。
3. **冻结/清空一个参考值（基准线）前必须回答**：① 它会不会被动作本身带走？② reset/丢脸/换应用时该不该清？
   —— v5.36/v5.37 和本轮 v5.46 的三次事故全在这里。
4. **任何"暂停/冻结另一条通道"的改动都要量化占空比**。
5. **过严的门会自己打死自己**（v5.11 / v5.31 / v5.33 / v5.37 / 本轮的 300ms 窗口与 300ms 遮挡下限）。
   加严前先问："它会不会挡住真实动作？"
6. **给人留的容错要大于人的感觉误差**（"盖 1 秒"实测 2.1~2.3 秒）。
7. **`travel=-`**（位移判据在判定域切换时被跳过）**至今没修**：本场 29 次触发**全部** `travel=-`。
   是个潜在加固点，但**不是**上面那些误触的原因（那些动作的真实位移都够）。

---

## 8. 已知问题与下一步建议

### 8.1 待确认（v5.46 装好后还没拿到用户反馈）

1. **30cm 那次"点头误触"是否消失**（v5.46 不再让遮挡清基准线）——看日志里
   `occlusion detected ... headBaseline=kept(NNNms)` 的比例（应几乎全是 kept）。
2. **注视门拨到「拦截」后的体感**（已在跑：1.5 小时只拦 2 次、判错 8 次，
   原因 `head-turned ×7 / eyes-away ×1`）——若误伤真实动作，先放宽 ③ 的 10° 或退回「观察」。

### 8.2 本轮发现、还没动手的线索

1. **`baseline cleared` 在无脸期间每帧一次**：v5.46 那场共 231 次，其中 **185 次集中在
   19:53~19:56（约 1 次/秒，正是 1fps 待机时）**，之后零星。调用点没有日志，
   候选是 `onFrame` 里 `if (tiltGateWasActive && !tilting) recalibrate()`（若 `tilting` 逐帧翻转）
   或 `resetDetectorStateForFreshStart()`；`checkFrames()` 那条**没触发**（`no frame for` 0 行）。
   影响：无脸期间基准线永远攒不满 → 回到手机后头部通道要等 ~1.1 秒（`baselineSettling`）
   才能工作，严重时头部通道长时间不可用。**建议先加一行带调用来源的日志**再决定怎么修。
2. **"抬头看别处"误触（v5.46 §5 里那个 50cm 的例子）**：物理上与"抬头翻页"重合，
   当前无法用阈值区分。可选方向（都有代价，**动之前先问用户**）：
   ① 要求抬头动作必须**回到基线**才算（延迟换准确率）；② 用注视门的③④兜住"头转开/躺下"的；
   ③ 接受现状（用户当前 `distMode=far` 时的阈值是 6.0°）。
3. **`travel=-`**（见 §7.10 第 7 条）：可以顺手修的加固项，但要先离线确认它不会挡真实动作。

### 8.3 早就记录、仍然有效的候选旋钮（都在 HANDOVER-v5.36 的老文档里，未动）

- `NEAR_DOWN_NOD_BOOST` 0.42 → 0.53（2.5°→3.2°）：**注意这条已被本轮数据否掉**（见 §5 的 v5.44）。
- 近距离"轻通道"加"原始位移下限"；修 `travel=-`。
- §7.1（老文档）"从待机打开抖音有时不触发"：本轮又遇到一次（v5.42 那轮，
  `foreground change` 里根本没有抖音的事件）——**HyperOS 过滤第三方无障碍窗口事件**是根因，
  变通办法：把 App 自己的设置页放在前台（相机被 `forceActive` 强制打开），或让用户拉一下通知栏。

---

## 9. 附录：常用核对命令

```powershell
$adb='D:\ruanjian\deepseek harness\.android-build\android-sdk\platform-tools\adb.exe'
$log='D:\ruanjian\deepseek harness\apk\v546-verify.log'

# 版本 / 服务状态
& $adb shell "dumpsys package com.example.gazescroll | grep versionName"
& $adb logcat -d -s GazeSelfCheck:* -v time | Select-Object -Last 1

# 触发与注入真值
& $adb logcat -d -v time | Select-String "triggered|GazeA11y.*swipe"

# 注视门（拦了什么、为什么）
& $adb logcat -d -s GazeGate:V -v time
(Get-Content $log -Encoding UTF8) | Select-String 'gate blocked|would-block'

# 遮挡与抖动（v5.45/v5.46 的效果）
(Get-Content $log -Encoding UTF8) | Select-String 'occlusion detected|flicker ignored|headBaseline='

# 基准线漂移（排查"回正/回到手机"类误触的第一现场）
(Get-Content $log -Encoding UTF8) | Select-String 'I/GazeDiag' |
  ForEach-Object { if ($_.Line -match '^\S+ (\S+).*? pitch=([-\d\.]+) base=([-\d\.]+).*?(pitchTh=[\d\.]+° pitchThUp=[\d\.]+°).*?(distMode=\w+)') {
      "$($Matches[1])  raw=$($Matches[2]) base=$($Matches[3]) $($Matches[4]) $($Matches[5])" } } | Select-Object -Last 40

# 用户设置（只读！不要改，见 §7.1）
& $adb shell "run-as com.example.gazescroll cat /data/data/com.example.gazescroll/shared_prefs/gaze_scroll_prefs.xml"

# 离线回放（三个）
powershell -NoProfile -ExecutionPolicy Bypass -File 'D:\ruanjian\deepseek harness\GazeScroll\tools\probe-replay\run.ps1'
powershell -NoProfile -ExecutionPolicy Bypass -File 'D:\ruanjian\deepseek harness\GazeScroll\tools\headpose-replay\run.ps1'

# 采集数据分析
powershell -NoProfile -ExecutionPolicy Bypass -File 'D:\ruanjian\deepseek harness\GazeScroll\tools\probe-analyze\analyze.ps1' -Csv 'D:\ruanjian\deepseek harness\apk\probe-20260917-190655.csv'
powershell -NoProfile -ExecutionPolicy Bypass -File 'D:\ruanjian\deepseek harness\GazeScroll\tools\probe-analyze\timeline.ps1' -Csv 'D:\ruanjian\deepseek harness\apk\probe-20260917-185631.csv'

# 装回锚点 / 各版
powershell -NoProfile -ExecutionPolicy Bypass -File 'D:\ruanjian\deepseek harness\GazeScroll\tools\install-v536.ps1'
```

**git 速查**：`git log --oneline -10`、`git tag`；回退到锚点：
`git checkout v5.36 -- app/src/main/java/com/example/gazescroll app/src/main/res app/src/main/AndroidManifest.xml app/build.gradle.kts tools`
（然后 `--offline assembleDebug` + `adb install -r -d`）。

---

## 附：v5.36 锚点时代仍然有效的结论（原文档 §5 的压缩版）

- **v5.30~v5.34「单眼闭眼控音量」四轮全废，别再走这条路**：绝对阈值分不开"眨眼/半闭"与"单眼闭"；
  `eyeOpenProbability` 的下降快慢不等于眼皮物理快慢；能分开的只有**两只眼的读数差**；
  时间域 duty 只能挡"反复被读低"。（v5.35 换成歪头控音量后稳定，v5.36 是锚点。）
- **v5.37 的三条新判据（重锚风暴 / 起手前必须有中位 / 暂停门槛）全部过头**，被用户整版退回；
  唯一被验证"零灵敏度代价"的修复是 `TiltDetector.reset()` **不清基准线窗口**
  （与 v5.46 修的是同一类问题）。
- **判定顺序很敏感**（`evaluatePitch`：below-onset → yaw-swing → recenter-lock → below-threshold →
  no-travel → ref-veto → eyes-unreliable → shake → phone-motion → slow-rise → jump-confirm →
  hold-not-met/light-confirm/speed-gate → yaw-dominant-arbitration）；往里插东西前先读 CHANGELOG。
- **域混用是经典事故**：轻通道位移是"相对运动起点的位移"，其余是"相对基线量"，两者相减 = 方向整体翻转（v5.17）。

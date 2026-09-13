# 万能翻页 · GazeScroll

> 用**前置摄像头**检测眨眼和点头/仰头，自动触发上滑翻页 —— 刷视频不用手。

[![Release](https://img.shields.io/badge/release-v4.2-blue)](../../releases/tag/v4.2)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)]()
[![License](https://img.shields.io/badge/license-MIT-lightgrey)]()

---

## 这是什么

一个 Android 免手操作工具。它用前置摄像头实时检测你的**眼部与头部动作**，识别到指定动作后，通过无障碍服务模拟一次「向上滑动」，从而让抖音、B站等短视频信息流自动翻到下一个视频。

**适用场景**：吃饭、做饭、躺着、手上有东西、戴手套 —— 任何不方便碰屏幕的时候。

---

## ⚠️ 当前痛点（求社区大佬支招）

**目前必须借助一次 ADB 授权才能真正用起来。** 这是本项目最大的体验短板，也是我最希望社区帮忙解决的地方。

具体来说：

1. **自动开启无障碍服务需要 `WRITE_SECURE_SETTINGS` 权限。**
   这个权限是 `signature|privileged` 级别，普通 App 无法直接申请，只能通过 ADB 手动授予一次。
   代码里已经做了「授权一次后永久记住」的逻辑（`AccessibilityBootstrap.repairIfNeeded`），
   所以**只需要授权一次**，之后重启、升级都不用再来。

2. **用 `UsageStatsManager` 判断前台 App 也需要特殊授权。** 在 MIUI / HyperOS 上，
   `GET_USAGE_STATS` 的 appop 会被系统直接拒绝（日志里能看到 `ignore; rejectTime=…`），
   所以前台检测退化为「无障碍窗口列表 + 窗口标题」的方案。

3. **部分系统（如 HyperOS）会过滤第三方 App 的无障碍事件**，只有 launcher / systemui 的事件能收到。

### 如果你知道更好的方案，非常欢迎提 Issue 或 PR 🙏

我特别想知道：

- 有没有**免 ADB** 的方式让用户自己开启自定义无障碍服务？
- 有没有不依赖 `UsageStatsManager` 的可靠前台 App 检测方案？
- HyperOS / 澎湃 OS 上有没有绕过无障碍事件过滤的正当途径？

> 说明：本项目**不会**尝试任何 root、系统签名或跨应用越权方案。所有实现都基于公开的 Android 官方 API。

---

## 已实现功能

### 触发方式
- **眨眼翻页** —— 可配置连眨 1 / 2 / 3 次触发（默认 2 次）
  - 双眼开合度阈值 0.55（针对**戴眼镜**场景调过）
  - 单眼触发也生效；需连续 2 帧确认，避免误触
  - 两次眨眼间隔需在 200–1500ms 之间
  - 触发后 1500ms 冷却；3 秒持续闭眼保护（防困倦误触）
- **点头 / 仰头翻页** —— 头部俯仰角超过 **8°** 且持续 **200ms**
  - 动态基准线：最近 45 帧的**中位数**（避免短促动作把基准带偏）
  - 触发后 2000ms 冷却
  - 支持下拉选择灵敏度（6° / 8° / 12°）

### 功耗与运行策略
- **按需启停**：只有在你选定的目标 App 处于前台时才开摄像头，切走立刻释放
- **三级功耗模型**：
  | 状态 | 行为 |
  | --- | --- |
  | 运行 | 全速分析（约 15fps） |
  | 温机 | 保留相机、丢弃帧、ML Kit 空闲（30 秒窗口） |
  | 释放 | 完全关闭相机 |
- 息屏自动释放相机
- 前台服务通知实时显示「运行中 / 待机」

### 目标 App 管理
- 多选列表：抖音、抖音极速版、微博、小红书、B站
- 未安装的自动置灰
- 「全部不勾选 = 全局开启」逃生开关

### 其他
- **权限记住**：授权一次后不再弹窗
- 实时读数面板：人脸置信度、双眼开合度、俯仰角、当前基准线、帧率
- **手动重启按钮** + `ACTION_RESTART`（应对相机偶发卡死）
- 相机冷启动预热，进入目标 App 后约 **1.2 秒**开始响应

---

## 安装与使用

### 方式一：直接下载 APK（推荐）

1. 到 **[Releases](../../releases/latest)** 下载 `gazescroll-4.2-debug.apk`
2. 安装到手机（允许「安装未知来源应用」）
3. **按下面的 ADB 步骤授权**（不授权的话不会工作）

> APK 是 **debug 签名**，仅供测试使用。

### 方式二：自己编译

```bash
git clone https://github.com/755489724-lgtm/GazeScroll.git
cd GazeScroll
./gradlew assembleDebug
```

环境要求：JDK 17、Android SDK（compileSdk 34 / buildTools 34.0.0）、Gradle 8.9。

> 仓库里目前只有 `gradle/wrapper/gradle-wrapper.properties`，**没有 wrapper 脚本与 jar**。
> 用 Android Studio 打开会自动补齐；命令行编译请先执行一次 `gradle wrapper`。

### 🔑 必须的 ADB 授权步骤（只做一次）

手机开启「USB 调试」连上电脑后，执行：

```bash
# 1) 授予 WRITE_SECURE_SETTINGS —— 用于自动开启无障碍服务
adb shell pm grant com.example.gazescroll android.permission.WRITE_SECURE_SETTINGS

# 2) 允许后台弹出界面（部分机型需要）
adb shell appops set com.example.gazescroll SYSTEM_ALERT_WINDOW allow

# 3) 前台 App 检测（MIUI/HyperOS 上可能被系统拒绝，属于已知问题）
adb shell appops set com.example.gazescroll GET_USAGE_STATS allow

# 4) 手动把无障碍服务加进启用列表
adb shell settings put secure enabled_accessibility_services \
  com.example.gazescroll/com.example.gazescroll.GazeAccessibilityService
adb shell settings put secure accessibility_enabled 1

# 5) 启动
adb shell am start -n com.example.gazescroll/.MainActivity
```

**授权完成后就可以拔掉数据线了**，之后正常使用不需要再连电脑。

> 项目里附带了一个 `恢复翻页.bat`（Windows），会把上面这几步 + 重装 + 启动一次性做完，
> 适合手机重启后或服务掉线时一键恢复。

### 使用

1. 打开 App，勾选你要生效的 App（比如「抖音」）
2. 点「启动」
3. 切到抖音，开始刷 —— 眨眼或点头即可翻页
4. 灵敏度不合适就在 App 里调，面板上能看到实时数据

---

## 已知限制

- **必须 ADB 授权一次**（见上）
- MIUI / HyperOS 上 `GET_USAGE_STATS` 被系统拒绝，前台检测降级为窗口标题方案
- HyperOS 会过滤第三方 App 的无障碍事件，只放行 launcher / systemui 的事件
- `am force-stop` 会**清空** `enabled_accessibility_services`，服务需要重新拉起
  （用 `恢复翻页.bat` 或重新打开 App 即可）
- 部分机型需要同时开启「USB 调试(安全设置)」才能正常注入手势
- 强光 / 逆光 / 大幅晃动时检测率会下降

---

## 项目结构

```
app/src/main/java/com/example/gazescroll/
├── BlinkDetector.kt               眨眼状态机（纯逻辑，无 Android API）
├── HeadPoseDetector.kt            头部姿态状态机（动态基准线 + 冷却）
├── FaceGazeAnalyzer.kt            CameraX ImageAnalysis + ML Kit → 分析结果
├── GazeCameraService.kt           前台服务（type=camera），持有整条流水线
├── GazeAccessibilityService.kt    无障碍服务，负责 dispatchGesture 注入上滑
├── AccessibilityBootstrap.kt      自动开启/修复无障碍服务（免手动去设置里点）
├── AppStateManager.kt             前台 App 检测 + 状态机启停
├── SwipeInjector.kt               手势注入抽象层
├── ShizukuSwipeDispatcher.kt      Shizuku 注入备选路径（未启用）
├── TargetApps.kt                  目标 App 列表与安装检测
├── AppPrefs.kt / GazeModel.kt     配置持久化与数据模型
└── MainActivity.kt                极简配置界面

app/src/main/res/xml/accessibility_service_config.xml
```

技术栈：**Kotlin** · CameraX 1.3.4 · ML Kit face-detection 16.1.7（bundled）· AccessibilityService · 原生 View

---

## 致谢

- [CameraX](https://developer.android.com/media/camera/camerax) —— 稳定的相机流水线
- [ML Kit Face Detection](https://developers.google.com/ml-kit/vision/face-detection) —— 人脸关键点与欧拉角
- [Shizuku](https://github.com/RikkaApps/Shizuku) —— 探索过的手势注入备选方案（`dev.rikka.shizuku`）

以及所有在 Issue 里帮忙测试、反馈机型和日志的朋友。

---

## 求助与反馈

**欢迎提 [Issue](../../issues)**，尤其欢迎这几类：

- 🐛 **Bug 反馈** —— 请附上机型和系统版本，最好带上 `adb logcat` 日志
- 💡 **免 ADB 方案** —— 这是我最想要的
- 📱 **机型适配** —— 你在别的机器上跑通了/跑不通，都欢迎告诉我
- 🔧 **灵敏度调参** —— 说说你戴眼镜/不戴眼镜时的实际效果，我可以把默认值调得更合理

提 Issue 时如果能带上这些信息，定位会快很多：

```
机型 / 系统版本：
是否戴眼镜：
触发的动作（眨眼 / 点头）：
现象描述：
logcat（过滤 gazescroll）：
```

---

## License

[MIT](LICENSE)

# GazeScroll → 眨眼翻页

Android 测试版 App：前置摄像头检测**眨眼**，**连眨两次眼**模拟一次向上滑动，
用于在抖音里翻页。界面只有一个 TextView，全部逻辑在前台服务里后台运行。

面向小米 13 / Android 13 / MIUI 14。手机侧安装与调参请看
[`../眨眼翻页-使用说明.md`](../眨眼翻页-使用说明.md)。

包名沿用 `com.example.gazescroll`。

---

## 组件结构

```
app/src/main/java/com/example/gazescroll/
├── BlinkDetector.kt               ★ 眨眼状态机（纯逻辑，无 Android API）—— 主触发源
├── FaceGazeAnalyzer.kt            CameraX ImageAnalysis + ML Kit → AnalyzedFrame
│                                  含帧率门控：活跃 ~15fps / 待机 1fps
├── GazeCameraService.kt           前台服务（type=camera），拥有整条流水线
├── GazeAccessibilityService.kt    dispatchGesture 执行上滑（0.8h → 0.2h, 100ms）
├── GazeStateMachine.kt            原有「视线下看上扫」状态机，保留但默认关闭
├── GazeModel.kt                   GazeState / GazeSample / GazeConfig（纯数据 + 校验）
├── GazeRuntime.kt                 进程内共享黑板（跨线程快照 + 主线程回调）
├── AppPrefs.kt                    SharedPreferences 持久化
└── MainActivity.kt                极简：一个可点击的 TextView
```

数据流：

```
CameraX(前置, 480x360) → FaceGazeAnalyzer ──┬─→ BlinkDetector ──→ fireSwipe()
                                             └─→ GazeStateMachine（调试，默认关）
                        GazeRuntime ←────────────┘
                             ↓
                    GazeAccessibilityService.swipeUp()
```

## 触发逻辑

```
每帧读 leftEyeOpenProbability / rightEyeOpenProbability
  任一为 null → 跳过该帧
  avg = (left + right) / 2
    avg < 0.4  → eyesClosed = true
    avg > 0.6  → eyesClosed = false
    0.4~0.6    → 保持原状态（迟滞，抑制抖动）

一次完整眨眼 = eyesClosed 的下降沿（闭 → 睁）
两次眨眼间隔 ∈ [200ms, 1500ms] → 触发上滑
触发后 2 秒冷却，期间忽略所有眨眼信号（但仍跟踪睁闭状态）
```

`BlinkDetector.SINGLE_BLINK_FOR_TEST = true` 时，**单次眨眼**也触发，便于先验证滑动效果。

## 关键设计决定

| 决定 | 原因 |
| --- | --- |
| 眨眼是主触发，视线状态机降级为调试开关 | 按需求变更；`GazeConfig.gazeModeEnabled` 默认 `false` |
| 帧率门控放在 `FaceGazeAnalyzer.analyze()`，不重启 CameraX | 需求明确要求；重启 CameraX 代价高且会闪黑 |
| 熄屏时 `unbindAll()` 真正释放摄像头 | 熄屏是低频事件，不是逐帧决策；真释放才省电 |
| 用 `leftEyeOpenProbability` 而非关键点距离判眨眼 | ML Kit 的 `CLASSIFICATION_MODE_ALL` 直接给出该值，比几何估算稳定 |
| 迟滞区间（0.4~0.6 保持原状态） | 单独用 0.4 阈值时，概率在阈值附近抖动会产生大量假眨眼 |
| 疲劳/抖动不靠平滑，靠冷却 | 2 秒冷却比平滑更能压住连续误触 |
| 悬浮窗整体移除 | 需求要求；`SYSTEM_ALERT_WINDOW` 已从清单删除 |
| 服务自动启动、UI 不阻塞 | 相机权限一到手就 `startForegroundService`，用户可直接回桌面 |
| 通知只发一次不更新 | 需求要求固定文案；`setOnlyAlertOnce(true)` 且从不重建 |
| ML Kit 用 **bundled** 变体 | 模型打进 APK，侧载后离线可用，不依赖 Play Services 下模型 |
| 用 Views/XML 而不是 Compose | 少一层版本耦合，APK 更小 |

`ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` 用于屏幕开关广播，
符合 targetSdk 34 对运行时注册接收器的要求。

## 坐标处理（保留的调试路径仍需要）

`FaceGazeAnalyzer` 里 `AnalyzedFrame.gaze` 供调试用状态机消费。ML Kit 返回的关键点
在**旋转后（摆正）**缓冲区坐标系里，所以竖屏时归一化用 `imageProxy.width`；
前置镜头镜像只影响 X，判定只用 Y，因此不需要翻转。

参考：Android 官方 [mlkit-spatial 说明](https://github.com/android/skills/blob/main/camera/camerax/references/mlkit-spatial.md)。

## 构建

工具链全部在 `../.android-build/`，不污染用户目录：

| 组件 | 路径 |
| --- | --- |
| JDK | `.android-build/jdk/jdk-17.0.20.1+1` |
| Android SDK | `.android-build/android-sdk`（platform-tools / platforms;android-34 / build-tools;34.0.0） |
| Gradle | `.android-build/gradle/gradle-8.9` |
| Gradle 缓存 | `.android-build/gradle-user-home` |

两种方式，产物相同：

```powershell
# 1) 直接构建
cd 'D:\ruanjian\deepseek harness'
.\build-apk.ps1                 # debug
.\build-apk.ps1 -Clean          # 全量重建

# 2) 走 dsh-plugin-android-apk 自己的代码路径
node .android-build\build-via-plugin.mjs
```

产物：`GazeScroll/app/build/outputs/apk/debug/app-debug.apk`
（插件方式会额外复制到 `apk/`。）

版本组合：Gradle 8.9 / AGP 8.6.1 / Kotlin 2.0.21 / JDK 17 / compileSdk 34 / minSdk 26 / targetSdk 34。

### 网络

本机实测 `services.gradle.org` 与 `repo1.maven.org` 只有 30–80 KB/s，
因此 `gradle-wrapper.properties` 指向腾讯镜像，`settings.gradle.kts` 把阿里云
Maven 镜像排在 `mavenCentral()` 前面。换网络后可以改回官方源。

## 关于 dsh-plugin-android-apk

已安装到 DSH 的 `web` profile。它的 `build_android_apk` 工具**要重启 DSH 才会注册**，
在那之前可以用 `.android-build/build-via-plugin.mjs` 直接调用它的 `buildApk()` 入口，
走的是完全相同的代码路径。两个值得记录的细节：

1. **安装需要 `-w`**：`web/` 下有 `pnpm-workspace.yaml`，pnpm 会把它当 workspace root，
   社区 README 的命令需要补 `-w`：`dsh plugin --profile web add -w <tarball>`。

2. **上游把宿主模块声明成了 dependencies**，导致 pnpm 往 profile 里装了一整套并行的
   DSH 框架（`dsh-tools@0.1.0-rc.8` + 12 个兄弟包），而宿主运行时用的是 `0.1.2-rc.1`。
   本地打包版 `0.1.0-hostdeps` 把 `@deepseek-ai/*` 改成 `peerDependencies`
   （optional），配合 profile 里已有的 `autoInstallPeers: false`，
   插件就只依赖宿主自己那一份模块，实例完全一致。

   上游仓库：https://github.com/memories-coder/DSH-plugin-android-apk

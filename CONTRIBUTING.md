# 贡献指南 · Contributing to GazeScroll

首先，感谢你愿意花时间参与这个项目！🎉

无论是提 Bug、给建议、改文档还是写代码，**所有形式的贡献都非常欢迎**。

---

## 目录

- [行为准则](#行为准则)
- [我能做什么贡献](#我能做什么贡献)
- [开发环境准备](#开发环境准备)
- [克隆与编译](#克隆与编译)
- [运行与调试](#运行与调试)
- [代码规范](#代码规范)
- [提交 PR 的流程](#提交-pr-的流程)
- [Commit Message 约定](#commit-message-约定)
- [特别需要帮助的方向](#特别需要帮助的方向)

---

## 行为准则

参与本项目即表示你同意遵守 [行为准则](CODE_OF_CONDUCT.md)。请保持友善、尊重与耐心。

---

## 我能做什么贡献

你不需要会写 Kotlin 也能帮上忙：

| 类型 | 说明 |
| --- | --- |
| 🐛 **反馈 Bug** | 用 [Bug 报告模板](../../issues/new?template=bug_report.md) 提交，带上机型和 logcat |
| 💡 **提功能建议** | 用 [功能建议模板](../../issues/new?template=feature_request.md) |
| 📱 **机型适配** | 在你的手机上测试，告诉我们能不能跑通 |
| 👓 **灵敏度调参** | 反馈戴/不戴眼镜、不同光照下的实际检测效果 |
| 📖 **改进文档** | 错别字、表述不清、步骤缺失，都欢迎直接改 |
| 💻 **写代码** | 见下文流程 |

---

## 开发环境准备

| 依赖 | 版本 |
| --- | --- |
| JDK | **17**（必需，其他版本可能编译失败） |
| Android SDK | compileSdk **34** / buildTools **34.0.0** |
| Gradle | **8.9**（仓库已带 Wrapper，无需预装） |
| Android Studio | 推荐最新稳定版（可选，命令行也能编译） |

安装完 JDK 17 后确认：

```bash
java -version
# 应输出 17.x
```

Android SDK 需要设置 `ANDROID_HOME`，或直接在项目根目录创建 `local.properties`：

```properties
sdk.dir=/path/to/your/android-sdk
```

> ⚠️ `local.properties` 已在 `.gitignore` 中，**不会被提交**，请放心填写本机路径。

---

## 克隆与编译

```bash
# 1. Fork 本仓库（点右上角 Fork），然后克隆你自己的 fork
git clone https://github.com/<你的用户名>/GazeScroll.git
cd GazeScroll

# 2. 添加上游仓库，方便同步
git remote add upstream https://github.com/755489724-lgtm/GazeScroll.git

# 3. 编译 Debug APK
./gradlew assembleDebug        # macOS / Linux
gradlew.bat assembleDebug      # Windows

# 产物位置
# app/build/outputs/apk/debug/app-debug.apk
```

首次编译会自动下载 Gradle 8.9 和依赖，可能需要几分钟。

> 中国大陆用户如果下载 Gradle 发行包缓慢，可以修改
> `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 为腾讯云镜像
> （文件里已有注释示例），**但请不要把这个改动提交进 PR**。

---

## 运行与调试

### 安装到手机

```bash
./gradlew installDebug
# 或手动
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 必要的一次性授权

**本项目必须通过 ADB 授权一次才能工作**，详见 [README 的授权步骤](README.md#-必须的-adb-授权步骤只做一次)。

### 看日志

```bash
# 只看本项目的日志
adb logcat | grep gazescroll

# 或者按 tag 过滤
adb logcat -s GazeCameraService BlinkDetector HeadPoseDetector
```

### 关于测试

**项目目前没有自动化测试**，这是一个已知短板（欢迎补充）。

现阶段验证改动的方式是：

1. 编译通过（`./gradlew assembleDebug`）
2. 装到真机上，实际跑一遍翻页流程
3. 用 logcat 确认没有异常

如果你愿意帮忙补单元测试，`BlinkDetector.kt` 和 `HeadPoseDetector.kt` 是**纯逻辑、不依赖 Android API** 的，
非常适合作为切入点（状态机 + 阈值判定，输入输出都很明确）。

---

## 代码规范

- 语言：**Kotlin**，4 空格缩进
- 命名：类 `PascalCase`，函数/变量 `camelCase`，常量 `UPPER_SNAKE_CASE`
- 注释：**关键阈值和状态机跳转请写清楚为什么**，这类代码光看数字很难理解
  > 例如：`// 0.55 是为了兼容戴眼镜时眼睑反光导致的读数偏高`
- 日志：统一用 `Log.d(TAG, ...)`，`TAG` 用类名
- **不要提交**：
  - 签名文件（`.jks` / `.keystore` / `.p12`）
  - `local.properties`
  - `build/`、`.gradle/`、`.idea/`
  - 任何含密钥、Token、个人信息的配置

---

## 提交 PR 的流程

```bash
# 1. 确保上游是最新的
git checkout main
git pull upstream main

# 2. 开一个语义清晰的分支
git checkout -b fix/blink-threshold-glasses
#                   feat/settings-panel
#                   docs/improve-readme

# 3. 改代码，然后提交
git add <你改的文件>      # 不要用 git add . 之前先 git status 看一眼
git commit -m "fix: 降低戴眼镜时的眨眼阈值"

# 4. 推到你自己的 fork
git push origin fix/blink-threshold-glasses
```

然后到 GitHub 上点 **"Compare & pull request"**，填写 PR 模板里的检查清单。

### PR 通过的标准

- ✅ `./gradlew assembleDebug` 能编译通过（CI 会自动跑）
- ✅ 在真机上验证过改动确实生效
- ✅ PR 描述里说清楚**改了什么、为什么改、怎么验证的**
- ✅ 涉及阈值的改动，请附上**实测数据**（比如"戴眼镜时开合度读数在 0.62~0.71 之间，所以阈值从 0.55 提到 0.65"）

### 关于审核

这是个人维护的项目，**审核可能不及时，请耐心等待**。如果一周没回应，欢迎在 PR 里礼貌地 ping 一下。

---

## Commit Message 约定

使用 [Conventional Commits](https://www.conventionalcommits.org/) 风格：

```
<类型>: <简短描述>

<可选的详细说明>
```

常用类型：

| 类型 | 用途 |
| --- | --- |
| `feat` | 新功能 |
| `fix` | 修 Bug |
| `docs` | 只改文档 |
| `refactor` | 重构（不改变行为） |
| `perf` | 性能优化 |
| `build` | 构建配置相关 |
| `chore` | 杂项 |

示例：

```
fix: 修复相机偶发卡死后不再重启的问题

在 FRAME_TIMEOUT_MS 触发重启时，没有重置基准线，
导致重启后头部姿态检测仍用旧基准，翻页失灵。
现在 restart() 里会调用 seedBaseline()。
```

---

## 特别需要帮助的方向

这几件事我一个人做不好，非常需要你：

1. **🔴 免 ADB 方案**（最重要）
   目前必须用 ADB 授权一次 `WRITE_SECURE_SETTINGS` 才能自动开启无障碍服务。
   有没有正当的替代方式？见 [求助 Issue](../../issues)。

2. **📱 机型适配反馈**
   你是小米 / 华为 / OPPO / vivo 用户？告诉我能不能跑通、哪里出问题。

3. **👓 戴眼镜场景的识别率**
   眨眼检测的阈值是按"戴眼镜"调的，但样本只有我一个人。
   如果你戴眼镜/隐形/不戴，实测数据对我非常宝贵。

4. **🧪 补单元测试**
   `BlinkDetector` 和 `HeadPoseDetector` 是纯逻辑类，很好测。

5. **⚡ 功耗优化**
   目前三级功耗模型（运行/温机/释放）还不够精细，有想法欢迎讨论。

---

## 再次感谢

每一个 Issue、每一条实测数据、每一行代码，都会让这个项目变得更好。谢谢！❤️

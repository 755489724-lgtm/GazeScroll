固定签名（release keystore）说明
================================

## 这个文件是干什么的

`gazescroll-release.jks` = 这个 App 的**固定签名密钥**。从 v5.65 起，
**debug 和 release 两种构建都用它签名**（见 `app/build.gradle.kts` 的 `signingConfigs`）。

## 为什么需要它

Android 规定：**同一个包名（com.example.gazescroll）的 APK，只有签名相同才能覆盖安装**。

- 以前用的是 debug 签名 —— 那是**每台电脑各自随机生成**的一个 key。
  结果：我这边打的包装不上你那台机器打的包，你朋友打的包也装不上你的，
  换台机器就得先卸载，**用户设置会一起没**。
- 换成这个固定 keystore 之后：**任何电脑、任何一次构建**产出的 APK，
  互相之间都能直接覆盖升级；别人拿到 APK 也能直接点安装。

## 口令与参数（照抄即可）

```
文件      : gazescroll-release.jks
别名 alias: gazescroll
store 口令: gazescroll2026
key   口令: gazescroll2026
算法      : RSA 2048 / SHA256withRSA
有效期    : 10000 天（约 27 年）
证书 SHA-256 指纹:
  71:1A:E3:D5:D3:19:A6:93:17:D8:AD:49:AD:91:39:F9:38:7F:E1:9D:2C:97:35:91:DA:FB:1B:43:D0:F2:31:F9
```

命令行核对（JDK 的 keytool）：

```
keytool -list -v -keystore gazescroll-release.jks -storepass gazescroll2026
```

## ⚠️ 唯一的铁律：别弄丢

**这个文件一旦丢失，同一个包名就永远无法再覆盖升级了**（只能换包名重新来过，
用户的设置也带不过去）。所以它现在有三份：

1. `GazeScroll\keystore\gazescroll-release.jks`　　　（构建时用，已被 .gitignore 排除）
2. `backup\keystore\gazescroll-release.jks`　　　　（备份目录）
3. `D:\ruanjian\deepseek harness\keystore\`　　　　（工作区根目录一份）

**建议再往 U 盘 / 网盘 / 邮箱里各放一份。** 这三份都在同一台电脑上，硬盘坏了就全没了。

## 它不进 git（故意的）

仓库根目录 `.gitignore` 里有 `*.jks` 规则。原因是：如果 keystore 跟着代码推到公开仓库，
任何人都能用同一身份签名一个"升级包"。所以：

- **只发 APK 给别人装** → 不需要给 keystore；
- **别人要自己编译、并且希望打出来的包能覆盖你的** → 把这个 jks 单独发给他，
  让他放到 `<项目>\keystore\gazescroll-release.jks`（路径固定，不用改代码）。

# 从源码构建

需要 JDK 17+ 和 Android SDK（build-tools 任意较新版本 + platforms/android-36）。不用 Gradle：

```sh
./build.sh            # 输出 build/mosinputglass.apk
```

- SDK 路径：设置 `ANDROID_HOME`（不设时依次找 `~/Library/Android/sdk`、`~/Android/Sdk`）。
- build-tools 版本：设置 `BUILD_TOOLS`（不设时用已安装的最新版本）。
- JDK：设置 `JAVA_HOME`（不设时用 PATH 里的 `javac`；macOS 上装了 Android Studio 会自动用它自带的 JDK）。
- 签名：发布时设置 `KEYSTORE`、`KEYSTORE_PASS`、`KEY_ALIAS`、`KEY_PASS` 用你自己的签名；
  不设时用 `~/.android/mosinputglass-debug.keystore`（第一次构建时自动生成，不在项目目录里）。

## 目录

| 路径 | 内容 |
|---|---|
| `src/` | 模块代码：`Main`（在输入法进程里运行的 hook）、`SettingsActivity`（设置页）、`Config`（设置项和默认值） |
| `resources/META-INF/xposed/` | LSPosed 模块元数据（入口类、作用域、API 版本） |
| `res/` | 图标和字符串 |
| `docs/screenshots/` | README 里的截图 |
| `lib/` | libxposed service（打进 APK），见 `lib/NOTICE.md` |
| `stubs/` | libxposed API（只用于编译，运行时由 LSPosed 提供） |
| `icon/source.webp` | 图标原图 |
| `test.sh` | 作者调试用的脚本，不是模块的一部分；只适配已 root 的荣耀 WKL-AN20，需要先 `export ANDROID_SERIAL=<设备>` |

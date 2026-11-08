`lib/java/io/github/libxposed/service` 和 `lib/aidl` 来自 https://github.com/libxposed/service（commit 0cfe0a4c），
`stubs/io/github/libxposed/api` 来自 https://github.com/libxposed/api（commit 79b75b49），均为 Apache License 2.0，未修改。
`stubs` 只用于编译，不打进 APK（运行时由 LSPosed 提供）；`lib` 会打进 APK。
`lib/java/androidx/annotation`、`lib/java/io/github/libxposed/annotation` 是为了不依赖 Gradle/AndroidX 写的最小注解桩。

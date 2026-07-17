# FontRedirect

LSPosed 模块，强制目标应用使用指定的系统字体文件渲染文字。当前版本：3.8.2。

## 工作原理

- 启动时解析 `/etc/fonts.xml`，根据 `lang`、`variant` 等属性定位系统字体文件（英文 sans-serif、CJK 等）。
- 对普通应用，直接用 `Typeface.Builder` / `Typeface.createFromFile` 加载 `/system/fonts/` 下的真实字体文件。
- 对 Magisk DenyList 中的应用：其 mount namespace 被隔离，Magisk 字体 overlay 会被卸载，`/system/fonts` 回退为原厂字体。模块通过 `android.system.Os.stat()` 比较选中的 CJK 字体文件与 `/system/fonts/` 目录的 `st_dev`；若发现字体未被替换，则自动改用 APK 内置字体。
- 如果 DenyList 隔离导致 `createPackageContext()` 无法创建模块上下文，会直接从 APK 的 `assets/` 中解压内置字体继续工作。
- 自动跳过以 `:Security` 结尾的银行自检进程，避免触发 Xposed 检测导致崩溃。
- Hook 层级覆盖 `TextView`、`Paint.setTypeface`、`Canvas.drawText/drawTextRun`、WebView 的 `@font-face` 注入，以及 Flutter 应用的 `AAssetManager_open` GOT 重定向。

## Flutter 应用支持

Flutter 不经过 Android 的 `TextView`/`Paint`，而是直接通过 Skia/Impeller 使用 `libflutter.so` 加载 APK 内置字体资源。模块通过原生 JNI 组件在目标进程内完成以下操作：

1. 枚举已加载模块，定位 `libflutter.so` 的 ELF 镜像。
2. 解析其动态重定位表，找到 `AAssetManager_open`、`AAsset_getBuffer`、`AAsset_getLength`、`AAsset_getLength64`、`AAsset_read`、`AAsset_close` 的 GOT 表项。
3. 使用 `mprotect` 绕过 RELRO 只读保护，将实际被 `libflutter.so` 导入的 GOT 表项替换为模块实现的拦截函数（不同 Flutter 版本可能只导入其中一部分，缺失的表项会被跳过）。
4. 当 `libflutter.so` 尝试打开 `.ttf`/`.otf`/`.ttc`/`.otc` 等任意字体资源时，返回指向替换字体文件的伪 `AAsset` 句柄；所有被加载的 Flutter 内置字体都会按文件名中的 CJK 关键字区分并替换。
5. 若 `Application.onCreate` 触发时 `libflutter.so` 尚未加载，后台轮询最多 5 秒，在加载完成后补刀。

> 注意：Flutter 直装应用的字体完全替换依赖 APK 中字体资源的文件名可被识别为字体文件。如果应用通过动态下载或自定义扩展名加载字体，拦截可能不会生效。

## 内置字体

`app/src/main/assets/` 下包含两个回退字体，仅在 DenyList 隔离或系统字体未被替换时使用：

- `Roboto-Regular.ttf`
- `OSans-RC-Regular.ttf`

> 这两个文件是 DenyList 兼容所必需的。删除后，处于 DenyList 中的应用（例如 `cmb.pb`）将无法加载替换字体，回退到原厂字体。

## 构建

项目已包含 Gradle Wrapper，使用 Android Studio 或命令行均可。

环境要求：

- JDK 17（推荐直接使用 Android Studio 自带的 JBR）
- Android SDK（`compileSdk 34`）
- Android NDK（CMake 项目需要 NDK 来编译 `libfontredirect.so`）

命令行构建：

```bash
set JAVA_HOME=C:\Android-Studio\jbr
set ANDROID_HOME=C:\Android-SDK
.\gradlew.bat assembleRelease --no-daemon
```

Release APK 会使用项目根目录的 `fontredirect.jks` 自动签名，输出路径为：

```
app/build/outputs/apk/release/app-release.apk
```

## 签名密钥

签名密钥 `fontredirect.jks` 位于项目根目录，已被 `.gitignore` 排除，不会进入 Git 仓库。自行修改构建时，请确保该文件存在；若丢失，需重新生成密钥并在 `app/build.gradle` 中更新配置。

## 使用步骤

1. 在 LSPosed 中启用模块，作用域勾选目标应用。
2. 安装 Release APK 并重启设备。
3. 强制停止并重新打开目标应用即可生效。

## 日志排查

日志路径：

```
/sdcard/Android/data/<目标包名>/files/Logs/FontRedirect.log
```

关键日志：

- `Fonts loaded from system config` + `replaced=true`：正常使用了 Magisk 替换后的系统字体。
- `CJK system font not replaced ... falling back to embedded fonts`：检测到 DenyList 或未挂载模块字体，已切换到内置字体。
- `Fonts loaded from embedded assets via apk path`：DenyList 隔离导致 `createPackageContext()` 失败，直接从 APK 解压字体。
- `Flutter AAsset hook result=true`：成功在 Flutter 应用中 patch `libflutter.so` 的 GOT 表项。
- `Flutter AAsset hook result=false`：`Application.onCreate` 时 `libflutter.so` 尚未加载，已启动后台轮询。
- `Flutter AAsset GOT hook applied (x/y slots)`：后台轮询成功找到 `libflutter.so` 并 patch 了实际存在的 `x` 个 GOT 表项。
- `replaced asset <name> -> <path>`：Flutter 应用加载的字体资源已被替换为模块字体。

## 项目结构

- `app/src/main/assets/`：内置回退字体与 `xposed_init`
- `app/src/main/java/org/c0fle4/FontRedirect/hook/`
  - `HookEntry.java`：Xposed 入口、进程过滤、Hook 注册
  - `FontLoader.java`：系统字体解析与加载、DenyList 检测、内置字体回退
  - `SystemFontConfig.java`：解析 `/etc/fonts.xml`
  - `TypefaceSourceTracker.java`：追踪 Typeface 来源
  - `WebViewInjector.java`：WebView `@font-face` 注入
  - `FontRedirectNative.java`：原生 JNI 入口封装
  - `FileLogger.java`：日志输出
- `app/src/main/cpp/`
  - `fontredirect.c`：解析 `libflutter.so` ELF 并重写 `AAsset*` GOT 表项
  - `CMakeLists.txt`：原生库构建配置

## 注意事项

- 模块是否生效完全由 LSPosed 作用域决定；App 内的应用列表/复选框界面已不实际控制 Hook。
- 应用在 Magisk DenyList 中也能生效，不需要手动移除。
- 银行类应用（如 `cmb.pb`）的 `:Security` 进程会被自动跳过，主进程仍会正常 Hook。
- Flutter 支持通过原生 GOT patch 实现，理论上适用于所有使用标准 NDK `AAsset*` API 加载资源的 Flutter 版本；Skia/Impeller 内部字体缓存可能导致首次启动时旧字体仍短暂出现。

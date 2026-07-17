# FontRedirect

LSPosed 模块，强制目标应用使用指定的系统字体文件渲染文字。当前版本：3.7.3。

## 工作原理

- 启动时解析 `/etc/fonts.xml`，根据 `lang`、`variant` 等属性定位系统字体文件（英文 sans-serif、CJK 等）。
- 对普通应用，直接用 `Typeface.Builder` / `Typeface.createFromFile` 加载 `/system/fonts/` 下的真实字体文件。
- 对 Magisk DenyList 中的应用：其 mount namespace 被隔离，Magisk 字体 overlay 会被卸载，`/system/fonts` 回退为原厂字体。模块通过 `android.system.Os.stat()` 比较选中的 CJK 字体文件与 `/system/fonts/` 目录的 `st_dev`；若发现字体未被替换，则自动改用 APK 内置字体。
- 如果 DenyList 隔离导致 `createPackageContext()` 无法创建模块上下文，会直接从 APK 的 `assets/` 中解压内置字体继续工作。
- 自动跳过以 `:Security` 结尾的银行自检进程，避免触发 Xposed 检测导致崩溃。
- Hook 层级覆盖 `TextView`、`Paint.setTypeface`、`Canvas.drawText/drawTextRun` 以及 WebView 的 `@font-face` 注入。

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

## 项目结构

- `app/src/main/assets/`：内置回退字体与 `xposed_init`
- `app/src/main/java/org/c0fle4/FontRedirect/hook/`
  - `HookEntry.java`：Xposed 入口、进程过滤、Hook 注册
  - `FontLoader.java`：系统字体解析与加载、DenyList 检测、内置字体回退
  - `SystemFontConfig.java`：解析 `/etc/fonts.xml`
  - `TypefaceSourceTracker.java`：追踪 Typeface 来源
  - `WebViewInjector.java`：WebView `@font-face` 注入
  - `FileLogger.java`：日志输出

## 注意事项

- 模块是否生效完全由 LSPosed 作用域决定；App 内的应用列表/复选框界面已不实际控制 Hook。
- 应用在 Magisk DenyList 中也能生效，不需要手动移除。
- 银行类应用（如 `cmb.pb`）的 `:Security` 进程会被自动跳过，主进程仍会正常 Hook。

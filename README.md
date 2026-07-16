# FontRedirect (LSPosed 模块)

强制指定应用内文字使用系统字体配置中的具体字体文件。

## 工作机制

- 启动时解析 `/etc/fonts.xml`，根据 `lang` 与 `variant` 属性选择对应的系统字体文件（英文/CJK）。
- 对普通应用，使用 `Typeface.Builder` / `Typeface.createFromFile` 加载真实字体文件，不再依赖字体族名称。
- 对 Magisk DenyList 应用：其进程运行在隔离的 mount namespace 中，模块字体 overlay 已被卸载，`/system/fonts` 会回退到原厂字体。模块通过 `Os.stat()` 比较 CJK 系统字体文件与 `/system/fonts/` 目录的 `st_dev` 来判断字体是否真正被替换；若未被替换，则自动改用模块内置字体。
- 自动跳过以 `:Security` 结尾的银行自检进程，避免触发 `envchecker` 等自我保护导致的崩溃。
- Hook 层级覆盖 `TextView`、`Paint.setTypeface`、`Canvas.drawText/drawTextRun` 以及 WebView 的 `@font-face` 注入。

## 使用步骤

1. 在 LSPosed 中启用模块，作用域勾选目标应用。
2. 构建并安装：`cmd //c gradlew.bat assembleRelease --no-daemon`（已配置 release 签名）。
3. 生成的 APK 位于 `app/build/outputs/apk/release/app-release.apk`。
4. 安装后重启设备，使 LSPosed 加载新版本模块。
5. 强制停止并重新打开目标应用即可生效。

## 项目结构

- `app/src/main/assets/`：模块内置字体
  - `Roboto-Regular.ttf`
  - `OSans-RC-Regular.ttf`
- `app/src/main/assets/xposed_init`：Xposed 入口类声明
- `app/src/main/java/org/c0fle4/FontRedirect/hook/HookEntry.java`：Hook 入口与进程过滤
- `app/src/main/java/org/c0fle4/FontRedirect/hook/FontLoader.java`：系统字体配置解析与加载
- `app/src/main/java/org/c0fle4/FontRedirect/hook/SystemFontConfig.java`：`/etc/fonts.xml` 解析
- `app/src/main/java/org/c0fle4/FontRedirect/hook/TypefaceSourceTracker.java`：Typeface 来源追踪
- `app/src/main/java/org/c0fle4/FontRedirect/hook/WebViewInjector.java`：WebView 字体注入
- `app/src/main/java/org/c0fle4/FontRedirect/log/FileLogger.java`：日志输出

## 注意事项

- 模块是否生效完全由 LSPosed 作用域决定；App 内的应用列表/复选框界面已不实际控制 Hook。
- 应用在 Magisk DenyList 中也能生效。DenyList 会卸载模块字体 overlay，模块通过 `Os.stat()` 检测到 `/system/fonts` 下字体未被替换后，会自动改用内置字体；如果 `createPackageContext()` 因隔离命名空间失败，则直接从 APK 解压内置字体。日志分别对应 `Fonts loaded from embedded assets` 与 `Fonts loaded from embedded assets via apk path`。
- 不在 DenyList 中的应用，模块会优先使用 Magisk 替换后的系统字体（可通过日志中 `Fonts loaded from system config` 与 `replaced=true` 确认）。
- 日志位于 `/sdcard/Android/data/<目标包名>/files/Logs/FontRedirect.log`，可用于排查是否加载了正确的字体文件。
- 银行类应用（如 `cmb.pb`）的 `:Security` 进程会被自动跳过，主进程仍会正常 Hook。

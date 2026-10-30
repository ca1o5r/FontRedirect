# FontRedirect

LSPosed 模块，强制目标应用使用模块内置的三个字体文件渲染文字。当前版本：3.8.10。

## 工作原理

- 模块**始终优先**使用 `assets/` 里的三个字体文件：启动时先把它们解压到目标应用的私有目录，再用 `Typeface.createFromFile` 加载。这样即使设备装了 Magisk 字体模块、`/etc/fonts.xml` 指向别的字体家族，被 hook 的应用也一定使用这三个文件。
- 仅当内置字体无法加载时才回退解析 `/etc/fonts.xml`（英文 sans-serif、CJK 等），再不行就保持应用原有字体。
- 英文（非 CJK）文本按样式区分：常规使用 `Latin.ttf`，斜体使用 `LatinItalic.ttf`；中日韩文本统一使用 `CJK.ttf`。
- 解压出的文件名为 `font_redirect_<资源指纹>_<原文件名>`，启动时清理所有过期的 `font_redirect_*`，因此升级模块后一定是新字体。
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

`app/src/main/assets/` 下包含三个替换字体，按文字类型与样式分工：

| 文件 | 内部字体名 | 用途 |
| --- | --- | --- |
| `CJK.ttf` | `FRCJK` | 中日韩文字（汉字、假名、谚文、全角标点） |
| `Latin.ttf` | `FRLatin`（Bold） | 非中日韩的常规（非斜体）文字 |
| `LatinItalic.ttf` | `FRLatinItalic`（Italic） | 非中日韩的斜体文字 |

判定规则：文本中包含 CJK 字符时一律使用 `CJK.ttf`（斜体 CJK 由系统合成倾斜）；否则根据 `Typeface` 的 `ITALIC` 样式位，或字体资源名中的 `italic` / `oblique` 关键字选择 `LatinItalic.ttf`，其余使用 `Latin.ttf`。

- 三个文件的字形数据未作任何改动，只重建了 `name` 表（family / full / PostScript 名），`OS/2` 与 `head` 的字重、斜体标志保持原样。
- 内置字体是唯一字体来源；系统字体配置只在解压失败时兜底。
- WebView 注入的 `@font-face` 同时注册 normal 与 italic 字面，页面中 `font-style: italic` 的元素会命中 `LatinItalic.ttf`。

> 这三个文件是 DenyList 兼容所必需的。删除后，处于 DenyList 中的应用（例如 `cmb.pb`）将无法加载替换字体，回退到原厂字体。

## 构建

项目已包含 Gradle Wrapper，使用 Android Studio 或命令行均可。

环境要求：

- JDK 17（推荐直接使用 Android Studio 自带的 JBR）
- Android SDK（`compileSdk 34`）
- Android NDK（CMake 项目需要 NDK 来编译 `libfontredirect.so`）

`local.properties`（已被 `.gitignore` 排除，需要本机自行创建）必须指向本机的 Android SDK，否则 Gradle 会报
`SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable or by setting the sdk.dir path ...`：

```properties
sdk.dir=C\:\\Android-SDK
```

命令行构建（`gradle.properties` 已通过 `org.gradle.java.home` 固定 JDK 17，且 `local.properties` 已提供 SDK 路径，因此无需再设置 `JAVA_HOME` / `ANDROID_HOME`）：

```bash
.\gradlew.bat assembleRelease --no-daemon
```

### VS Code（Java 扩展）

VS Code 的 Java 扩展通过 `java.import.gradle.java.home` 挑选 Gradle daemon 使用的 JVM；未设置时会回退到 VS Code 进程启动时继承的 `JAVA_HOME`。该值在客户端生效，**会覆盖** `gradle.properties` 中的 `org.gradle.java.home`，因此若 VS Code 启动时 `JAVA_HOME` 指向 JDK 8，导入项目就会报
`No matching variant of com.android.tools.build:gradle:7.4.2 was found ... compatible with Java 8`（AGP 7.4.2 要求 Java 11+）。

仓库内的 `.vscode/settings.json` 已把它固定到 `C:\JDK-17.0.12`：

```json
{
    "java.import.gradle.java.home": "C:\\JDK-17.0.12"
}
```

若本机 JDK 安装路径不同，请修改这一行。修改后执行 `Developer: Reload Window` 即可重新导入。

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

- `Fonts loaded from embedded assets` / `Fonts loaded from embedded assets via apk path`：正常路径，三个内置字体已解压并加载（`latin=`、`latinItalic=`、`cjk=` 给出实际文件）。
- `Failed to load embedded fonts ...`：解压或加载失败，此时才回退到系统字体配置。
- `Fonts loaded from system config`：兜底路径，使用了 `/etc/fonts.xml` 指向的字体。
- `extractAsset wrote <name> (<bytes>)`：字体因指纹变化而重新解压，说明升级后的新字体已生效。
- `Flutter AAsset hook result=true`：成功在 Flutter 应用中 patch `libflutter.so` 的 GOT 表项。
- `Flutter AAsset hook result=false`：`Application.onCreate` 时 `libflutter.so` 尚未加载，已启动后台轮询。
- `Flutter AAsset GOT hook applied (x/y slots)`：后台轮询成功找到 `libflutter.so` 并 patch 了实际存在的 `x` 个 GOT 表项。
- `replaced asset <name> -> <path>`：Flutter 应用加载的字体资源已被替换为模块字体。

## 项目结构

- `app/src/main/assets/`：内置回退字体与 `xposed_init`
- `app/src/main/java/org/c0fle4/FontRedirect/hook/`
  - `HookEntry.java`：Xposed 入口、进程过滤、Hook 注册
  - `FontLoader.java`：内置字体解压与加载、字体选择、系统字体兜底
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
- **Google 密码管理工具（Google Password）**：桌面/抽屉中的“Google 密码”图标对应快捷方式应用 `com.google.android.apps.credentialmanager`，该 APK 仅为一个跳板，其实际密码管理界面与列表全部由 **Google Play 服务（`com.google.android.gms`）** 渲染。若要让 Google 密码生效，必须在 LSPosed 作用域中勾选 **【Google Play 服务】（`com.google.android.gms`）**；若仅勾选了前者，模块会在打开时 Toast 提示。
- **WebView 混合应用与自定义 Scheme 跳转**：模块完整代理了 `WebViewClient` 的所有回调并保留原生 Client 委托，同时对非 Web 协议（如 `cmbmobilebank://`、`alipays://`、`weixin://`、`intent://` 等）提供自动意图分发兜底，避免出现 `net::ERR_UNKNOWN_URL_SCHEME` 导致页面无法打开。
- Flutter 支持通过原生 GOT patch 实现，理论上适用于所有使用标准 NDK `AAsset*` API 加载资源的 Flutter 版本；Skia/Impeller 内部字体缓存可能导致首次启动时旧字体仍短暂出现。

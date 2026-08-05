package org.c0fle4.FontRedirect.hook;

import android.app.Application;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Layout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.widget.TextView;

import org.c0fle4.FontRedirect.log.FileLogger;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.net.URL;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "FontRedirect";
    private static final ThreadLocal<Boolean> applying = ThreadLocal.withInitial(() -> false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        final String pkg = lpparam.packageName;
        final ClassLoader cl = lpparam.classLoader;
        if (pkg == null || pkg.startsWith("android") || pkg.equals("com.android.systemui")) {
            return;
        }

        // Banking apps often run self-protection code in a dedicated :Security process.
        // Hooking it triggers their Xposed/LSPosed detection and crashes the process,
        // which can put the app into a restricted fallback mode. Skip it entirely.
        String processName = getCurrentProcessName();
        if (processName != null && processName.endsWith(":Security")) {
            XposedBridge.log("FontRedirect: skipping protected security process " + processName);
            return;
        }

        // 依赖 LSPosed 作用域来控制是否 Hook，不再读取应用内选择列表
        XposedBridge.log("FontRedirect: handleLoadPackage " + pkg);

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Application app = (Application) param.thisObject;
                FileLogger.init(app);
                FileLogger.i(TAG, "Application.onCreate hooked for " + pkg);

                // Weex/Unicorn caches original fonts and glyph textures. Clear only
                // font/Weex-related cache entries on every cold start so the engine
                // has to rebuild its text cache through our hooked font paths.
                CacheCleaner.cleanFontCaches(app);

                try {
                    Context moduleCtx = app.createPackageContext("org.c0fle4.FontRedirect", Context.CONTEXT_IGNORE_SECURITY);
                    FileLogger.i(TAG, "module context created for " + pkg);
                    FontLoader.init(moduleCtx, app);
                } catch (Throwable t) {
                    FileLogger.e(TAG, "Failed to create module context for " + pkg + ", trying ClassLoader fallback", t);
                    try {
                        String apkPath = getModuleApkPath();
                        FileLogger.i(TAG, "module apk path=" + apkPath);
                        FontLoader.init(apkPath, app);
                    } catch (Throwable t2) {
                        FileLogger.e(TAG, "Failed to load fonts via ClassLoader fallback for " + pkg, t2);
                        // Cannot proceed without fonts
                        return;
                    }
                }

                // Register all hooks after logger/fonts are ready so every registration log is captured.
                hookTextView(pkg, cl);
                TypefaceSourceTracker.hook(pkg, cl);
                hookPaintSetTypeface(pkg, cl);
                hookCanvasDrawText(pkg, cl);
                hookFileInputStreamForFonts(pkg, cl);
                hookWeexTextManager(pkg, cl);
                hookWeexTextStyleUtils(pkg, cl);
                hookWeexTextLayoutBuilder(pkg, cl);
                hookWeexTextDrawable(pkg, cl);
                hookDynamicWeexClasses(pkg, cl);
                WebViewInjector.hook(pkg, cl);
                hookFlutterFonts(pkg);
            }
        });
    }

    private void hookFlutterFonts(String pkg) {
        try {
            if (!FontRedirectNative.isAvailable()) {
                FileLogger.w(TAG, "Native library unavailable, skipping Flutter hook for " + pkg);
                return;
            }
            File latin = FontLoader.getLatinFontFile();
            File cjk = FontLoader.getCjkFontFile();
            if (latin == null || cjk == null) {
                FileLogger.w(TAG, "Font files not ready, skipping Flutter hook for " + pkg);
                return;
            }
            boolean ok = FontRedirectNative.hookFlutter(latin.getAbsolutePath(), cjk.getAbsolutePath());
            FileLogger.i(TAG, "Flutter AAsset hook result=" + ok + " for " + pkg);
        } catch (Throwable t) {
            FileLogger.e(TAG, "Flutter hook failed for " + pkg, t);
        }
    }

    private static String getCurrentProcessName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return Application.getProcessName();
            } catch (Throwable ignored) {
            }
        }
        try {
            Object at = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread")
                    .invoke(null);
            java.lang.reflect.Field field = at.getClass().getDeclaredField("mProcessName");
            field.setAccessible(true);
            return (String) field.get(at);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String getModuleApkPath() throws Exception {
        ClassLoader cl = HookEntry.class.getClassLoader();
        if (cl == null) {
            throw new NullPointerException("HookEntry ClassLoader is null");
        }

        // Method 1: ProtectionDomain CodeSource
        try {
            URL url = HookEntry.class.getProtectionDomain().getCodeSource().getLocation();
            if (url != null) {
                String path = url.getPath();
                FileLogger.i(TAG, "CodeSource location=" + path);
                if (path != null && new File(path).getAbsolutePath().endsWith(".apk")) {
                    return new File(path).getAbsolutePath();
                }
            }
        } catch (Throwable t) {
            FileLogger.e(TAG, "CodeSource method failed", t);
        }

        // Method 2: ClassLoader resource URL (jar:file:/path/base.apk!/assets/...)
        try {
            URL res = cl.getResource("assets/Roboto-Regular.ttf");
            if (res != null) {
                String s = res.toString();
                FileLogger.i(TAG, "resource URL=" + s);
                if (s.startsWith("jar:file:")) {
                    int end = s.indexOf("!/");
                    if (end > 0) {
                        String path = s.substring("jar:file:".length(), end);
                        if (new File(path).getAbsolutePath().endsWith(".apk")) {
                            return new File(path).getAbsolutePath();
                        }
                    }
                } else if (s.startsWith("file:")) {
                    String path = s.substring("file:".length());
                    if (new File(path).getAbsolutePath().endsWith(".apk")) {
                        return new File(path).getAbsolutePath();
                    }
                }
            }
        } catch (Throwable t) {
            FileLogger.e(TAG, "resource URL method failed", t);
        }

        // Method 3: DexPathList reflection
        FileLogger.i(TAG, "module ClassLoader=" + cl.getClass().getName());
        Object pathList = getField(cl, "pathList");
        Object[] dexElements = (Object[]) getField(pathList, "dexElements");
        for (Object element : dexElements) {
            File path = (File) getField(element, "path");
            if (path != null && path.getAbsolutePath().endsWith(".apk")) {
                return path.getAbsolutePath();
            }
            File file = (File) getField(element, "file");
            if (file != null && file.getAbsolutePath().endsWith(".apk")) {
                return file.getAbsolutePath();
            }
            File zip = (File) getField(element, "zip");
            if (zip != null && zip.getAbsolutePath().endsWith(".apk")) {
                return zip.getAbsolutePath();
            }
        }
        throw new IOException("No .apk found for module ClassLoader: " + cl.getClass().getName());
    }

    private Object getField(Object obj, String name) throws Exception {
        Class<?> clazz = obj.getClass();
        java.lang.reflect.Field field = null;
        while (clazz != null) {
            try {
                field = clazz.getDeclaredField(name);
                break;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        if (field == null) {
            throw new NoSuchFieldException(name);
        }
        field.setAccessible(true);
        return field.get(obj);
    }

    private void hookTextView(String pkg, ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.widget.TextView", cl, "setText",
                    CharSequence.class, TextView.BufferType.class, boolean.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            applyFontToTextView(pkg, param.thisObject);
                        }
                    });
            FileLogger.i(TAG, "TextView.setText hooked for " + pkg);
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to hook setText for " + pkg, t);
        }

        try {
            XposedHelpers.findAndHookMethod("android.widget.TextView", cl, "setTypeface",
                    Typeface.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            handleSetTypeface(pkg, param, true);
                        }
                    });
            FileLogger.i(TAG, "TextView.setTypeface(Typeface) hooked for " + pkg);
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to hook setTypeface(Typeface) for " + pkg, t);
        }

        try {
            XposedHelpers.findAndHookMethod("android.widget.TextView", cl, "setTypeface",
                    Typeface.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            handleSetTypeface(pkg, param, false);
                        }
                    });
            FileLogger.i(TAG, "TextView.setTypeface(Typeface,int) hooked for " + pkg);
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to hook setTypeface(Typeface,int) for " + pkg, t);
        }
    }

    private void handleSetTypeface(String pkg, XC_MethodHook.MethodHookParam param, boolean singleArg) {
        if (applying.get()) return;
        try {
            TextView tv = (TextView) param.thisObject;
            CharSequence text = tv.getText();
            Typeface desired = FontLoader.getTypefaceForText(text);
            Typeface original = (Typeface) param.args[0];
            FileLogger.i(TAG, "setTypeface pkg=" + pkg + " text=[" + text + "] cjk=" + FontLoader.containsCJK(text)
                    + " desired=" + FontLoader.getTypefaceName(desired) + " original=" + FontLoader.getTypefaceName(original));
            if (desired != null && desired != original) {
                applying.set(true);
                try {
                    param.args[0] = desired;
                    FileLogger.i(TAG, "setTypeface replaced pkg=" + pkg + " text=[" + text + "] -> " + FontLoader.getTypefaceName(desired));
                } finally {
                    applying.set(false);
                }
            }
        } catch (Throwable t) {
            FileLogger.e(TAG, "handleSetTypeface failed for " + pkg, t);
        }
    }

    private void hookPaintSetTypeface(String pkg, ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.graphics.Paint", cl, "setTypeface",
                    Typeface.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (applying.get()) return;
                            try {
                                Typeface original = (Typeface) param.args[0];
                                String source = TypefaceSourceTracker.getSource(original);
                                String classify = source != null ? TypefaceSourceTracker.classify(original) : "unknown";
                                FileLogger.i(TAG, "Paint.setTypeface called pkg=" + pkg
                                        + " typeface=" + original + " source=" + source + " classify=" + classify);
                                Typeface desired = null;
                                String reason = null;
                                if (TypefaceSourceTracker.isKnownCjk(original)) {
                                    desired = FontLoader.getCjkTypeface();
                                    reason = "known-cjk";
                                } else if (TypefaceSourceTracker.isKnownEnglish(original)) {
                                    desired = FontLoader.getEnglishTypeface();
                                    reason = "known-english";
                                }
                                if (desired != null && desired != original) {
                                    applying.set(true);
                                    try {
                                        param.args[0] = desired;
                                        FileLogger.i(TAG, "Paint.setTypeface replaced pkg=" + pkg + " reason=" + reason
                                                + " original=" + original + "(" + source + ") -> " + FontLoader.getTypefaceName(desired));
                                    } finally {
                                        applying.set(false);
                                    }
                                }
                            } catch (Throwable t) {
                                FileLogger.e(TAG, "Paint.setTypeface hook failed for " + pkg, t);
                            }
                        }
                    });
            FileLogger.i(TAG, "Paint.setTypeface hooked for " + pkg);
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to hook Paint.setTypeface for " + pkg, t);
        }
    }

    private void hookCanvasDrawText(String pkg, ClassLoader cl) {
        String[] classes = {
                "android.graphics.Canvas",
                "android.view.DisplayListCanvas",
                "android.graphics.BaseRecordingCanvas",
                "android.graphics.RecordingCanvas"
        };
        String[] methods = {"drawText", "drawTextRun"};
        for (String className : classes) {
            for (String method : methods) {
                try {
                    Class<?> clazz = XposedHelpers.findClass(className, cl);
                    int hooked = 0;
                    for (Method m : clazz.getDeclaredMethods()) {
                        if (m.getName().equals(method)) {
                            Class<?>[] pts = m.getParameterTypes();
                            Object[] paramTypes = new Object[pts.length + 1];
                            System.arraycopy(pts, 0, paramTypes, 0, pts.length);
                            paramTypes[pts.length] = new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    replaceTypefaceForDrawText(pkg, param);
                                }
                            };
                            XposedHelpers.findAndHookMethod(className, cl, method, paramTypes);
                            hooked++;
                        }
                    }
                    FileLogger.i(TAG, "Hooked " + className + "." + method + " (" + hooked + " overloads) for " + pkg);
                } catch (Throwable t) {
                    FileLogger.e(TAG, "Failed to hook " + className + "." + method + " for " + pkg, t);
                }
            }
        }
    }

    private void replaceTypefaceForDrawText(String pkg, XC_MethodHook.MethodHookParam param) {
        if (applying.get()) return;
        try {
            Paint paint = null;
            CharSequence text = null;
            for (Object arg : param.args) {
                if (arg instanceof Paint && paint == null) {
                    paint = (Paint) arg;
                } else if (arg instanceof CharSequence && text == null) {
                    text = (CharSequence) arg;
                } else if (arg instanceof String && text == null) {
                    text = (String) arg;
                }
            }
            if (paint == null || TextUtils.isEmpty(text)) {
                return;
            }
            Typeface desired = FontLoader.getTypefaceForText(text);
            if (desired != null && desired != paint.getTypeface()) {
                applying.set(true);
                try {
                    paint.setTypeface(desired);
                    FileLogger.i(TAG, "Canvas drawText replaced pkg=" + pkg + " text=[" + text + "] -> " + FontLoader.getTypefaceName(desired));
                } finally {
                    applying.set(false);
                }
            }
        } catch (Throwable t) {
            FileLogger.e(TAG, "replaceTypefaceForDrawText failed for " + pkg, t);
        }
    }

    /**
     * Ele.me caches extracted/downloaded font files in its private directories and
     * reopens them with FileInputStream on warm starts, bypassing Typeface creation
     * hooks. Redirect those file opens to our replacement font files.
     */
    private void hookFileInputStreamForFonts(String pkg, ClassLoader cl) {
        if (!"me.ele".equals(pkg)) return;
        try {
            Class<?> fisClass = XposedHelpers.findClass("java.io.FileInputStream", cl);
            Constructor<?> ctor = fisClass.getDeclaredConstructor(File.class);
            hookConstructor(ctor, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    redirectFileArg(pkg, param, 0);
                }
            });
            FileLogger.i(TAG, "FileInputStream(File) hooked for " + pkg);
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to hook FileInputStream(File) for " + pkg, t);
        }
        try {
            Class<?> fisClass = XposedHelpers.findClass("java.io.FileInputStream", cl);
            Constructor<?> ctor = fisClass.getDeclaredConstructor(String.class);
            hookConstructor(ctor, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    redirectPathArg(pkg, param, 0);
                }
            });
            FileLogger.i(TAG, "FileInputStream(String) hooked for " + pkg);
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to hook FileInputStream(String) for " + pkg, t);
        }
    }

    private void redirectFileArg(String pkg, XC_MethodHook.MethodHookParam param, int index) {
        try {
            File file = (File) param.args[index];
            String replacement = chooseReplacementPath(pkg, file);
            if (replacement != null) {
                param.args[index] = new File(replacement);
                FileLogger.i(TAG, "FileInputStream redirected pkg=" + pkg + " " + file + " -> " + replacement);
            }
        } catch (Throwable t) {
            FileLogger.e(TAG, "redirectFileArg failed for " + pkg, t);
        }
    }

    private void redirectPathArg(String pkg, XC_MethodHook.MethodHookParam param, int index) {
        try {
            String path = (String) param.args[index];
            String replacement = chooseReplacementPath(pkg, new File(path));
            if (replacement != null) {
                param.args[index] = replacement;
                FileLogger.i(TAG, "FileInputStream redirected pkg=" + pkg + " " + path + " -> " + replacement);
            }
        } catch (Throwable t) {
            FileLogger.e(TAG, "redirectPathArg failed for " + pkg, t);
        }
    }

    private String chooseReplacementPath(String pkg, File file) {
        if (file == null) return null;
        String path = file.getAbsolutePath();
        // Only intercept font files inside the target app's own private dirs.
        if (!path.contains("/" + pkg + "/")) return null;
        String name = file.getName().toLowerCase();
        if (!name.endsWith(".ttf") && !name.endsWith(".otf") && !name.endsWith(".ttc")) return null;
        // Do not redirect our own replacement fonts (would recurse).
        File cjk = FontLoader.getCjkFontFile();
        File latin = FontLoader.getLatinFontFile();
        if (cjk != null && path.equals(cjk.getAbsolutePath())) return null;
        if (latin != null && path.equals(latin.getAbsolutePath())) return null;
        // For Ele.me everything is CJK; otherwise keep latin for non-CJK apps.
        File target = "me.ele".equals(pkg) ? cjk : latin;
        return target != null ? target.getAbsolutePath() : null;
    }

    private void applyFontToTextView(String pkg, Object obj) {
        if (applying.get()) return;
        try {
            TextView tv = (TextView) obj;
            CharSequence text = tv.getText();
            Typeface desired = FontLoader.getTypefaceForText(text);
            Typeface current = tv.getTypeface();
            FileLogger.i(TAG, "applyFontToTextView pkg=" + pkg + " text=[" + text + "] cjk=" + FontLoader.containsCJK(text)
                    + " desired=" + FontLoader.getTypefaceName(desired) + " current=" + FontLoader.getTypefaceName(current));
            if (desired != null && desired != current) {
                applying.set(true);
                try {
                    tv.setTypeface(desired);
                    FileLogger.i(TAG, "applyFontToTextView applied pkg=" + pkg + " text=[" + text + "] -> " + FontLoader.getTypefaceName(desired));
                } finally {
                    applying.set(false);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "applyFontToTextView failed", t);
        }
    }

    /**
     * Ele.me renders its home page with Weex texture mode. Weex caches Typeface objects
     * internally and applies them to TextPaint, so by the time Paint.setTypeface is hooked
     * the source is no longer traceable. Hook Weex's own font resolution to force our
     * replacement fonts before layout/rasterization.
     */
    private void hookWeexTextManager(String pkg, ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("com.taobao.android.weex_uikit.widget.text.TextManager", cl,
                    "applyFontStyle", TextPaint.class, String.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (applying.get()) return;
                            try {
                                TextPaint paint = (TextPaint) param.args[0];
                                String fontFamily = (String) param.args[1];
                                int style = (Integer) param.args[2];
                                Typeface desired = getWeexReplacementTypeface(pkg, fontFamily, style);
                                if (desired == null) return;
                                Typeface current = paint.getTypeface();
                                if (desired == current) return;
                                applying.set(true);
                                try {
                                    paint.setTypeface(desired);
                                    FileLogger.i(TAG, "Weex TextManager.applyFontStyle replaced pkg=" + pkg
                                            + " family=" + fontFamily + " style=" + style
                                            + " -> " + FontLoader.getTypefaceName(desired));
                                } finally {
                                    applying.set(false);
                                }
                            } catch (Throwable t) {
                                FileLogger.e(TAG, "Weex TextManager.applyFontStyle hook failed for " + pkg, t);
                            }
                        }
                    });
            FileLogger.i(TAG, "Weex TextManager.applyFontStyle hooked for " + pkg);
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to hook Weex TextManager.applyFontStyle for " + pkg, t);
        }
    }

    private void hookWeexTextStyleUtils(String pkg, ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("com.taobao.android.weex_uikit.widget.text.TextStyleUtils", cl,
                    "applyFontStyle", TextPaint.class, int.class, int.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (applying.get()) return;
                            try {
                                TextPaint paint = (TextPaint) param.args[0];
                                String fontFamily = (String) param.args[3];
                                int style = (Integer) param.args[1];
                                Typeface desired = getWeexReplacementTypeface(pkg, fontFamily, style);
                                if (desired == null) return;
                                Typeface current = paint.getTypeface();
                                if (desired == current) return;
                                applying.set(true);
                                try {
                                    paint.setTypeface(desired);
                                    FileLogger.i(TAG, "Weex TextStyleUtils.applyFontStyle replaced pkg=" + pkg
                                            + " family=" + fontFamily + " style=" + style
                                            + " -> " + FontLoader.getTypefaceName(desired));
                                } finally {
                                    applying.set(false);
                                }
                            } catch (Throwable t) {
                                FileLogger.e(TAG, "Weex TextStyleUtils.applyFontStyle hook failed for " + pkg, t);
                            }
                        }
                    });
            FileLogger.i(TAG, "Weex TextStyleUtils.applyFontStyle hooked for " + pkg);
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to hook Weex TextStyleUtils.applyFontStyle for " + pkg, t);
        }

        try {
            XposedHelpers.findAndHookMethod("com.taobao.android.weex_uikit.widget.text.TextStyleUtils", cl,
                    "getOrCreateTypeface", String.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                String fontFamily = (String) param.args[0];
                                int style = (Integer) param.args[1];
                                Typeface desired = getWeexReplacementTypeface(pkg, fontFamily, style);
                                if (desired != null) {
                                    Typeface result = (Typeface) param.getResult();
                                    if (desired != result) {
                                        param.setResult(desired);
                                        FileLogger.i(TAG, "Weex TextStyleUtils.getOrCreateTypeface replaced pkg=" + pkg
                                                + " family=" + fontFamily + " style=" + style
                                                + " -> " + FontLoader.getTypefaceName(desired));
                                    }
                                }
                            } catch (Throwable t) {
                                FileLogger.e(TAG, "Weex TextStyleUtils.getOrCreateTypeface hook failed for " + pkg, t);
                            }
                        }
                    });
            FileLogger.i(TAG, "Weex TextStyleUtils.getOrCreateTypeface hooked for " + pkg);
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to hook Weex TextStyleUtils.getOrCreateTypeface for " + pkg, t);
        }

        // Hook both getFontTypeface overloads by matching parameter types.
        hookWeexGetFontTypeface(pkg, cl, false);
        hookWeexGetFontTypeface(pkg, cl, true);
    }

    private void hookWeexGetFontTypeface(final String pkg, ClassLoader cl, boolean withPaint) {
        try {
            Class<?> clazz = XposedHelpers.findClass("com.taobao.android.weex_uikit.widget.text.TextStyleUtils", cl);
            java.lang.reflect.Method target = null;
            for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
                if (!m.getName().equals("getFontTypeface")) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (withPaint) {
                    if (pts.length == 4
                            && pts[0].getName().equals("android.text.TextPaint")
                            && pts[1] == int.class
                            && pts[2] == int.class
                            && pts[3] == String.class) {
                        target = m;
                        break;
                    }
                } else {
                    if (pts.length == 3
                            && pts[0] == int.class
                            && pts[1] == int.class
                            && pts[2] == String.class) {
                        target = m;
                        break;
                    }
                }
            }
            if (target == null) {
                FileLogger.w(TAG, "Weex TextStyleUtils.getFontTypeface withPaint=" + withPaint + " not found for " + pkg);
                return;
            }
            final java.lang.reflect.Method finalTarget = target;
            Class<?>[] pts = target.getParameterTypes();
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        String fontFamily = null;
                        int style = 0;
                        Class<?>[] hookPts = finalTarget.getParameterTypes();
                        for (int i = 0; i < hookPts.length; i++) {
                            if (param.args[i] instanceof String) {
                                fontFamily = (String) param.args[i];
                            } else if (param.args[i] instanceof Integer) {
                                if (style == 0) style = (Integer) param.args[i];
                            }
                        }
                        Typeface desired = getWeexReplacementTypeface(pkg, fontFamily, style);
                        if (desired != null) {
                            Typeface result = (Typeface) param.getResult();
                            if (desired != result) {
                                param.setResult(desired);
                                FileLogger.i(TAG, "Weex TextStyleUtils.getFontTypeface withPaint=" + withPaint
                                        + " replaced pkg=" + pkg + " family=" + fontFamily + " style=" + style
                                        + " -> " + FontLoader.getTypefaceName(desired));
                            }
                        }
                    } catch (Throwable t) {
                        FileLogger.e(TAG, "Weex TextStyleUtils.getFontTypeface withPaint=" + withPaint + " hook failed for " + pkg, t);
                    }
                }
            };
            Object[] paramTypes = new Object[pts.length + 1];
            System.arraycopy(pts, 0, paramTypes, 0, pts.length);
            paramTypes[pts.length] = hook;
            XposedHelpers.findAndHookMethod(clazz, "getFontTypeface", paramTypes);
            FileLogger.i(TAG, "Weex TextStyleUtils.getFontTypeface withPaint=" + withPaint + " hooked for " + pkg);
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to hook Weex TextStyleUtils.getFontTypeface withPaint=" + withPaint + " for " + pkg, t);
        }
    }

    /**
     * Weex builds text layouts through TextLayoutBuilder. Hook setTypeface to steer the
     * default typeface, and hook build() so the TextPaint is replaced according to the
     * text before the Layout is created.
     */
    private void hookWeexTextLayoutBuilder(String pkg, ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("com.taobao.android.weex_uikit.widget.text.TextLayoutBuilder", cl,
                    "setTypeface", Typeface.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (applying.get()) return;
                            try {
                                Typeface original = (Typeface) param.args[0];
                                if (original == null) return;
                                Typeface desired = pickWeexTypeface(pkg, original);
                                if (desired != null && desired != original) {
                                    applying.set(true);
                                    try {
                                        param.args[0] = desired;
                                        FileLogger.i(TAG, "Weex TextLayoutBuilder.setTypeface replaced pkg=" + pkg
                                                + " original=" + FontLoader.getTypefaceName(original)
                                                + " -> " + FontLoader.getTypefaceName(desired));
                                    } finally {
                                        applying.set(false);
                                    }
                                }
                            } catch (Throwable t) {
                                FileLogger.e(TAG, "Weex TextLayoutBuilder.setTypeface hook failed for " + pkg, t);
                            }
                        }
                    });
            FileLogger.i(TAG, "Weex TextLayoutBuilder.setTypeface hooked for " + pkg);
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to hook Weex TextLayoutBuilder.setTypeface for " + pkg, t);
        }

        try {
            XposedHelpers.findAndHookMethod("com.taobao.android.weex_uikit.widget.text.TextLayoutBuilder", cl,
                    "build",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (applying.get()) return;
                            try {
                                Object builder = param.thisObject;
                                Object params = getField(builder, "mParams");
                                if (params == null) return;
                                CharSequence text = (CharSequence) getField(params, "text");
                                TextPaint paint = (TextPaint) getField(params, "paint");
                                if (paint == null || TextUtils.isEmpty(text)) return;
                                Typeface desired = FontLoader.getTypefaceForText(text);
                                if (desired != null && desired != paint.getTypeface()) {
                                    applying.set(true);
                                    try {
                                        paint.setTypeface(desired);
                                        FileLogger.i(TAG, "Weex TextLayoutBuilder.build paint replaced pkg=" + pkg
                                                + " text=[" + text + "] -> " + FontLoader.getTypefaceName(desired));
                                    } finally {
                                        applying.set(false);
                                    }
                                }
                            } catch (Throwable t) {
                                FileLogger.e(TAG, "Weex TextLayoutBuilder.build hook failed for " + pkg, t);
                            }
                        }
                    });
            FileLogger.i(TAG, "Weex TextLayoutBuilder.build hooked for " + pkg);
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to hook Weex TextLayoutBuilder.build for " + pkg, t);
        }
    }

    /**
     * TextDrawable holds the final Layout. Make sure its paint typeface is replaced both
     * when the layout is assigned and immediately before it is drawn.
     */
    private void hookWeexTextDrawable(String pkg, ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("com.taobao.android.weex_uikit.widget.text.TextDrawable", cl,
                    "setLayout", Layout.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (applying.get()) return;
                            try {
                                Layout layout = (Layout) param.args[0];
                                replaceTypefaceForLayout(pkg, layout, "TextDrawable.setLayout");
                            } catch (Throwable t) {
                                FileLogger.e(TAG, "Weex TextDrawable.setLayout hook failed for " + pkg, t);
                            }
                        }
                    });
            FileLogger.i(TAG, "Weex TextDrawable.setLayout hooked for " + pkg);
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to hook Weex TextDrawable.setLayout for " + pkg, t);
        }

        try {
            XposedHelpers.findAndHookMethod("com.taobao.android.weex_uikit.widget.text.TextDrawable", cl,
                    "onDraw", Canvas.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (applying.get()) return;
                            try {
                                Object drawable = param.thisObject;
                                Layout layout = (Layout) getField(drawable, "layout");
                                if (layout == null) return;
                                replaceTypefaceForLayout(pkg, layout, "TextDrawable.onDraw");
                            } catch (Throwable t) {
                                FileLogger.e(TAG, "Weex TextDrawable.onDraw hook failed for " + pkg, t);
                            }
                        }
                    });
            FileLogger.i(TAG, "Weex TextDrawable.onDraw hooked for " + pkg);
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to hook Weex TextDrawable.onDraw for " + pkg, t);
        }
    }

    private void replaceTypefaceForLayout(String pkg, Layout layout, String source) {
        if (layout == null) return;
        CharSequence text = layout.getText();
        if (TextUtils.isEmpty(text)) return;
        TextPaint paint = layout.getPaint();
        if (paint == null) return;
        Typeface desired = FontLoader.getTypefaceForText(text);
        if (desired != null && desired != paint.getTypeface()) {
            applying.set(true);
            try {
                paint.setTypeface(desired);
                FileLogger.i(TAG, "Weex " + source + " layout paint replaced pkg=" + pkg
                        + " text=[" + text + "] -> " + FontLoader.getTypefaceName(desired));
            } finally {
                applying.set(false);
            }
        }
    }

    /**
     * Ele.me ships several Weex builds. The exact package/name of text classes varies
     * between versions. Try to discover and hook the most common variants dynamically.
     */
    private void hookDynamicWeexClasses(String pkg, ClassLoader cl) {
        String[][] classMethodSignatures = {
                {"com.taobao.android.weex_uikit.widget.text.TextStyleUtils", "applyFontStyle"},
                {"com.taobao.android.weex_uikit.widget.text.TextStyleUtils", "getOrCreateTypeface"},
                {"com.taobao.android.weex_uikit.widget.text.TextManager", "applyFontStyle"},
                {"com.taobao.android.weex.adapter.DefaultWXFontAdapter", "getFontTypeface"},
                {"com.taobao.android.weex.adapter.IWXFontAdapter", "getFontTypeface"},
                {"com.taobao.android.weex.utils.WXResourceUtils", "getFontTypeface"},
                {"com.taobao.weex.adapter.DefaultWXFontAdapter", "getFontTypeface"},
                {"com.taobao.weex.adapter.IWXFontAdapter", "getFontTypeface"},
                {"com.taobao.weex.utils.WXResourceUtils", "getFontTypeface"},
                {"com.taobao.android.weex.dom.WXStyle", "getFontFamily"},
        };
        for (String[] cms : classMethodSignatures) {
            try {
                Class<?> clazz = XposedHelpers.findClass(cms[0], cl);
                int hooked = 0;
                for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
                    if (!m.getName().equals(cms[1])) continue;
                    Class<?>[] pts = m.getParameterTypes();
                    Object[] paramTypes = new Object[pts.length + 1];
                    System.arraycopy(pts, 0, paramTypes, 0, pts.length);
                    final boolean returnsTypeface = m.getReturnType() == Typeface.class;
                    paramTypes[pts.length] = new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                String fontFamily = null;
                                int style = 0;
                                for (Object arg : param.args) {
                                    if (arg instanceof String) fontFamily = (String) arg;
                                    else if (arg instanceof Integer && style == 0) style = (Integer) arg;
                                }
                                Typeface desired = getWeexReplacementTypeface(pkg, fontFamily, style);
                                if (desired == null) return;
                                if (returnsTypeface) {
                                    Typeface result = (Typeface) param.getResult();
                                    if (desired != result) {
                                        param.setResult(desired);
                                        FileLogger.i(TAG, "Dynamic Weex " + cms[0] + "." + cms[1]
                                                + " replaced pkg=" + pkg + " family=" + fontFamily
                                                + " style=" + style + " -> " + FontLoader.getTypefaceName(desired));
                                    }
                                } else {
                                    // Method returns void; we cannot replace the return value, but the
                                    // TextPaint argument (if present) can still be patched.
                                    for (Object arg : param.args) {
                                        if (arg instanceof TextPaint) {
                                            TextPaint paint = (TextPaint) arg;
                                            Typeface current = paint.getTypeface();
                                            if (desired != current) {
                                                paint.setTypeface(desired);
                                                FileLogger.i(TAG, "Dynamic Weex " + cms[0] + "." + cms[1]
                                                        + " paint replaced pkg=" + pkg + " family=" + fontFamily
                                                        + " style=" + style + " -> " + FontLoader.getTypefaceName(desired));
                                            }
                                            break;
                                        }
                                    }
                                }
                            } catch (Throwable t) {
                                FileLogger.e(TAG, "Dynamic Weex " + cms[0] + "." + cms[1] + " failed for " + pkg, t);
                            }
                        }
                    };
                    XposedHelpers.findAndHookMethod(clazz, cms[1], paramTypes);
                    hooked++;
                }
                if (hooked > 0) {
                    FileLogger.i(TAG, "Dynamic Weex " + cms[0] + "." + cms[1] + " hooked (" + hooked + " overloads) for " + pkg);
                }
            } catch (Throwable t) {
                FileLogger.w(TAG, "Dynamic Weex class not available: " + cms[0] + " for " + pkg);
            }
        }
    }

    /**
     * api-82.jar only exposes XposedHelpers.findAndHookMethod at compile time.
     * Constructors must be hooked through XposedBridge.hookMethod at runtime.
     */
    private void hookConstructor(Member constructor, XC_MethodHook callback) throws Exception {
        Class<?> xposedBridge = Class.forName("de.robv.android.xposed.XposedBridge");
        Method hookMethod = xposedBridge.getDeclaredMethod("hookMethod", Member.class, XC_MethodHook.class);
        hookMethod.invoke(null, constructor, callback);
    }

    private Typeface pickWeexTypeface(String pkg, Typeface original) {
        if (original == null) return null;
        String source = TypefaceSourceTracker.getSource(original);
        String classify = source != null ? TypefaceSourceTracker.classify(original) : "unknown";
        if ("cjk".equals(classify)) {
            return FontLoader.getCjkTypeface();
        }
        if ("english".equals(classify)) {
            return FontLoader.getEnglishTypeface();
        }
        // Chinese apps default to CJK unless the typeface is already our replacement.
        if ("me.ele".equals(pkg)) {
            Typeface cjk = FontLoader.getCjkTypeface();
            if (cjk != null && cjk != original) return cjk;
        }
        return null;
    }

    private Typeface getWeexReplacementTypeface(String pkg, String fontFamily, int style) {
        if (fontFamily == null) fontFamily = "";
        String classify = TypefaceSourceTracker.classifySource("weex-family:" + fontFamily);
        Typeface base;
        if ("cjk".equals(classify)) {
            base = FontLoader.getCjkTypeface();
        } else if ("english".equals(classify)) {
            base = FontLoader.getEnglishTypeface();
        } else {
            // For Chinese apps the default sans is almost always used for CJK content.
            base = "me.ele".equals(pkg) ? FontLoader.getCjkTypeface() : FontLoader.getEnglishTypeface();
        }
        if (base == null) return null;
        if (style == 0 || base.getStyle() == style) return base;
        applying.set(true);
        try {
            return Typeface.create(base, style);
        } finally {
            applying.set(false);
        }
    }
}

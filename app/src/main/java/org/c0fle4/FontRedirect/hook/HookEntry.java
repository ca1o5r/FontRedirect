package org.c0fle4.FontRedirect.hook;

import android.app.Application;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.widget.TextView;

import org.c0fle4.FontRedirect.log.FileLogger;

import java.io.File;
import java.io.IOException;
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
                WebViewInjector.hook(pkg, cl);
            }
        });
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
}

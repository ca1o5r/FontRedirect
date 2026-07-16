package org.c0fle4.FontRedirect.hook;

import android.content.res.AssetManager;
import android.graphics.Typeface;

import org.c0fle4.FontRedirect.log.FileLogger;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class TypefaceSourceTracker {

    private static final String TAG = "FontRedirect";

    // Typeface identity -> source descriptor (lowercase path/family hint)
    private static final Map<Integer, String> typefaceSources = new ConcurrentHashMap<>();

    // Known patterns that indicate a CJK or English font in target apps.
    private static final String[] CJK_HINTS = {
            "noto_sans_cjk", "notosanscjk", "sourcehansans", "sourcehanserif",
            "miui", "harmony", "opposans", "osans", "chinese"
    };
    private static final String[] ENG_HINTS = {
            "coming_soon", "roboto", "opensans", "montserrat", "lato", "english"
    };

    public static void hook(String pkg, ClassLoader cl) {
        hookCreateFromAsset(pkg, cl);
        hookCreateFromFile(pkg, cl);
        hookCreateFromFile2(pkg, cl);
        hookCreateFromResourcesFontFile(pkg, cl);
        hookCreateFromResources(pkg, cl);
        hookCreateTypeface(pkg, cl);
        hookResourcesGetFont(pkg, cl);
    }

    /**
     * Propagate the source descriptor from a parent Typeface to derived Typefaces.
     */
    public static void inherit(Typeface derived, Typeface parent) {
        if (derived == null || parent == null) return;
        String src = getSource(parent);
        if (src != null) {
            record(derived, "inherit:" + src);
        }
    }

    private static void hookCreateFromAsset(String pkg, ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.graphics.Typeface", cl, "createFromAsset",
                    AssetManager.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Typeface tf = (Typeface) param.getResult();
                            if (tf != null) {
                                String src = "asset:" + param.args[1];
                                record(tf, src);
                                FileLogger.i(TAG, "TypefaceSource asset " + src + " -> " + tf.hashCode());
                            }
                        }
                    });
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to hook Typeface.createFromAsset for " + pkg, t);
        }
    }

    private static void hookCreateFromFile(String pkg, ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.graphics.Typeface", cl, "createFromFile",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Typeface tf = (Typeface) param.getResult();
                            if (tf != null) {
                                String src = "file:" + param.args[0];
                                record(tf, src);
                                FileLogger.i(TAG, "TypefaceSource file " + src + " -> " + tf.hashCode());
                            }
                        }
                    });
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to hook Typeface.createFromFile(String) for " + pkg, t);
        }
    }

    private static void hookCreateFromFile2(String pkg, ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.graphics.Typeface", cl, "createFromFile",
                    File.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Typeface tf = (Typeface) param.getResult();
                            if (tf != null) {
                                File f = (File) param.args[0];
                                String src = "file:" + (f != null ? f.getAbsolutePath() : "null");
                                record(tf, src);
                                FileLogger.i(TAG, "TypefaceSource file2 " + src + " -> " + tf.hashCode());
                            }
                        }
                    });
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to hook Typeface.createFromFile(File) for " + pkg, t);
        }
    }

    private static void hookCreateFromResourcesFontFile(String pkg, ClassLoader cl) {
        // API 26+ internal method
        String[] candidates = {
                "createFromResourcesFontFile",
                "createFromResources",
        };
        for (String name : candidates) {
            try {
                Method m = findMethodBestMatch(name, cl);
                if (m == null) continue;
                Class<?>[] pts = m.getParameterTypes();
                Object[] paramTypes = new Object[pts.length + 1];
                System.arraycopy(pts, 0, paramTypes, 0, pts.length);
                paramTypes[pts.length] = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Typeface tf = (Typeface) param.getResult();
                        if (tf != null) {
                            StringBuilder sb = new StringBuilder();
                            sb.append("res:");
                            for (Object arg : param.args) {
                                if (arg instanceof AssetManager) continue;
                                if (arg == null) continue;
                                sb.append(arg.toString()).append("|");
                            }
                            record(tf, sb.toString());
                            FileLogger.i(TAG, "TypefaceSource " + name + " " + sb + " -> " + tf.hashCode());
                        }
                    }
                };
                XposedHelpers.findAndHookMethod("android.graphics.Typeface", cl, name, paramTypes);
                FileLogger.i(TAG, "Hooked Typeface." + name + " for " + pkg);
                break;
            } catch (Throwable t) {
                FileLogger.e(TAG, "Failed to hook Typeface." + name + " for " + pkg, t);
            }
        }
    }

    private static void hookCreateFromResources(String pkg, ClassLoader cl) {
        // Some ROMs use a different overload
        try {
            XposedHelpers.findAndHookMethod("android.graphics.Typeface", cl, "create",
                    String.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Typeface tf = (Typeface) param.getResult();
                            if (tf != null) {
                                String family = (String) param.args[0];
                                record(tf, "family:" + family);
                                FileLogger.i(TAG, "TypefaceSource create family:" + family + " -> " + tf.hashCode());
                            }
                        }
                    });
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to hook Typeface.create for " + pkg, t);
        }
    }

    private static void hookCreateTypeface(String pkg, ClassLoader cl) {
        // Propagate source tracking through Typeface.create(Typeface, int) and create(Typeface, int, boolean)
        String[] names = {"create"};
        Class<?>[][] signatures = {
                {Typeface.class, int.class},
                {Typeface.class, int.class, boolean.class}
        };
        for (String name : names) {
            for (Class<?>[] sig : signatures) {
                try {
                    Object[] paramTypes = new Object[sig.length + 1];
                    System.arraycopy(sig, 0, paramTypes, 0, sig.length);
                    paramTypes[sig.length] = new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Typeface tf = (Typeface) param.getResult();
                            Typeface family = (Typeface) param.args[0];
                            if (tf != null && family != null) {
                                String src = getSource(family);
                                if (src != null) {
                                    record(tf, "derive:" + src);
                                    FileLogger.i(TAG, "TypefaceSource derive " + src + " -> " + tf.hashCode());
                                }
                            }
                        }
                    };
                    XposedHelpers.findAndHookMethod("android.graphics.Typeface", cl, name, paramTypes);
                    FileLogger.i(TAG, "Hooked Typeface." + name + "(" + sigString(sig) + ") for " + pkg);
                } catch (Throwable ignored) {
                    // Signature may not exist on this API level
                }
            }
        }
    }

    private static String sigString(Class<?>[] sig) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> c : sig) {
            sb.append(c.getSimpleName()).append(",");
        }
        return sb.toString();
    }

    private static void hookResourcesGetFont(String pkg, ClassLoader cl) {
        // API 26+: Resources.getFont(int) -> Typeface
        try {
            XposedHelpers.findAndHookMethod("android.content.res.Resources", cl, "getFont",
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Typeface tf = (Typeface) param.getResult();
                            if (tf != null) {
                                String src = "resources-font:" + param.args[0];
                                record(tf, src);
                                FileLogger.i(TAG, "TypefaceSource " + src + " -> " + tf.hashCode());
                            }
                        }
                    });
            FileLogger.i(TAG, "Hooked Resources.getFont for " + pkg);
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to hook Resources.getFont for " + pkg, t);
        }
    }

    private static Method findMethodBestMatch(String name, ClassLoader cl) {
        try {
            Class<?> typefaceClass = XposedHelpers.findClass("android.graphics.Typeface", cl);
            for (Method m : typefaceClass.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    return m;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void record(Typeface tf, String source) {
        if (tf == null || source == null) return;
        typefaceSources.put(System.identityHashCode(tf), source.toLowerCase());
    }

    public static String getSource(Typeface tf) {
        if (tf == null) return null;
        return typefaceSources.get(System.identityHashCode(tf));
    }

    public static boolean isKnownCjk(Typeface tf) {
        String src = getSource(tf);
        if (src == null) return false;
        for (String hint : CJK_HINTS) {
            if (src.contains(hint)) return true;
        }
        return false;
    }

    public static boolean isKnownEnglish(Typeface tf) {
        String src = getSource(tf);
        if (src == null) return false;
        for (String hint : ENG_HINTS) {
            if (src.contains(hint)) return true;
        }
        return false;
    }

    public static String classify(Typeface tf) {
        if (isKnownCjk(tf)) return "cjk";
        if (isKnownEnglish(tf)) return "english";
        return "unknown";
    }
}

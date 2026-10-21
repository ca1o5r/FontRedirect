package org.c0fle4.FontRedirect.hook;

import android.content.res.AssetManager;
import android.content.res.Resources;
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

    // Font resource id -> resolved resource name (populated by Resources.getFont hook)
    private static final Map<Integer, String> resourceIdToName = new ConcurrentHashMap<>();

    // Prevents recursion when creating styled replacement Typefaces.
    private static final ThreadLocal<Boolean> replacing = ThreadLocal.withInitial(() -> false);

    // Known patterns that indicate a CJK or English font in target apps.
    // Ele.me uses Alibaba/ALSC/PingFang assets which are CJK fonts but not covered by
    // generic family names, so include their identifiers explicitly.
    private static final String[] CJK_HINTS = {
            "noto_sans_cjk", "notosanscjk", "sourcehansans", "sourcehanserif",
            "miui", "misans", "harmony", "opposans", "osans", "chinese",
            "alibaba", "alsc", "pingfang", "pingfangsc",
            "futu", "nn_font"
    };
    private static final String[] ENG_HINTS = {
            "coming_soon", "roboto", "opensans", "montserrat", "lato", "english",
            "core sans"
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
                                tryReplaceResult(param, src);
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
                                tryReplaceResult(param, src);
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
                                tryReplaceResult(param, src);
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
                            tryReplaceResult(param, sb.toString());
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
                                String src = "family:" + family;
                                record(tf, src);
                                FileLogger.i(TAG, "TypefaceSource create family:" + family + " -> " + tf.hashCode());
                                tryReplaceResult(param, src);
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
                                    String derivedSrc = buildDerivedSource(src);
                                    record(tf, derivedSrc);
                                    FileLogger.i(TAG, "TypefaceSource derive " + derivedSrc + " -> " + tf.hashCode());
                                    tryReplaceResult(param, derivedSrc);
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
                                int id = (Integer) param.args[0];
                                String name = null;
                                try {
                                    Resources res = (Resources) param.thisObject;
                                    name = res.getResourceName(id);
                                } catch (Throwable ignored) {
                                }
                                if (name != null) {
                                    resourceIdToName.put(id, name);
                                }
                                String src = name != null
                                        ? "resources-font:" + name + ":" + id
                                        : "resources-font:" + id;
                                record(tf, src);
                                FileLogger.i(TAG, "TypefaceSource " + src + " -> " + tf.hashCode());
                                tryReplaceResult(param, src);
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

    /**
     * True when a font source hint (asset path, file name, resource or family name)
     * denotes an italic face.
     */
    public static boolean isItalicSource(String source) {
        if (source == null) return false;
        String s = source.toLowerCase();
        return s.contains("italic") || s.contains("oblique");
    }

    private static Typeface replacementFor(Typeface original, String source) {
        if (original == null || source == null || replacing.get()) return null;
        String s = source.toLowerCase();
        boolean cjk = false;
        boolean english = false;
        for (String hint : CJK_HINTS) {
            if (s.contains(hint)) {
                cjk = true;
                break;
            }
        }
        if (!cjk) {
            for (String hint : ENG_HINTS) {
                if (s.contains(hint)) {
                    english = true;
                    break;
                }
            }
        }
        if (!cjk && !english) return null;
        int style = original.getStyle();
        Typeface base;
        if (cjk) {
            base = FontLoader.getCjkTypeface();
        } else if ((style & Typeface.ITALIC) != 0 || isItalicSource(s)) {
            // The italic face already carries the slant, so drop the ITALIC bit to
            // avoid faux-italic double slanting.
            base = FontLoader.getLatinItalicTypeface();
            style &= ~Typeface.ITALIC;
        } else {
            base = FontLoader.getEnglishTypeface();
        }
        if (base == null || base == original) return null;
        if (style == 0 || base.getStyle() == style) return base;
        // Guard against recursive hooks from Typeface.create.
        replacing.set(true);
        try {
            return Typeface.create(base, style);
        } finally {
            replacing.set(false);
        }
    }

    private static void tryReplaceResult(XC_MethodHook.MethodHookParam param, String source) {
        if (replacing.get()) return;
        Typeface original = (Typeface) param.getResult();
        Typeface repl = replacementFor(original, source);
        if (repl == null || repl == original) return;
        replacing.set(true);
        try {
            param.setResult(repl);
            record(repl, source);
            FileLogger.i(TAG, "TypefaceSource replaced at source " + source + " -> " + FontLoader.getTypefaceName(repl));
        } finally {
            replacing.set(false);
        }
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
        String src = getClassifiableSource(tf);
        if (src == null) return false;
        for (String hint : CJK_HINTS) {
            if (src.contains(hint)) return true;
        }
        return false;
    }

    public static boolean isKnownEnglish(Typeface tf) {
        String src = getClassifiableSource(tf);
        if (src == null) return false;
        for (String hint : ENG_HINTS) {
            if (src.contains(hint)) return true;
        }
        return false;
    }

    private static String getClassifiableSource(Typeface tf) {
        String src = getSource(tf);
        if (src == null) return null;
        if (src.contains("resources-font:")) {
            Integer id = parseResourceId(src);
            String name = id != null ? resourceIdToName.get(id) : null;
            if (name != null && !src.contains(name)) {
                return src + "|" + name;
            }
        }
        return src;
    }

    private static String buildDerivedSource(String src) {
        if (src == null) return null;
        if (src.startsWith("resources-font:")) {
            Integer id = parseResourceId(src);
            String name = id != null ? resourceIdToName.get(id) : null;
            if (name != null) {
                return "derive:resources-font:" + name + ":" + id;
            }
        }
        return "derive:" + src;
    }

    private static Integer parseResourceId(String src) {
        if (src == null) return null;
        int lastColon = src.lastIndexOf(':');
        if (lastColon < 0 || lastColon == src.length() - 1) return null;
        String num = src.substring(lastColon + 1);
        try {
            return Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String classify(Typeface tf) {
        if (isKnownCjk(tf)) return "cjk";
        if (isKnownEnglish(tf)) return "english";
        return "unknown";
    }
}

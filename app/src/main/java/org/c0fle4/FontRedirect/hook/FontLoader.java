package org.c0fle4.FontRedirect.hook;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.system.Os;
import android.system.StructStat;

import org.c0fle4.FontRedirect.log.FileLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class FontLoader {
    private static final String TAG = "FontRedirect";
    private static final String SYSTEM_FONT_DIR = "/system/fonts/";
    /** Regular text that is not CJK. */
    public static final String LATIN = "Latin.ttf";
    /** Italic text that is not CJK. */
    public static final String LATIN_ITALIC = "LatinItalic.ttf";
    /** Chinese / Japanese / Korean text. */
    public static final String CJK = "CJK.ttf";

    private static Typeface latinTypeface;
    private static Typeface latinItalicTypeface;
    private static Typeface cjkTypeface;
    private static File latinFile;
    private static File latinItalicFile;
    private static File cjkFile;
    private static String latinName = "latin";
    private static String latinItalicName = "latin-italic";
    private static String cjkName = "cjk";
    private static boolean staleExtractsCleaned;

    public static synchronized void init(Context moduleContext, Context targetContext) {
        if (latinTypeface != null && cjkTypeface != null && latinItalicTypeface != null) {
            FileLogger.i(TAG, "Fonts already loaded");
            return;
        }
        cleanupStaleExtracts(targetContext);
        if (loadSystemFonts()) {
            // /etc/fonts.xml only exposes a regular Latin face, so the italic face
            // always comes from the module's own assets.
            loadEmbeddedItalic(moduleContext, targetContext);
            return;
        }
        loadEmbeddedFonts(moduleContext, targetContext);
    }

    public static synchronized void init(String moduleApkPath, Context targetContext) {
        if (latinTypeface != null && cjkTypeface != null && latinItalicTypeface != null) {
            FileLogger.i(TAG, "Fonts already loaded");
            return;
        }
        cleanupStaleExtracts(targetContext);
        // This entry point is only used when createPackageContext() failed, which
        // typically happens for Magisk DenyList apps running in an isolated mount
        // namespace. In that namespace /system/fonts is the stock firmware, not the
        // Magisk-module replacement, so parsing /etc/fonts.xml would load the wrong
        // fonts. Always use the module's own embedded fonts here.
        loadEmbeddedFonts(moduleApkPath, targetContext);
    }

    private static boolean loadSystemFonts() {
        try {
            SystemFontConfig.Config cfg = SystemFontConfig.load();
            if (cfg != null && cfg.latin != null && cfg.latin.typeface != null
                    && cfg.cjk != null && cfg.cjk.typeface != null) {
                // Magisk DenyList unmounts module overlays, so /system/fonts becomes stock
                // firmware again. Detect this by comparing the device of the selected CJK font
                // file with the device of /system/fonts/. If they are the same, the file was
                // not replaced by a module and we should fall back to the embedded fonts.
                if (!isFontReplacedByModule(cfg.cjk.file)) {
                    FileLogger.i(TAG, "CJK system font not replaced (DenyList or no module), falling back to embedded fonts: " + cfg.cjk.file);
                    return false;
                }
                latinTypeface = cfg.latin.typeface;
                cjkTypeface = cfg.cjk.typeface;
                latinFile = cfg.latin.file;
                cjkFile = cfg.cjk.file;
                latinName = cfg.latin.getFileName();
                cjkName = cfg.cjk.getFileName();
                FileLogger.i(TAG, "Fonts loaded from system config: latin=" + latinFile + " cjk=" + cjkFile);
                return true;
            }
        } catch (Throwable t) {
            FileLogger.e(TAG, "System font config load failed, will fallback", t);
        }
        return false;
    }

    private static boolean isFontReplacedByModule(File fontFile) {
        if (fontFile == null) {
            return false;
        }
        try {
            StructStat fileStat = Os.stat(fontFile.getAbsolutePath());
            StructStat dirStat = Os.stat(SYSTEM_FONT_DIR);
            boolean replaced = fileStat.st_dev != dirStat.st_dev;
            FileLogger.i(TAG, "isFontReplacedByModule " + fontFile + " fileDev=" + fileStat.st_dev + " dirDev=" + dirStat.st_dev + " replaced=" + replaced);
            return replaced;
        } catch (Throwable t) {
            FileLogger.e(TAG, "isFontReplacedByModule failed for " + fontFile, t);
            return false;
        }
    }

    private static void loadEmbeddedFonts(Context moduleContext, Context targetContext) {
        try {
            File latin = extractAsset(moduleContext, targetContext, LATIN);
            File latinItalic = extractAsset(moduleContext, targetContext, LATIN_ITALIC);
            File cjk = extractAsset(moduleContext, targetContext, CJK);
            latinFile = latin;
            latinItalicFile = latinItalic;
            cjkFile = cjk;
            latinTypeface = Typeface.createFromFile(latin);
            latinItalicTypeface = Typeface.createFromFile(latinItalic);
            cjkTypeface = Typeface.createFromFile(cjk);
            latinName = "Latin";
            latinItalicName = "LatinItalic";
            cjkName = "CJK";
            FileLogger.i(TAG, "Fonts loaded from embedded assets: latin=" + latinFile
                    + ", latinItalic=" + latinItalicFile + ", cjk=" + cjkFile);
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to load embedded fonts", t);
        }
    }

    /**
     * Loads only the italic Latin face from the module assets. Called when the Latin
     * and CJK faces come from the system font config, which has no italic variant of
     * the replacement font.
     */
    private static void loadEmbeddedItalic(Context moduleContext, Context targetContext) {
        if (latinItalicTypeface != null) {
            return;
        }
        try {
            File latinItalic = extractAsset(moduleContext, targetContext, LATIN_ITALIC);
            latinItalicFile = latinItalic;
            latinItalicTypeface = Typeface.createFromFile(latinItalic);
            latinItalicName = "LatinItalic";
            FileLogger.i(TAG, "Italic font loaded from embedded assets: " + latinItalicFile);
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to load embedded italic font", t);
        }
    }

    /**
     * Releases before 3.8.5 shipped Roboto-Regular.ttf / OSans-RC-Regular.ttf and
     * extracted them into the target app's files dir. Drop those copies once so an
     * upgrade does not keep ~33 MB of unused fonts per target app.
     */
    private static void cleanupStaleExtracts(Context targetContext) {
        if (staleExtractsCleaned || targetContext == null) {
            return;
        }
        staleExtractsCleaned = true;
        String[] stale = {"font_redirect_Roboto-Regular.ttf", "font_redirect_OSans-RC-Regular.ttf"};
        try {
            File dir = targetContext.getFilesDir();
            for (String name : stale) {
                File f = new File(dir, name);
                if (f.exists() && f.delete()) {
                    FileLogger.i(TAG, "Removed stale embedded font " + f);
                }
            }
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to remove stale embedded fonts", t);
        }
    }

    private static void loadEmbeddedFonts(String moduleApkPath, Context targetContext) {
        try {
            File latin = extractAsset(moduleApkPath, targetContext, LATIN);
            File latinItalic = extractAsset(moduleApkPath, targetContext, LATIN_ITALIC);
            File cjk = extractAsset(moduleApkPath, targetContext, CJK);
            latinFile = latin;
            latinItalicFile = latinItalic;
            cjkFile = cjk;
            latinTypeface = Typeface.createFromFile(latin);
            latinItalicTypeface = Typeface.createFromFile(latinItalic);
            cjkTypeface = Typeface.createFromFile(cjk);
            latinName = "Latin";
            latinItalicName = "LatinItalic";
            cjkName = "CJK";
            FileLogger.i(TAG, "Fonts loaded from embedded assets via apk path: latin=" + latinFile
                    + ", latinItalic=" + latinItalicFile + ", cjk=" + cjkFile);
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to load embedded fonts from apk path", t);
        }
    }

    private static File extractAsset(Context moduleContext, Context targetContext, String name) throws IOException {
        File out = new File(targetContext.getFilesDir(), "font_redirect_" + name);
        FileLogger.i(TAG, "extractAsset target=" + out.getAbsolutePath());
        if (out.exists() && out.length() > 0) {
            FileLogger.i(TAG, "extractAsset reusing existing " + name);
            return out;
        }
        AssetManager am = moduleContext.getAssets();
        try (InputStream is = am.open(name);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) {
                fos.write(buf, 0, len);
            }
        }
        out.setReadable(true, false);
        FileLogger.i(TAG, "extractAsset wrote " + name + " (" + out.length() + " bytes)");
        return out;
    }

    private static File extractAsset(String moduleApkPath, Context targetContext, String name) throws IOException {
        File out = new File(targetContext.getFilesDir(), "font_redirect_" + name);
        FileLogger.i(TAG, "extractAsset target=" + out.getAbsolutePath());
        if (out.exists() && out.length() > 0) {
            FileLogger.i(TAG, "extractAsset reusing existing " + name);
            return out;
        }
        try (ZipFile zf = new ZipFile(moduleApkPath)) {
            ZipEntry entry = zf.getEntry("assets/" + name);
            if (entry == null) {
                throw new IOException("assets/" + name + " not found in " + moduleApkPath);
            }
            try (InputStream is = zf.getInputStream(entry);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) {
                    fos.write(buf, 0, len);
                }
            }
        }
        out.setReadable(true, false);
        FileLogger.i(TAG, "extractAsset wrote " + name + " (" + out.length() + " bytes)");
        return out;
    }

    public static Typeface getTypefaceForText(CharSequence text) {
        return getTypefaceForText(text, false);
    }

    public static Typeface getTypefaceForText(CharSequence text, boolean italic) {
        if (text == null || text.length() == 0) {
            return null;
        }
        if (containsCJK(text)) {
            return cjkTypeface;
        }
        Typeface face = italic ? latinItalicTypeface : latinTypeface;
        // Fall back to the regular face when the italic asset could not be loaded.
        return face != null ? face : latinTypeface;
    }

    public static Typeface getCjkTypeface() {
        return cjkTypeface;
    }

    public static Typeface getEnglishTypeface() {
        return latinTypeface;
    }

    public static Typeface getLatinItalicTypeface() {
        return latinItalicTypeface;
    }

    /**
     * True when the Typeface is explicitly styled italic or is the module's own
     * italic replacement face. Typeface.createFromFile() reports style NORMAL for
     * italic font files, so identity is checked as well.
     */
    public static boolean isItalicTypeface(Typeface tf) {
        if (tf == null) {
            return false;
        }
        if ((tf.getStyle() & Typeface.ITALIC) != 0) {
            return true;
        }
        return tf == latinItalicTypeface;
    }

    public static File getLatinFontFile() {
        return latinFile;
    }

    public static File getLatinItalicFontFile() {
        return latinItalicFile;
    }

    public static File getCjkFontFile() {
        return cjkFile;
    }

    public static String getTypefaceName(Typeface tf) {
        if (tf == null) return "null";
        if (tf == latinTypeface) return latinName;
        if (tf == latinItalicTypeface) return latinItalicName;
        if (tf == cjkTypeface) return cjkName;
        return "other(" + tf.hashCode() + ")";
    }

    public static boolean containsCJK(CharSequence text) {
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            if (isCJK(cp)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean isCJK(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) ||
               (cp >= 0x3040 && cp <= 0x309F) ||
               (cp >= 0x30A0 && cp <= 0x30FF) ||
               (cp >= 0xAC00 && cp <= 0xD7AF) ||
               (cp >= 0x3100 && cp <= 0x312F) ||
               (cp >= 0x31F0 && cp <= 0x31FF) ||
               (cp >= 0xFF00 && cp <= 0xFFEF);
    }
}

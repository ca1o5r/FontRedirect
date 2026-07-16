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
    public static final String ROBOTO = "Roboto-Regular.ttf";
    public static final String OSANS_RC = "OSans-RC-Regular.ttf";

    private static Typeface latinTypeface;
    private static Typeface cjkTypeface;
    private static File latinFile;
    private static File cjkFile;
    private static String latinName = "latin";
    private static String cjkName = "cjk";

    public static synchronized void init(Context moduleContext, Context targetContext) {
        if (latinTypeface != null && cjkTypeface != null) {
            FileLogger.i(TAG, "Fonts already loaded");
            return;
        }
        if (loadSystemFonts()) {
            return;
        }
        loadEmbeddedFonts(moduleContext, targetContext);
    }

    public static synchronized void init(String moduleApkPath, Context targetContext) {
        if (latinTypeface != null && cjkTypeface != null) {
            FileLogger.i(TAG, "Fonts already loaded");
            return;
        }
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
            File robotoFile = extractAsset(moduleContext, targetContext, ROBOTO);
            File osansRcFile = extractAsset(moduleContext, targetContext, OSANS_RC);
            latinFile = robotoFile;
            cjkFile = osansRcFile;
            latinTypeface = Typeface.createFromFile(robotoFile);
            cjkTypeface = Typeface.createFromFile(osansRcFile);
            latinName = "Roboto";
            cjkName = "OSans-RC";
            FileLogger.i(TAG, "Fonts loaded from embedded assets: roboto=" + latinFile + ", osans=" + cjkFile);
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to load embedded fonts", t);
        }
    }

    private static void loadEmbeddedFonts(String moduleApkPath, Context targetContext) {
        try {
            File robotoFile = extractAsset(moduleApkPath, targetContext, ROBOTO);
            File osansRcFile = extractAsset(moduleApkPath, targetContext, OSANS_RC);
            latinFile = robotoFile;
            cjkFile = osansRcFile;
            latinTypeface = Typeface.createFromFile(robotoFile);
            cjkTypeface = Typeface.createFromFile(osansRcFile);
            latinName = "Roboto";
            cjkName = "OSans-RC";
            FileLogger.i(TAG, "Fonts loaded from embedded assets via apk path: roboto=" + latinFile + ", osans=" + cjkFile);
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
        if (text == null || text.length() == 0) {
            return null;
        }
        return containsCJK(text) ? cjkTypeface : latinTypeface;
    }

    public static Typeface getCjkTypeface() {
        return cjkTypeface;
    }

    public static Typeface getEnglishTypeface() {
        return latinTypeface;
    }

    public static File getLatinFontFile() {
        return latinFile;
    }

    public static File getCjkFontFile() {
        return cjkFile;
    }

    public static String getTypefaceName(Typeface tf) {
        if (tf == null) return "null";
        if (tf == latinTypeface) return latinName;
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

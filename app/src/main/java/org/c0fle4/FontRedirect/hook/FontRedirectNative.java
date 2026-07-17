package org.c0fle4.FontRedirect.hook;

import org.c0fle4.FontRedirect.log.FileLogger;

public class FontRedirectNative {
    private static final String TAG = "FontRedirect";
    private static boolean loaded = false;

    static {
        try {
            System.loadLibrary("fontredirect");
            loaded = true;
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to load libfontredirect", t);
        }
    }

    public static boolean isAvailable() {
        return loaded;
    }

    public static native boolean hookFlutter(String latinFontPath, String cjkFontPath);
}

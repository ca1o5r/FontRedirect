package org.c0fle4.FontRedirect.hook;

import android.content.Context;

import org.c0fle4.FontRedirect.log.FileLogger;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Clears font-related caches for target apps on every cold start.
 *
 * Ele.me's Weex/Unicorn engine caches downloaded font files, compiled bundles and
 * rendered glyph textures under the app's cache directory. Once those caches are
 * built with the original font, subsequent launches render from the cache and the
 * module's hooks never see the original font load again. Clearing only the cache
 * (not application data) forces Weex to rebuild its text cache through the hooked
 * font paths, while keeping the user logged in.
 */
public class CacheCleaner {

    private static final String TAG = "FontRedirect";

    // Directory/file name fragments that are strongly associated with cached fonts,
    // Weex bundles or glyph textures. We only delete entries whose names contain
    // one of these fragments to avoid wiping login/session state.
    private static final List<String> FONT_CACHE_FRAGMENTS = Arrays.asList(
            "font", "fonts", "typeface", "glyph", "weex", "unicorn", "bundle",
            "uik_iconfont", "alsc", "alibaba", "pingfang", "futu", "nn_font"
    );

    /**
     * Delete font/Weex-related cache entries for the current app. Safe to call on
     * every Application.onCreate; it only touches cache directories, not shared
     * preferences or databases.
     */
    public static void cleanFontCaches(Context context) {
        if (context == null) return;
        try {
            File cacheDir = context.getCacheDir();
            if (cacheDir != null && cacheDir.exists()) {
                int n = deleteMatching(cacheDir, FONT_CACHE_FRAGMENTS);
                FileLogger.i(TAG, "CacheCleaner removed " + n + " font/Weex entries from " + cacheDir);
            }
            File codeCache = context.getCodeCacheDir();
            if (codeCache != null && codeCache.exists()) {
                int n = deleteMatching(codeCache, FONT_CACHE_FRAGMENTS);
                FileLogger.i(TAG, "CacheCleaner removed " + n + " font/Weex entries from " + codeCache);
            }
            File filesDir = context.getFilesDir();
            if (filesDir != null && filesDir.exists()) {
                int n = deleteMatching(filesDir, FONT_CACHE_FRAGMENTS);
                FileLogger.i(TAG, "CacheCleaner removed " + n + " font/Weex entries from " + filesDir);
            }
            File extCache = context.getExternalCacheDir();
            if (extCache != null && extCache.exists()) {
                int n = deleteMatching(extCache, FONT_CACHE_FRAGMENTS);
                FileLogger.i(TAG, "CacheCleaner removed " + n + " font/Weex entries from " + extCache);
            }
        } catch (Throwable t) {
            FileLogger.e(TAG, "CacheCleaner failed", t);
        }
    }

    private static int deleteMatching(File dir, List<String> fragments) {
        int removed = 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        for (File child : children) {
            String name = child.getName().toLowerCase();
            boolean matches = false;
            for (String frag : fragments) {
                if (name.contains(frag)) {
                    matches = true;
                    break;
                }
            }
            if (matches) {
                if (deleteRecursive(child)) {
                    removed++;
                }
            } else if (child.isDirectory()) {
                removed += deleteMatching(child, fragments);
            }
        }
        return removed;
    }

    private static boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        return file.delete();
    }
}

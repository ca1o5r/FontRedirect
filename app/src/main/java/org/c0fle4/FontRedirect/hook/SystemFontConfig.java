package org.c0fle4.FontRedirect.hook;

import android.graphics.Typeface;
import android.os.Build;
import android.util.Xml;

import org.c0fle4.FontRedirect.log.FileLogger;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;

public class SystemFontConfig {
    private static final String TAG = "FontRedirect";
    private static final String FONTS_XML = "/etc/fonts.xml";
    private static final String SYSTEM_FONT_DIR = "/system/fonts/";

    public static class FontEntry {
        public final File file;
        public final int ttcIndex;
        public final int weight;
        public final String style;
        public Typeface typeface;

        FontEntry(File file, int ttcIndex, int weight, String style) {
            this.file = file;
            this.ttcIndex = ttcIndex;
            this.weight = weight;
            this.style = style;
        }

        public String getFileName() {
            return file != null ? file.getName() : "null";
        }
    }

    public static class Config {
        public FontEntry latin;
        public FontEntry cjk;
    }

    public static Config load() {
        File xml = new File(FONTS_XML);
        if (!xml.exists()) {
            FileLogger.w(TAG, "System fonts.xml not found at " + FONTS_XML);
            return null;
        }
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(xml);
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, "UTF-8");
            Config config = new Config();
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "family".equals(parser.getName())) {
                    String name = parser.getAttributeValue(null, "name");
                    String lang = parser.getAttributeValue(null, "lang");
                    FontEntry entry = parseFamily(parser);
                    if (entry != null) {
                        if (config.latin == null && "sans-serif".equals(name)) {
                            config.latin = entry;
                            FileLogger.i(TAG, "SystemFontConfig latin sans-serif -> " + entry.file);
                        }
                        if (config.cjk == null && isCjkLanguage(lang)) {
                            config.cjk = entry;
                            FileLogger.i(TAG, "SystemFontConfig cjk lang=" + lang + " -> " + entry.file);
                        }
                    }
                }
                event = parser.next();
            }
            if (config.latin == null) {
                FileLogger.w(TAG, "SystemFontConfig no sans-serif family found");
            }
            if (config.cjk == null) {
                FileLogger.w(TAG, "SystemFontConfig no CJK family found, falling back to latin");
                config.cjk = config.latin;
            }
            createTypefaces(config);
            return config;
        } catch (Throwable t) {
            FileLogger.e(TAG, "SystemFontConfig load failed", t);
            return null;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static FontEntry parseFamily(XmlPullParser parser) throws Exception {
        int familyDepth = parser.getDepth();
        int event = parser.next();
        while (!(event == XmlPullParser.END_TAG && "family".equals(parser.getName()) && parser.getDepth() == familyDepth)) {
            if (event == XmlPullParser.START_TAG && "font".equals(parser.getName())) {
                int weight = parseInt(parser.getAttributeValue(null, "weight"), 400);
                String style = parser.getAttributeValue(null, "style");
                if (style == null) style = "normal";
                int index = parseInt(parser.getAttributeValue(null, "index"), 0);
                String fileName = collectFontText(parser).trim();
                if (!fileName.isEmpty() && weight == 400 && "normal".equals(style)) {
                    File file = new File(SYSTEM_FONT_DIR, fileName);
                    return new FontEntry(file, index, weight, style);
                }
            }
            event = parser.next();
        }
        return null;
    }

    private static String collectFontText(XmlPullParser parser) throws Exception {
        int fontDepth = parser.getDepth();
        StringBuilder sb = new StringBuilder();
        int event = parser.next();
        while (!(event == XmlPullParser.END_TAG && "font".equals(parser.getName()) && parser.getDepth() == fontDepth)) {
            if (event == XmlPullParser.TEXT) {
                sb.append(parser.getText());
            }
            event = parser.next();
        }
        return sb.toString();
    }

    private static boolean isCjkLanguage(String lang) {
        if (lang == null || lang.isEmpty()) return false;
        for (String token : lang.split(",")) {
            String primary = token.trim().split("-")[0].toLowerCase();
            if (primary.equals("zh") || primary.equals("ja") || primary.equals("ko")) {
                return true;
            }
        }
        return false;
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static void createTypefaces(Config config) {
        if (config.latin != null) {
            config.latin.typeface = createTypeface(config.latin);
        }
        if (config.cjk != null) {
            config.cjk.typeface = createTypeface(config.cjk);
        }
    }

    private static Typeface createTypeface(FontEntry entry) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && entry.ttcIndex > 0) {
                Typeface tf = new Typeface.Builder(entry.file)
                        .setTtcIndex(entry.ttcIndex)
                        .build();
                if (tf != null) {
                    FileLogger.i(TAG, "SystemFontConfig loaded typeface " + entry.file + " ttcIndex=" + entry.ttcIndex);
                    return tf;
                }
            }
            Typeface tf = Typeface.createFromFile(entry.file);
            FileLogger.i(TAG, "SystemFontConfig loaded typeface " + entry.file);
            return tf;
        } catch (Throwable t) {
            FileLogger.e(TAG, "SystemFontConfig createTypeface failed for " + entry.file, t);
            return null;
        }
    }
}

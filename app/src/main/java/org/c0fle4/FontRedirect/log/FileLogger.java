package org.c0fle4.FontRedirect.log;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FileLogger {

    private static final Object LOCK = new Object();
    private static final String MAIN_TAG = "FontRedirect";
    private static final long MAX_SIZE = 2 * 1024 * 1024;
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private static File logFile;

    public static void init(Context context) {
        File target = null;
        if (context != null) {
            File ext = context.getExternalFilesDir(null);
            if (ext != null) {
                File f = new File(ext, "Logs/FontRedirect.log");
                if (ensureWritable(f)) {
                    target = f;
                }
            }
        }

        logFile = target;
        if (logFile != null) {
            rotateIfNeeded(logFile);
            i("FileLogger", "log file initialized: " + logFile.getAbsolutePath());
        } else {
            Log.e(MAIN_TAG, "[FileLogger] failed to initialize any log file");
        }
    }

    private static boolean ensureWritable(File f) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (!f.exists()) {
                f.createNewFile();
            }
            return f.canWrite();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void rotateIfNeeded(File f) {
        if (f.length() > MAX_SIZE) {
            File bak = new File(f.getAbsolutePath() + ".bak");
            f.renameTo(bak);
        }
    }

    public static File getLogFile() {
        return logFile;
    }

    public static void d(String sub, String msg) {
        Log.d(MAIN_TAG, "[" + sub + "] " + msg);
        write("D", sub, msg, null);
    }

    public static void i(String sub, String msg) {
        Log.i(MAIN_TAG, "[" + sub + "] " + msg);
        write("I", sub, msg, null);
    }

    public static void w(String sub, String msg) {
        Log.w(MAIN_TAG, "[" + sub + "] " + msg);
        write("W", sub, msg, null);
    }

    public static void w(String sub, String msg, Throwable t) {
        Log.w(MAIN_TAG, "[" + sub + "] " + msg, t);
        write("W", sub, msg, t);
    }

    public static void e(String sub, String msg, Throwable t) {
        Log.e(MAIN_TAG, "[" + sub + "] " + msg, t);
        write("E", sub, msg, t);
    }

    private static void write(String level, String sub, String msg, Throwable t) {
        if (logFile == null) {
            return;
        }
        synchronized (LOCK) {
            try (PrintWriter pw = new PrintWriter(new FileWriter(logFile, true))) {
                String time = TIME_FMT.format(new Date());
                pw.println(time + " [" + level + "/" + sub + "] " + msg);
                if (t != null) {
                    t.printStackTrace(pw);
                }
            } catch (IOException e) {
                Log.e(MAIN_TAG, "[FileLogger] write failed", e);
            }
        }
    }
}

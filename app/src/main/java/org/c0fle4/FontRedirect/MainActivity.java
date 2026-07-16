package org.c0fle4.FontRedirect;

import android.content.ContentValues;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.c0fle4.FontRedirect.log.FileLogger;

import com.google.android.material.appbar.MaterialToolbar;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        FileLogger.init(this);

        TextView versionView = findViewById(R.id.version_text);
        versionView.setText(getString(R.string.version_label, getVersionName()));
    }

    private String getVersionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_export_log) {
            exportLog();
            return true;
        }
        if (item.getItemId() == R.id.action_export_lsposed_log) {
            exportLsposedLog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void exportLog() {
        File src = FileLogger.getLogFile();
        if (src == null || !src.exists()) {
            File ext = getExternalFilesDir(null);
            if (ext != null) {
                src = new File(ext, "Logs/FontRedirect.log");
            }
        }
        if (src == null || !src.exists()) {
            Toast.makeText(this, "日志文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "FontRedirect_" + timeStamp + ".log";

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    throw new IOException("MediaStore insert returned null");
                }
                try (FileInputStream fis = new FileInputStream(src);
                     java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                    copyStream(fis, os);
                }
            } else {
                File out = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName);
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                try (FileInputStream fis = new FileInputStream(src);
                     FileOutputStream fos = new FileOutputStream(out)) {
                    copyStream(fis, fos);
                }
            }
            Toast.makeText(this, "日志已导出到 Downloads: " + fileName, Toast.LENGTH_LONG).show();
            FileLogger.i("MainActivity", "exported log to Downloads/" + fileName);
        } catch (Exception e) {
            Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            FileLogger.e("MainActivity", "export log failed", e);
        }
    }

    private void exportLsposedLog() {
        new LsposedLogExportTask(this).execute();
    }

    private static class LsposedLogExportTask extends AsyncTask<Void, Void, Boolean> {
        private final WeakReference<MainActivity> ref;
        private String fileName;
        private String errorMsg;

        LsposedLogExportTask(MainActivity activity) {
            this.ref = new WeakReference<>(activity);
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            MainActivity activity = ref.get();
            if (activity == null) return false;

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            fileName = "LSPosed_FontRedirect_" + timeStamp + ".log";

            StringBuilder sb = new StringBuilder();
            sb.append("===== FontRedirect LSPosed log export =====\n");
            sb.append("Time: ").append(timeStamp).append("\n");
            sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
            sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n\n");

            boolean gotRoot = appendRootLogs(sb, activity);
            if (!gotRoot) {
                appendLogcatLogs(sb);
            }

            if (sb.length() == 0) {
                errorMsg = "没有收集到任何日志";
                return false;
            }

            return writeStringToDownloads(activity, fileName, sb.toString());
        }

        private boolean appendRootLogs(StringBuilder sb, MainActivity activity) {
            Process proc = null;
            try {
                proc = Runtime.getRuntime().exec("su");
                java.io.DataOutputStream os = new java.io.DataOutputStream(proc.getOutputStream());
                os.writeBytes("ls /data/adb/lspd/log/ 2>/dev/null\n");
                os.writeBytes("exit\n");
                os.flush();

                String listOutput = readStream(proc.getInputStream());
                drainStream(proc.getErrorStream());
                int code = proc.waitFor();
                if (code != 0 || listOutput.trim().isEmpty()) {
                    return false;
                }

                sb.append("===== /data/adb/lspd/log/ (root) =====\n");
                sb.append("Files: ").append(listOutput.trim().replace("\n", ", ")).append("\n\n");

                String[] names = listOutput.split("\\s+");
                for (String name : names) {
                    if (name.isEmpty() || !name.endsWith(".log")) continue;
                    sb.append("----- ").append(name).append(" -----\n");
                    try {
                        Process cat = Runtime.getRuntime().exec("su");
                        java.io.DataOutputStream cos = new java.io.DataOutputStream(cat.getOutputStream());
                        cos.writeBytes("cat '/data/adb/lspd/log/" + name + "' 2>/dev/null\n");
                        cos.writeBytes("exit\n");
                        cos.flush();
                        String content = readStreamLimited(cat.getInputStream(), 500000);
                        drainStream(cat.getErrorStream());
                        cat.waitFor();
                        sb.append(content);
                        sb.append("\n");
                    } catch (Exception e) {
                        sb.append("[read failed: ").append(e.getMessage()).append("]\n\n");
                    }
                }
                return true;
            } catch (Exception e) {
                FileLogger.e("MainActivity", "root LSPosed log read failed", e);
                return false;
            } finally {
                if (proc != null) proc.destroy();
            }
        }

        private void appendLogcatLogs(StringBuilder sb) {
            sb.append("===== logcat fallback (no root) =====\n");
            Process proc = null;
            try {
                proc = Runtime.getRuntime().exec(new String[]{
                        "logcat", "-d", "-s",
                        "LSPosed-Bridge:V", "LSPosed:V", "LSPosedManager:V", "Xposed:V",
                        "FontRedirect:V"
                });
                String output = readStreamLimited(proc.getInputStream(), 500000);
                drainStream(proc.getErrorStream());
                proc.waitFor();
                sb.append(output);
                sb.append("\n");
            } catch (Exception e) {
                sb.append("[logcat failed: ").append(e.getMessage()).append("]\n");
                FileLogger.e("MainActivity", "logcat fallback failed", e);
            } finally {
                if (proc != null) proc.destroy();
            }
        }

        private String readStream(InputStream is) throws IOException {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            return sb.toString();
        }

        private String readStreamLimited(InputStream is, int maxChars) throws IOException {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (sb.length() + line.length() + 1 > maxChars) {
                        int keep = maxChars - sb.length();
                        if (keep > 0) {
                            sb.append(line, 0, keep);
                        }
                        sb.append("\n... (truncated to last ").append(maxChars).append(" chars)\n");
                        break;
                    }
                    sb.append(line).append("\n");
                }
            }
            return sb.toString();
        }

        private void drainStream(InputStream is) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
                while (br.readLine() != null) {
                    // discard
                }
            } catch (IOException ignored) {
            }
        }

        private boolean writeStringToDownloads(MainActivity activity, String name, String content) {
            try {
                byte[] data = content.getBytes("UTF-8");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    values.put(MediaStore.Downloads.IS_PENDING, 1);
                    Uri uri = activity.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) {
                        throw new IOException("MediaStore insert returned null");
                    }
                    try (java.io.OutputStream os = activity.getContentResolver().openOutputStream(uri)) {
                        if (os == null) {
                            throw new IOException("openOutputStream returned null");
                        }
                        os.write(data);
                        os.flush();
                    }
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Downloads.IS_PENDING, 0);
                    done.put(MediaStore.Downloads.SIZE, data.length);
                    activity.getContentResolver().update(uri, done, null, null);
                } else {
                    File out = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name);
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        fos.write(data);
                        fos.flush();
                    }
                }
                FileLogger.i("MainActivity", "exported LSPosed log to Downloads/" + name + " (" + data.length + " bytes)");
                return true;
            } catch (Exception e) {
                errorMsg = e.getMessage();
                FileLogger.e("MainActivity", "export LSPosed log failed", e);
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean ok) {
            MainActivity activity = ref.get();
            if (activity == null) return;
            if (ok) {
                Toast.makeText(activity, "LSPosed 日志已导出到 Downloads: " + fileName, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(activity, "导出失败: " + (errorMsg != null ? errorMsg : "未知错误"), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void copyStream(java.io.InputStream is, java.io.OutputStream os) throws IOException {
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) > 0) {
            os.write(buf, 0, len);
        }
    }
}

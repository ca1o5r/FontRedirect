package org.c0fle4.FontRedirect;

import android.graphics.drawable.Drawable;

public class AppInfo {
    private final String packageName;
    private final String appName;
    private final Drawable icon;
    private boolean selected;

    public AppInfo(String packageName, String appName, Drawable icon, boolean selected) {
        this.packageName = packageName;
        this.appName = appName;
        this.icon = icon;
        this.selected = selected;
    }

    public String getPackageName() { return packageName; }
    public String getAppName() { return appName; }
    public Drawable getIcon() { return icon; }
    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }
}

package org.c0fle4.FontRedirect.prefs;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

public class PrefHelper {
    public static final String PREFS_NAME = "app_selection";
    public static final String KEY_SELECTED_APPS = "selected_apps";

    private final SharedPreferences prefs;

    public PrefHelper(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public Set<String> getSelectedApps() {
        return new HashSet<>(prefs.getStringSet(KEY_SELECTED_APPS, new HashSet<>()));
    }

    public void setSelectedApps(Set<String> apps) {
        prefs.edit().putStringSet(KEY_SELECTED_APPS, apps).apply();
    }

    public boolean isSelected(String packageName) {
        return getSelectedApps().contains(packageName);
    }
}

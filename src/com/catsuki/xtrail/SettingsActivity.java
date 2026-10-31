package com.catsuki.xtrail;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Launcher entry. X cannot see this app and the module has no way into X's storage, so the settings live inside X:
 * this only opens X with a flag that the hook in {@link XTrailModule} turns into the settings page.
 */
public final class SettingsActivity extends Activity {
    static final String EXTRA = "xtrail_open_settings";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.twitter.android");
            if (i == null) {
                i = new Intent(Intent.ACTION_MAIN)
                        .setComponent(new ComponentName("com.twitter.android", "com.x.android.main.MainActivity"))
                        .addCategory(Intent.CATEGORY_LAUNCHER);
            }
            i.putExtra(EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开 X：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
        finish();
    }
}

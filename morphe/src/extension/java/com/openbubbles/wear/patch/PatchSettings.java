package com.openbubbles.wear.patch;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.compose.runtime.Composer;
import androidx.compose.ui.platform.AndroidCompositionLocals_androidKt;
import com.localob.messaging.app.settings.AccountStatusSectionKt;
import com.localob.messaging.app.settings.SettingsScreenKt;
import kotlin.Unit;

/** Runtime settings belong to OpenBubbles, which enforces them on every request. */
public final class PatchSettings {
    static final String PREFS = "morphe_wear_patches";
    static final String ENABLED = "wear_bridge_enabled";

    private PatchSettings() { }

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply();
    }

    public static void accountStatusAndPatches(Composer composer, int flags) {
        AccountStatusSectionKt.AccountStatusSection(composer, flags);
        try {
            Context context = (Context) composer.consume(AndroidCompositionLocals_androidKt.getLocalContext());
            SettingsScreenKt.SettingLink("Morphe patches", "Configure installed patches", null, () -> {
                context.startActivity(new Intent(context, PatchSettingsActivity.class));
                return Unit.INSTANCE;
            }, composer, 0, 4);
        } catch (RuntimeException error) {
            Log.w("OBWearPatch", "Unable to show patch settings", error);
        }
    }
}

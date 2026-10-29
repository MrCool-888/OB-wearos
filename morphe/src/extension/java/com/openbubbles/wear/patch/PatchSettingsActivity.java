package com.openbubbles.wear.patch;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** An app-private screen reached from OpenBubbles Settings → Morphe patches. */
public final class PatchSettingsActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        setTitle("Morphe patches");
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom);
            return insets;
        });
        Button back = new Button(this);
        back.setText("Back to OpenBubbles");
        back.setOnClickListener(view -> finish());
        content.addView(back);
        TextView title = new TextView(this);
        title.setText("Morphe patches");
        title.setTextSize(26);
        title.setPadding(0, padding, 0, padding);
        content.addView(title);
        Switch toggle = new Switch(this);
        toggle.setText("Wear companion bridge");
        toggle.setTextSize(18);
        toggle.setMinHeight(Math.round(56 * getResources().getDisplayMetrics().density));
        toggle.setChecked(PatchSettings.isEnabled(this));
        toggle.setOnCheckedChangeListener((button, enabled) -> PatchSettings.setEnabled(this, enabled));
        content.addView(toggle);
        TextView detail = new TextView(this);
        detail.setText("Show OpenBubbles chats and recent history on your watch, view downloaded images, and send replies through your phone.\n\nChanges apply to new requests immediately. Sync the watch after changing this setting. Turning it off uses notification mode. No repatching or reinstalling is needed.");
        detail.setTextSize(16);
        detail.setPadding(0, padding, 0, 0);
        content.addView(detail);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
    }
}

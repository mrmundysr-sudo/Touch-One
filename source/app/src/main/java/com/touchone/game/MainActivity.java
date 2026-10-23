package com.touchone.game;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.View;

/**
 * Hosts the game. All of the game itself lives in {@link TableView}; this class only
 * owns the window (fullscreen, screen-on), the sound pool and the lifecycle hand-offs.
 */
public class MainActivity extends Activity {
    private TableView table;
    private Sfx sfx;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        Theme.init(getAssets());
        sfx = new Sfx(this);
        table = new TableView(this, sfx, new Engine());
        setContentView(table);
        enterImmersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersive();
        table.onHostResume();
    }

    @Override
    protected void onPause() {
        table.onHostPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        sfx.release();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
    }

    @Override
    public void onBackPressed() {
        if (!table.onBack()) super.onBackPressed();
    }

    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }
}

package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewParent;
import android.widget.ScrollView;

/**
 * App launcher screen: a marketing-style welcome page (hero, gallery, about)
 * shown to guests before they log in. Log In leads to the existing
 * MainActivity login screen. Only the "AGUA FRIO" nav tab is wired up today;
 * ROOMS/COTTAGES/KTV/HALLS are left as inert labels until their destinations
 * exist.
 */
public class LandingActivity extends Activity {

    private ScrollView landingScroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!ProfileStore.getAuthToken(this).isEmpty()) {
            startActivity(new Intent(this, DashboardActivity.class));
            finish();
            return;
        }
        ThemeManager.apply(this);
        setContentView(R.layout.activity_landing);

        landingScroll = findViewById(R.id.landingScroll);

        View heroSection = findViewById(R.id.heroSection);

        findViewById(R.id.navTabHome).setOnClickListener(v -> scrollToSection(heroSection));
        findViewById(R.id.landingLogInButton).setOnClickListener(v -> openLogin());
    }

    private void openLogin() {
        startActivity(new Intent(this, MainActivity.class));
    }

    /** Smooth-scrolls to a section, summing its offset through its ancestor views up to the ScrollView. */
    private void scrollToSection(View target) {
        landingScroll.post(() -> {
            int top = 0;
            View v = target;
            while (v != null && v != landingScroll) {
                top += v.getTop();
                ViewParent parent = v.getParent();
                if (!(parent instanceof View)) {
                    break;
                }
                v = (View) parent;
            }
            landingScroll.smoothScrollTo(0, top);
        });
    }
}

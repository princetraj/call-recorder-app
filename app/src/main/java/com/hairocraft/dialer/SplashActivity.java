package com.hairocraft.dialer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

public class SplashActivity extends Activity {

    private static final int SPLASH_DISPLAY_LENGTH = 2500; // 2.5 seconds
    private PrefsManager prefsManager;
    private ApiService apiService;
    private Handler splashHandler; // Handler for cleanup

    private ImageView splashLogo;
    private TextView splashTitle;
    private TextView splashSubtitle;
    private ProgressBar splashProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        // Initialize
        prefsManager = new PrefsManager(this);
        apiService = ApiService.getInstance();

        // Find views
        splashLogo = findViewById(R.id.splash_logo);
        splashTitle = findViewById(R.id.splash_title);
        splashSubtitle = findViewById(R.id.splash_subtitle);
        splashProgress = findViewById(R.id.splash_progress);

        // Start animations
        startAnimations();

        // Check login status after a delay
        splashHandler = new Handler();
        splashHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkLoginStatus();
            }
        }, SPLASH_DISPLAY_LENGTH);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up handler to prevent memory leak
        if (splashHandler != null) {
            splashHandler.removeCallbacksAndMessages(null);
        }
    }

    private void startAnimations() {
        // Logo animation (scale + fade in)
        Animation logoAnim = AnimationUtils.loadAnimation(this, R.anim.splash_logo_anim);
        splashLogo.startAnimation(logoAnim);

        // Title fade in (delayed)
        Animation titleFadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in);
        titleFadeIn.setStartOffset(500);
        splashTitle.startAnimation(titleFadeIn);

        // Subtitle fade in (delayed more)
        Animation subtitleFadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in);
        subtitleFadeIn.setStartOffset(800);
        splashSubtitle.startAnimation(subtitleFadeIn);

        // Progress bar fade in (delayed most)
        Animation progressFadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in);
        progressFadeIn.setStartOffset(1200);
        splashProgress.startAnimation(progressFadeIn);
    }

    private void checkLoginStatus() {
        if (prefsManager.isLoggedIn()) {
            // User is logged in, trust the local token
            // Remote logout will be handled via periodic status updates
            navigateToMain();
        } else {
            // Not logged in, go to main (will show login screen)
            navigateToMain();
        }
    }

    private void navigateToMain() {
        Intent intent = new Intent(SplashActivity.this, MainActivity.class);
        startActivity(intent);
        finish();
        // Add transition animation
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}

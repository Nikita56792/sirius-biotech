package org.sirius.biotech;

import android.app.Activity;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Color;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import java.io.InputStream;

/** One centered portrait crop per process; fit it without clipping the title. */
final class LaunchScreen {
    private static boolean shown;

    static void start(Activity activity, Runnable initialize, Runnable ready) {
        if (shown) { initialize.run(); ready.run(); return; }
        shown = true;
        View image;
        try (InputStream source = activity.getAssets().open("splash-sbs-mss.png")) {
            final Bitmap artwork = BitmapFactory.decodeStream(source);
            image = new View(activity) {
                private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                @Override protected void onDraw(Canvas canvas) {
                    super.onDraw(canvas);
                    // Center-crop the original portrait composition; tablets keep app-colored side bars.
                    float width = Math.min(getWidth(), getHeight() * 9f / 16f);
                    float height = getHeight();
                    float scale = Math.max(width / artwork.getWidth(), height / artwork.getHeight());
                    float sourceWidth = width / scale, sourceHeight = height / scale;
                    float left = (artwork.getWidth() - sourceWidth) / 2f;
                    float top = (artwork.getHeight() - sourceHeight) / 2f;
                    float x = (getWidth() - width) / 2f;
                    canvas.drawBitmap(artwork, new Rect(Math.round(left), Math.round(top), Math.round(left + sourceWidth), Math.round(top + sourceHeight)), new RectF(x, 0, x + width, height), paint);
                }
            };
        } catch (java.io.IOException error) {
            initialize.run(); ready.run(); return;
        }
        image.setBackgroundColor(0xffeeeef4);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        image.setClickable(true);
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        int status = activity.getWindow().getStatusBarColor();
        int navigation = activity.getWindow().getNavigationBarColor();
        int cutout = activity.getWindow().getAttributes().layoutInDisplayCutoutMode;
        if (Build.VERSION.SDK_INT >= 28) {
            android.view.WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
            attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            activity.getWindow().setAttributes(attributes);
        }
        activity.getWindow().setStatusBarColor(Color.TRANSPARENT);
        activity.getWindow().setNavigationBarColor(Color.TRANSPARENT);
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        decor.addView(image, new ViewGroup.LayoutParams(-1, -1));
        if (Build.VERSION.SDK_INT >= 31) activity.getSplashScreen().setOnExitAnimationListener(splash -> splash.remove());
        long started = SystemClock.uptimeMillis();
        // Let the image draw before constructing the first screen.
        image.postOnAnimation(() -> image.post(() -> {
            initialize.run();
            image.bringToFront();
            image.postOnAnimation(() -> image.postDelayed(() -> {
                Runnable finish=()->{
                decor.removeView(image);
                decor.setSystemUiVisibility(flags);
                activity.getWindow().setStatusBarColor(status);
                activity.getWindow().setNavigationBarColor(navigation);
                if (Build.VERSION.SDK_INT >= 28) {
                    android.view.WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
                    attributes.layoutInDisplayCutoutMode = cutout;
                    activity.getWindow().setAttributes(attributes);
                }
                ready.run();
                };
                if(android.animation.ValueAnimator.areAnimatorsEnabled())image.animate().alpha(0f).setDuration(300).withEndAction(finish).start();else finish.run();
            }, Math.max(0, 450 - (SystemClock.uptimeMillis() - started))));
        }));
    }
}

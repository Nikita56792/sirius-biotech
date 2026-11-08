package org.sirius.biotech;

import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.io.ByteArrayOutputStream;

/** Local-only editor. A null callback result means cancel; only confirmation produces bytes. */
final class AvatarCropView {
    interface Callback { void onResult(byte[] jpeg); }

    static View create(MainActivity activity, Bitmap original, Callback callback) {
        boolean ru = "ru".equals(activity.getResources().getConfiguration().getLocales().get(0).getLanguage());
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff17191e);
        TextView title = new TextView(activity);
        title.setText(ru ? "Обрезать фото" : "Crop photo");
        title.setTextColor(Color.WHITE); title.setTextSize(22);
        title.setPadding(activity.dp(20), activity.dp(16), activity.dp(20), activity.dp(16));
        root.addView(title);
        Editor editor = new Editor(activity, original);
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
        SeekBar zoom = new SeekBar(activity);
        zoom.setMax(400);
        zoom.setContentDescription(ru ? "Масштаб" : "Zoom");
        root.addView(zoom, new LinearLayout.LayoutParams(-1, activity.dp(48)));
        zoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) {}
            public void onProgressChanged(SeekBar b, int value, boolean user) {
                if (user) editor.zoomAt(1f + value / 100f, editor.cx(), editor.cy());
            }
        });
        editor.zoomControl = zoom;
        LinearLayout controls = new LinearLayout(activity);
        Button cancel = new Button(activity), use = new Button(activity);
        cancel.setText(ru ? "Отмена" : "Cancel");
        use.setText(ru ? "Использовать фото" : "Use photo");
        controls.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(use, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(controls);
        final boolean[] finished = {false};
        cancel.setOnClickListener(v -> { if (!finished[0]) { finished[0] = true; callback.onResult(null); } });
        use.setOnClickListener(v -> {
            if (finished[0] || editor.side <= 0) return;
            Bitmap result = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result); canvas.drawColor(Color.WHITE);
            float factor = 512f / editor.side;
            canvas.scale(factor, factor);
            canvas.translate(-editor.left(), -editor.top());
            canvas.translate(editor.x, editor.y); canvas.scale(editor.scale(), editor.scale());
            canvas.drawBitmap(original, 0, 0, editor.paint);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            result.compress(Bitmap.CompressFormat.JPEG, 92, out); result.recycle();
            finished[0] = true; callback.onResult(out.toByteArray());
        });
        return root;
    }

    private static final class Editor extends View {
        final Bitmap image;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final ScaleGestureDetector pinch;
        SeekBar zoomControl;
        float side, base, zoom = 1, x, y, lastX, lastY;
        Editor(MainActivity context, Bitmap image) {
            super(context); this.image = image;
            setContentDescription("Drag to position photo; pinch or use slider to zoom");
            pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                public boolean onScale(ScaleGestureDetector d) {
                    zoomAt(zoom * d.getScaleFactor(), d.getFocusX(), d.getFocusY()); return true;
                }
            });
        }
        float cx() { return getWidth() / 2f; }
        float cy() { return getHeight() / 2f; }
        float left() { return cx() - side / 2; }
        float top() { return cy() - side / 2; }
        float scale() { return base * zoom; }
        protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            side = Math.max(0, Math.min(w - 32, h - 32));
            base = side / Math.min(image.getWidth(), image.getHeight());
            x = cx() - image.getWidth() * scale() / 2;
            y = cy() - image.getHeight() * scale() / 2; clamp();
        }
        void clamp() {
            x = Math.min(left(), Math.max(left() + side - image.getWidth() * scale(), x));
            y = Math.min(top(), Math.max(top() + side - image.getHeight() * scale(), y));
            invalidate();
        }
        void zoomAt(float value, float focusX, float focusY) {
            float next = Math.max(1, Math.min(5, value)), ratio = next / zoom;
            x = focusX - (focusX - x) * ratio; y = focusY - (focusY - y) * ratio; zoom = next;
            if (zoomControl != null) zoomControl.setProgress(Math.round((zoom - 1) * 100));
            clamp();
        }
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save(); canvas.translate(x, y); canvas.scale(scale(), scale());
            canvas.drawBitmap(image, 0, 0, paint); canvas.restore();
            // Dim outside the circular preview, retaining the full original underneath.
            Path mask = new Path(); mask.setFillType(Path.FillType.EVEN_ODD);
            mask.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
            mask.addCircle(cx(), cy(), side / 2, Path.Direction.CW);
            paint.setColor(0xaa000000); canvas.drawPath(mask, paint);
            paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2);
            canvas.drawRect(left(), top(), left() + side, top() + side, paint);
            canvas.drawCircle(cx(), cy(), side / 2, paint); paint.setStyle(Paint.Style.FILL);
        }
        public boolean onTouchEvent(MotionEvent event) {
            pinch.onTouchEvent(event);
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
            if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1 && !pinch.isInProgress()) {
                x += event.getX() - lastX; y += event.getY() - lastY; clamp();
            }
            int index = action == MotionEvent.ACTION_POINTER_UP && event.getActionIndex() == 0 ? 1 : 0;
            lastX = event.getX(index); lastY = event.getY(index);
            if (action == MotionEvent.ACTION_UP) performClick();
            return true;
        }
        public boolean performClick() { super.performClick(); return true; }
    }
}

package org.sirius.biotech;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import java.net.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Private, bounded photo cache. URLs are allowlisted and redirects are never followed. */
final class ProfilePhotos {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final long MAX_AGE = 7L * 24 * 60 * 60 * 1000;
    private static final long CHECK_INTERVAL = 5L * 60 * 1000;
    private static final int MAX_DOWNLOAD = 2 * 1024 * 1024;
    private static final Map<String, Long> attempts = new LinkedHashMap<String, Long>(64, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, Long> entry) { return size() > 512; }
    };

    private static final java.util.concurrent.ExecutorService downloads = java.util.concurrent.Executors.newFixedThreadPool(2);

    static void load(MainActivity a, ImageView view, String path) { load(a,view,path,true); }
    static void load(MainActivity a, ImageView view, String path, boolean showPlaceholder) {
        view.setTag(null);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setOutlineProvider(new ViewOutlineProvider() {
            public void getOutline(View v, Outline outline) {
                int side = Math.min(v.getWidth(), v.getHeight());
                int left = (v.getWidth() - side) / 2, top = (v.getHeight() - side) / 2;
                outline.setOval(left, top, left + side, top + side);
            }
        });
        view.setClipToOutline(true);
        int placeholder = a.getResources().getIdentifier("icon_attendee_56", "drawable", a.getPackageName());
        if (!showPlaceholder) view.setImageDrawable(null); else if (placeholder != 0) view.setImageResource(placeholder); else view.setImageDrawable(new Placeholder());
        if (path == null || path.isEmpty()) return;
        final String url = (path.startsWith("/media/avatars/") || path.startsWith("/static/assets/speakers/")) ? a.api.baseUrl() + path : path;
        try {
            URI uri = URI.create(url);
            String host = uri.getHost(), baseHost = URI.create(a.api.baseUrl()).getHost();
            if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || uri.getPort() != -1 && uri.getPort() != 443
                    || !(host.equals(baseHost) || host.equals("static.tildacdn.com"))) return;
        } catch (Exception e) { return; }
        view.setTag(url);
        downloads.execute(() -> {
            try {
                if(path.startsWith("/static/assets/speakers/")){
                    try(InputStream source=a.getAssets().open("speakers/"+path.substring(path.lastIndexOf('/')+1))){
                        Bitmap bundled=decode(read(source));if(bundled!=null){publish(a,view,url,bundled);return;}
                    }catch(IOException ignored){}
                }
                File dir = new File(a.getNoBackupFilesDir(), "profile-photos");
                if (!dir.isDirectory() && !dir.mkdirs()) return;
                trim(dir);
                String key = hash(url);
                File imageFile = new File(dir, key + ".jpg"), metadata = new File(dir, key + ".properties");
                Properties props = new Properties();
                if (metadata.isFile()) try (InputStream in = new FileInputStream(metadata)) { props.load(in); }
                byte[] cached = imageFile.isFile() ? read(new FileInputStream(imageFile)) : null;
                Bitmap bitmap = decode(cached);
                if (bitmap != null) { imageFile.setLastModified(System.currentTimeMillis()); publish(a, view, url, bitmap); if(path.startsWith("/media/avatars/")) return; }
                long now = System.currentTimeMillis();
                long checked;
                try { checked = Long.parseLong(props.getProperty("checked", "0")); } catch (Exception e) { checked = 0; }
                synchronized (attempts) {
                    Long attempted = attempts.get(url);
                    if ((attempted != null && now >= attempted && now - attempted < CHECK_INTERVAL)
                            || (bitmap != null && now >= checked && now - checked < CHECK_INTERVAL)) return;
                    attempts.put(url, now);
                }
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(6000); connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(false);
                if (bitmap != null) {
                    if (props.getProperty("etag") != null) connection.setRequestProperty("If-None-Match", props.getProperty("etag"));
                    if (props.getProperty("modified") != null) connection.setRequestProperty("If-Modified-Since", props.getProperty("modified"));
                }
                try {
                    int status = connection.getResponseCode();
                    if (status == 304 && bitmap != null) {
                        props.setProperty("checked", Long.toString(now));
                        saveMetadata(metadata, props); imageFile.setLastModified(now);
                    } else if (status == 200) {
                        if (connection.getContentLengthLong() > MAX_DOWNLOAD) return;
                        byte[] bytes = read(connection.getInputStream());
                        Bitmap fresh = decode(bytes);
                        if (fresh == null) return;
                        writeAtomic(imageFile, bytes);
                        props.clear(); props.setProperty("checked", Long.toString(now));
                        String etag = connection.getHeaderField("ETag"), modified = connection.getHeaderField("Last-Modified");
                        if (etag != null) props.setProperty("etag", etag);
                        if (modified != null) props.setProperty("modified", modified);
                        saveMetadata(metadata, props);
                        publish(a, view, url, fresh);
                    } else if (status == 404 || status == 410) {
                        imageFile.delete(); metadata.delete();
                        a.runOnUiThread(() -> { if (url.equals(view.getTag())) {
                            if (!showPlaceholder) view.setImageDrawable(null); else if (placeholder != 0) view.setImageResource(placeholder); else view.setImageDrawable(new Placeholder());
                        } });
                    }
                } finally { connection.disconnect(); trim(dir); }
            } catch (Exception | OutOfMemoryError ignored) { /* Retain the cached photo while offline. */ }
        });
    }

    private static void publish(MainActivity a, ImageView view, String url, Bitmap image) {
        a.runOnUiThread(() -> { if (!a.isDestroyed() && url.equals(view.getTag())) view.setImageBitmap(image); });
    }
    private static Bitmap decode(byte[] bytes) {
        if (bytes == null) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
        while (((long) Math.max(bounds.outWidth, bounds.outHeight) + options.inSampleSize - 1) / options.inSampleSize > 1024)
            options.inSampleSize *= 2;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }
    private static byte[] read(InputStream stream) throws IOException {
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > MAX_DOWNLOAD) throw new IOException("Photo too large");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
    private static String hash(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
        StringBuilder result = new StringBuilder();
        for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    private static void writeAtomic(File target, byte[] bytes) throws IOException {
        File tmp = new File(target.getPath() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(bytes); out.getFD().sync(); }
            if (!tmp.renameTo(target)) throw new IOException("Cache replace failed");
        } finally { tmp.delete(); }
    }
    private static void saveMetadata(File file, Properties props) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); props.store(bytes, null); writeAtomic(file, bytes.toByteArray());
    }
    private static void trim(File dir) {
        File[] files = dir.listFiles(); if (files == null) return;
        long now = System.currentTimeMillis(), size = 0;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File file : files) {
            if (file.getName().endsWith(".tmp")) file.delete();
            else size += file.length();
        }
        for (File file : files) {
            if (size <= MAX_BYTES) break;
            if (file.isFile()) { long length = file.length(); if (file.delete()) size -= length; }
        }
    }
    private static final class Placeholder extends Drawable {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        public void draw(Canvas c) {
            Rect b = getBounds(); float x = b.exactCenterX(), y = b.exactCenterY(), r = Math.min(b.width(), b.height()) / 2f;
            paint.setColor(0xffe5e7ec); c.drawCircle(x, y, r, paint);
            paint.setColor(0xff929fa8); c.drawCircle(x, y - r * .25f, r * .28f, paint);
            c.drawOval(x - r * .58f, y + r * .12f, x + r * .58f, y + r * 1.1f, paint);
        }
        public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}

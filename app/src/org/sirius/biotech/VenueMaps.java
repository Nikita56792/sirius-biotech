package org.sirius.biotech;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Original MV floor geometry with strictly masked floor-2 navigation. */
public final class VenueMaps {
    private VenueMaps() {}
    public static View create(MainActivity activity) { return new Screen(activity); }
    private static String normalize(String text) { return text.toLowerCase(Locale.ROOT).replace('ё', 'е'); }

    private static final class Poi {
        String id, ru, en, category, node, number = "";
        float x, y;
        String name(boolean russian) { return russian ? ru : en; }
    }
    private static final class Zone {
        Path path;
        Poi poi;
        float x, y;
    }
    private static final class Data {
        final float[] bounds = new float[4];
        final ArrayList<Path> blocks = new ArrayList<>(), circulation = new ArrayList<>(), walls = new ArrayList<>(), lines = new ArrayList<>();
        final ArrayList<Zone> zones = new ArrayList<>();
        final Map<String, Poi> pois = new LinkedHashMap<>();
        final EventVenueGraph graph = new EventVenueGraph();
        int blocked, highlight, route, ink, wall;
    }
    private static void ring(Path path, JSONArray ring) throws Exception {
        for (int i = 0; i < ring.length(); i++) {
            JSONArray p = ring.getJSONArray(i);
            float x = (float) p.getDouble(0), y = (float) p.getDouble(1);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
    }
    private static void polygons(JSONArray items, ArrayList<Path> target) throws Exception {
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            Path path = new Path(); path.setFillType(Path.FillType.EVEN_ODD);
            ring(path, item.getJSONArray("polygon"));
            JSONArray holes = item.optJSONArray("holes");
            if (holes != null) for (int h = 0; h < holes.length(); h++) ring(path, holes.getJSONArray(h));
            target.add(path);
        }
    }
    private static Data load(Context context) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = context.getAssets().open("venue/event-map.json")) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
        }
        JSONObject root = new JSONObject(bytes.toString("UTF-8"));
        if (root.getInt("version") != 2 || root.getInt("floor") != 2
                || !"up".equals(root.getJSONObject("coordinates").getString("axisY"))
                || root.getJSONObject("graph").getBoolean("directed"))
            throw new IllegalArgumentException("Unsupported event map");
        Data data = new Data();
        JSONArray bounds = root.getJSONObject("coordinates").getJSONArray("bounds");
        for (int i = 0; i < 4; i++) data.bounds[i] = (float) bounds.getDouble(i);
        JSONObject style = root.getJSONObject("style");
        data.blocked = Color.parseColor(style.getString("blocked"));
        data.highlight = Color.parseColor(style.getString("highlight"));
        data.route = Color.parseColor(style.getString("route"));
        data.ink = Color.parseColor(style.getString("ink"));
        data.wall = Color.parseColor(style.getString("wall"));
        JSONArray blocks = root.getJSONArray("blocked");
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.getJSONObject(i);
            Path path = new Path();
            path.setFillType(Path.FillType.EVEN_ODD);
            ring(path, block.getJSONArray("polygon"));
            JSONArray holes = block.optJSONArray("holes");
            if (holes != null) for (int h = 0; h < holes.length(); h++) ring(path, holes.getJSONArray(h));
            data.blocks.add(path);
        }
        JSONArray pois = root.getJSONArray("pois");
        for (int i = 0; i < pois.length(); i++) {
            JSONObject p = pois.getJSONObject(i);
            if (p.getInt("floor") != 2) throw new IllegalArgumentException("Unexpected floor");
            Poi poi = new Poi();
            poi.id = p.getString("id"); poi.ru = p.getString("nameRU"); poi.en = p.isNull("nameEN") ? poi.ru : p.getString("nameEN");
            poi.category = p.getString("category"); poi.node = p.getJSONObject("entrance").isNull("cell") ? null
                    : Integer.toString(p.getJSONObject("entrance").getInt("cell"));
            poi.x = (float) p.getDouble("x"); poi.y = (float) p.getDouble("y");
            data.pois.put(poi.id, poi);
        }
        JSONArray zones = root.getJSONArray("zones");
        for (int i = 0; i < zones.length(); i++) {
            JSONObject z = zones.getJSONObject(i);
            Zone zone = new Zone();
            zone.poi = data.pois.get(z.getString("poiId"));
            if (zone.poi == null) throw new IllegalArgumentException("Unknown zone POI");
            zone.poi.number = z.isNull("number") ? "" : z.optString("number", "");
            zone.path = new Path();
            JSONArray polygon = z.getJSONArray("polygon");
            ring(zone.path, polygon);
            JSONArray holes = z.optJSONArray("holes");
            zone.path.setFillType(Path.FillType.EVEN_ODD);
            if (holes != null) for (int h = 0; h < holes.length(); h++) ring(zone.path, holes.getJSONArray(h));
            zone.x = zone.poi.x; zone.y = zone.poi.y;
            data.zones.add(zone);
        }
        JSONObject graph = root.getJSONObject("graph");
        if (!"grid-rle-u16".equals(graph.getString("type")) || graph.getBoolean("diagonalCornerCut"))
            throw new IllegalArgumentException("Unsupported routing grid");
        data.graph.configure(graph.getInt("cols"), graph.getInt("rows"), graph.getDouble("x0"),
                graph.getDouble("y1"), graph.getDouble("res"));
        JSONArray runs = graph.getJSONArray("runs");
        if (runs.length() != graph.getInt("rows")) throw new IllegalArgumentException("Grid rows");
        for (int r = 0; r < runs.length(); r++) {
            JSONArray row = runs.getJSONArray(r);
            for (int j = 0; j < row.length(); j++) {
                JSONArray run = row.getJSONArray(j);
                if (run.getInt(2) <= 0) throw new IllegalArgumentException("Closed run");
                data.graph.addRun(r, run.getInt(0), run.getInt(1));
            }
        }
        for (Poi poi : data.pois.values()) if (poi.node != null) data.graph.addEntrance(Integer.parseInt(poi.node));
        JSONObject geometry = root.getJSONObject("geometry");
        polygons(geometry.getJSONArray("circulation"), data.circulation);
        polygons(geometry.getJSONArray("eventWalls"), data.walls);
        JSONObject lines = geometry.getJSONObject("eventLines");
        for (String key : new String[]{"door_xy", "stair_xy", "furn_xy", "edit_xy"}) {
            JSONArray paths = lines.getJSONArray(key);
            for (int i = 0; i < paths.length(); i++) {
                Path path = new Path(); JSONArray points = paths.getJSONArray(i);
                for (int j = 0; j < points.length(); j++) {
                    JSONArray point = points.getJSONArray(j);
                    float x = (float) point.getDouble(0), y = (float) point.getDouble(1);
                    if (j == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                data.lines.add(path);
            }
        }
        return data;
    }

    private static final class Screen extends LinearLayout {
        final MainActivity activity;
        final boolean russian;
        final Handler handler = new Handler(Looper.getMainLooper());
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final MapView map;
        final LinearLayout fields, footer, zoom, extras;
        final TextView fromButton, toButton, buildButton, status, stepsButton, browseButton;
        final int primary = 0xff356aff, ink = 0xff2a333d, secondary = 0xff596777;
        Data data;
        Poi from, to;
        EventVenueGraph.Route route;
        AlertDialog dialog;
        EditText searchInput;
        volatile boolean disposed;
        volatile int generation;
        int progress;
        boolean navigating;
        final EventVenueVoice voice;
        TextView startButton, voiceButton;
        Screen(MainActivity a) {
            super(a);
            activity = a;
            russian = "ru".equals(a.store.getString("appLanguage", "en"));
            voice = new EventVenueVoice(a, russian, text -> statusVoice(text));
            setOrientation(VERTICAL);
            setBackgroundColor(Color.WHITE);
            LinearLayout toolbar = new LinearLayout(a);
            toolbar.setGravity(Gravity.CENTER_VERTICAL);
            ImageView back = new ImageView(a);
            int icon = getResources().getIdentifier("icon_back_24", "drawable", a.getPackageName());
            if (icon != 0) back.setImageResource(icon);
            back.setColorFilter(a.ink);
            back.setPadding(a.dp(14), a.dp(14), a.dp(14), a.dp(14));
            back.setContentDescription(t("Назад", "Back"));
            back.setFocusable(true);
            back.setOnClickListener(v -> a.show("home"));
            toolbar.addView(back, new LayoutParams(a.dp(52), a.dp(52)));
            TextView title = a.bold(t("Место проведения", "Venue"), 20);
            toolbar.addView(title, new LayoutParams(0, -2, 1));
            TextView floor = a.text(t("Этаж 2", "Floor 2"), 14, secondary);
            floor.setPadding(0, 0, a.dp(12), 0);
            toolbar.addView(floor);
            addView(toolbar);
            map = new MapView(this);
            fields = new LinearLayout(a);
            fields.setOrientation(VERTICAL);
            fields.setPadding(a.dp(12), 0, a.dp(12), 0);
            fromButton = button("", () -> search(0));
            toButton = button("", () -> search(1));
            fields.addView(fromButton, new LayoutParams(-1, a.dp(48)));
            fields.addView(toButton, new LayoutParams(-1, a.dp(48)));
            addView(fields);
            LinearLayout plan = new LinearLayout(a);
            plan.addView(button(t("Поменять", "Swap"), this::swap), new LayoutParams(0, a.dp(48), 1));
            buildButton = button(t("Маршрут", "Route"), this::build);
            plan.addView(buildButton, new LayoutParams(0, a.dp(48), 1));
            plan.addView(button(t("Очистить", "Clear"), this::clear), new LayoutParams(0, a.dp(48), 1));
            addView(plan);
            addView(map, new LayoutParams(-1, 0, 1));
            footer = new LinearLayout(a);
            footer.setOrientation(VERTICAL);
            zoom = new LinearLayout(a);
            zoom.addView(button("-", () -> map.zoom(.67f)), new LayoutParams(0, a.dp(48), 1));
            zoom.addView(button(t("Вся карта", "Fit map"), map::fit), new LayoutParams(0, a.dp(48), 2));
            zoom.addView(button("+", () -> map.zoom(1.5f)), new LayoutParams(0, a.dp(48), 1));
            footer.addView(zoom);
            extras = new LinearLayout(a);
            browseButton = button(t("Поиск / зоны", "Search / zones"), () -> search(2));
            extras.addView(browseButton, new LayoutParams(0, a.dp(48), 1));
            stepsButton = button(t("По шагам", "Steps"), this::showSteps);
            extras.addView(stepsButton, new LayoutParams(0, a.dp(48), 1));
            footer.addView(extras);
            LinearLayout guidance = new LinearLayout(a);
            startButton = button(t("Поехали · вручную", "Start · manual"), this::startNavigation);
            voiceButton = button(t("Голос: вкл · TTS", "Voice: on · TTS"), () -> {
                voice.setMuted(!voice.muted);
                voiceButton.setText(voice.muted ? t("Голос: выкл", "Voice: off") : t("Голос: вкл · TTS", "Voice: on · TTS"));
            });
            guidance.addView(startButton, new LayoutParams(0, a.dp(48), 1));
            guidance.addView(voiceButton, new LayoutParams(0, a.dp(48), 1));
            footer.addView(guidance);
            addView(footer);
            status = a.text(t("Загрузка карты...", "Loading map..."), 12, secondary);
            status.setPadding(a.dp(12), 0, a.dp(12), a.dp(6));
            status.setMaxLines(3);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            addView(status);
            updateFields();
            worker.execute(() -> {
                try {
                    Data loaded = load(a.getApplicationContext());
                    handler.post(() -> {
                        if (disposed) return;
                        data = loaded;
                        map.data = loaded;
                        map.fit();
                        updateFields();
                        status.setText(t("Оригинальная карта MV. Между залами проходы не подтверждены. Позиционирование отсутствует.",
                                "Original MV map. Cross-hall passages unverified. No positioning provider."));
                    });
                } catch (Exception e) {
                    android.util.Log.e("VenueMaps", "Event map load failed", e);
                    handler.post(() -> { if (!disposed) status.setText(t("Не удалось загрузить карту. Вернитесь и попробуйте снова.",
                            "Unable to load map. Go back and try again.")); });
                }
            });
        }
        String t(String ru, String en) { return russian ? ru : en; }
        String name(Poi poi) { return poi == null ? t("не выбрано", "not selected") : poi.name(russian); }
        String category(Poi poi) {
            switch (poi.category) {
                case "hall": return t("Зал", "Hall");
                case "entrance": return t("Вход", "Entrance");
                case "toilet": return t("Туалет", "Toilet");
                case "registration": return t("Регистрация", "Registration");
                case "coffee": return t("Кофе", "Coffee");
                case "cloakroom": return t("Гардероб", "Cloakroom");
                case "information": return t("Информация", "Information");
                case "photo": return t("Фотозона", "Photo zone");
                case "partner": return t("Партнеринг", "Partnering");
                case "service": return t("Сервис", "Service");
                case "poster": return t("Постерная сессия", "Poster session");
                default: return t("Зона мероприятия", "Event zone");
            }
        }
        TextView button(String label, Runnable action) {
            TextView v = activity.text(label, 14, primary);
            v.setGravity(Gravity.CENTER);
            v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            v.setPadding(activity.dp(8), 0, activity.dp(8), 0);
            v.setMaxLines(2);
            v.setEllipsize(TextUtils.TruncateAt.END);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xfff3f6fc); bg.setCornerRadius(activity.dp(12));
            v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22356aff), bg, null));
            v.setContentDescription("+".equals(label) ? t("Увеличить", "Zoom in")
                    : "-".equals(label) ? t("Уменьшить", "Zoom out") : label);
            v.setFocusable(true);
            v.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName("android.widget.Button");
                }
            });
            v.setOnClickListener(w -> action.run());
            return v;
        }
        void updateFields() {
            fromButton.setText(t("Откуда: ", "From: ") + name(from));
            toButton.setText(t("Куда: ", "To: ") + name(to));
            fromButton.setContentDescription(fromButton.getText());
            toButton.setContentDescription(toButton.getText());
            fromButton.setEnabled(data != null); toButton.setEnabled(data != null);
            browseButton.setEnabled(data != null);
            buildButton.setEnabled(from != null && to != null);
            stepsButton.setEnabled(route != null);
            if (startButton != null) startButton.setEnabled(route != null && route.nodes.size() > 1);
        }
        void clear() {
            generation++;
            navigating = false; voice.stop();
            route = null; map.route = null; map.progress = -1; progress = 0;
            map.invalidate(); updateFields();
            status.setText(t("Маршрут очищен. Начало и цель можно изменить.", "Route cleared. You can change start and destination."));
        }
        void choose(Poi poi, int mode) {
            clear();
            if (mode == 0) from = poi; else to = poi;
            map.selected = poi; map.focus(poi.x, poi.y);
            updateFields();
            status.setText(t("Выберите точки и нажмите «Маршрут».", "Choose endpoints, then tap Route."));
        }
        void swap() {
            Poi old = from; from = to; to = old;
            clear(); updateFields();
            if (from != null && to != null) build();
        }
        void build() {
            if (from == null || to == null || disposed) return;
            navigating = false; voice.stop();
            final int token = ++generation;
            final Poi start = from, end = to;
            route = null; map.route = null; map.progress = -1; map.invalidate(); updateFields();
            status.setText(t("Строим маршрут...", "Building route..."));
            worker.execute(() -> {
                EventVenueGraph.Route result = data.graph.route(start.node, end.node, () -> disposed || token != generation);
                handler.post(() -> {
                    if (disposed || token != generation) return;
                    route = result; map.route = result; progress = 0;
                    map.progress = result == null ? -1 : 0;
                    updateFields(); map.fitRoute();
                    if (result == null) status.setText(t("Подтвержденного прохода нет. Уточните маршрут у организаторов.", "No verified passage. Ask the event staff for directions."));
                    else status.setText(result.nodes.size() == 1 ? t("Начало и цель совпадают.", "Start and destination coincide.")
                            : String.format(Locale.ROOT, t("%.0f м по модели · продвижение вручную", "%.0f model metres · manual progress"), result.distance));
                });
            });
        }
        void dismissDialog() {
            if (searchInput != null) {
                InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
                searchInput = null;
            }
            if (dialog != null) { dialog.dismiss(); dialog = null; }
        }
        void search(int mode) {
            if (data == null || disposed) return;
            dismissDialog();
            LinearLayout panel = new LinearLayout(activity);
            panel.setOrientation(VERTICAL);
            panel.setPadding(activity.dp(16), activity.dp(8), activity.dp(16), 0);
            EditText query = new EditText(activity);
            query.setSingleLine(true); query.setTextSize(16);
            query.setHint(t("Название зала MV", "MV hall name"));
            query.setContentDescription(t("Поиск зон второго этажа", "Search floor 2 event zones"));
            query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            panel.addView(query, new LayoutParams(-1, activity.dp(52)));
            TextView legend = activity.text(t("Зеленый — зоны; светлые блоки — прочие площади; синий — маршрут.",
                    "Green: event zones; pale blocks: other areas; blue: route."), 12, secondary);
            panel.addView(legend);
            TextView count = activity.text("", 12, secondary);
            count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            panel.addView(count);
            ArrayList<Poi> found = new ArrayList<>();
            ListView list = new ListView(activity);
            BaseAdapter adapter = new BaseAdapter() {
                @Override public int getCount() { return found.size(); }
                @Override public Object getItem(int p) { return found.get(p); }
                @Override public long getItemId(int p) { return p; }
                @Override public View getView(int p, View old, android.view.ViewGroup parent) {
                    TextView row = old instanceof TextView ? (TextView) old : activity.text("", 16, ink);
                    Poi poi = found.get(p);
                    row.setText((poi.number.isEmpty() ? "" : poi.number + ". ") + name(poi) + "\n" + category(poi));
                    row.setMinHeight(activity.dp(64));
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8));
                    return row;
                }
            };
            list.setAdapter(adapter);
            panel.addView(list, new LayoutParams(-1, 0, 1));
            Runnable filter = () -> {
                String term = normalize(query.getText().toString().trim());
                found.clear();
                for (Poi poi : data.pois.values()) {
                    if (normalize(poi.ru + " " + poi.en + " " + category(poi)).contains(term) || (!term.isEmpty() && poi.number.equals(term))) found.add(poi);
                }
                Collections.sort(found, (a, b) -> {
                    if (!a.number.isEmpty() && !b.number.isEmpty()) return Integer.compare(Integer.parseInt(a.number), Integer.parseInt(b.number));
                    if (a.number.isEmpty() != b.number.isEmpty()) return a.number.isEmpty() ? 1 : -1;
                    return name(a).compareToIgnoreCase(name(b));
                });
                adapter.notifyDataSetChanged();
                count.setText(found.isEmpty() ? t("Ничего не найдено", "No results") : t("Зон: ", "Zones: ") + found.size());
            };
            query.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filter.run(); }
                @Override public void afterTextChanged(Editable e) {}
            });
            query.setOnEditorActionListener((v, action, event) -> {
                if (action != EditorInfo.IME_ACTION_SEARCH) return false;
                InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.hideSoftInputFromWindow(query.getWindowToken(), 0);
                query.clearFocus(); return true;
            });
            list.setOnItemClickListener((parent, view, position, id) -> {
                Poi poi = found.get(position);
                dismissDialog();
                if (mode == 2) detail(poi); else choose(poi, mode);
            });
            dialog = new AlertDialog.Builder(activity).setTitle(mode == 0 ? t("Откуда", "From") : mode == 1 ? t("Куда", "To") : t("Зоны и сервисы", "Zones and services"))
                    .setView(panel).setNegativeButton(t("Закрыть", "Close"), (d, w) -> dismissDialog()).create();
            dialog.show();
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            dialog.getWindow().setLayout(-1, Math.max(activity.dp(220), (int) (getHeight() * .88f)));
            searchInput = query;
            filter.run();
        }
        void detail(Poi poi) {
            dismissDialog(); map.selected = poi; map.focus(poi.x, poi.y);
            dialog = new AlertDialog.Builder(activity).setTitle((poi.number.isEmpty() ? "" : poi.number + ". ") + name(poi))
                    .setMessage(poi.ru + " / " + poi.en + "\n" + category(poi) + t(" · этаж 2\nТочка — исходная подпись зала, не проверенный вход.\nСхема 2026 года; распределение залов на 2027 год не подтверждено.\nДоступность маршрута без ступеней не проверена.",
                            " · floor 2\nMarker: original room label, not a verified entrance.\n2026 reference plan; 2027 room assignments are not confirmed.\nStep-free route access is unverified."))
                    .setNeutralButton(t("Закрыть", "Close"), (d, w) -> dismissDialog())
                    .setNegativeButton(t("Отсюда", "From here"), (d, w) -> { dismissDialog(); choose(poi, 0); })
                    .setPositiveButton(t("Сюда", "To here"), (d, w) -> { dismissDialog(); choose(poi, 1); }).create();
            dialog.show();
        }
        void statusVoice(String text) {
            if (!disposed && status != null) status.setText(text);
        }
        void startNavigation() {
            if (route == null || route.nodes.size() < 2) return;
            navigating = true; progress = 0; map.progress = 0;
            voice.stop();
            voice.say(t("Ручной просмотр маршрута. Ваше положение не отслеживается.",
                    "Manual route review. Your position is not tracked."), "start");
            showSteps();
        }
        void announceProgress() {
            if (navigating) voice.say(guidance(progress), "point-" + progress);
        }
        String guidance(int index) {
            if (route == null) return "";
            if (index >= route.nodes.size() - 1) return t("Последняя точка модели: ", "Last model point: ") + name(to)
                    + t(". Это не подтверждение прибытия.", ". This does not confirm arrival.");
            // Raw grid stair-steps are not real maneuvers: never invent spoken turns.
            double remaining = 0;
            for (int i = index; i < route.nodes.size() - 1; i++) {
                EventVenueGraph.Node a = route.nodes.get(i), b = route.nodes.get(i + 1);
                remaining += Math.hypot(b.x - a.x, b.y - a.y);
            }
            return String.format(Locale.ROOT, t("Точка %d/%d · осталось %.1f м по модели. Следуйте линии; продвижение вручную.",
                    "Point %d/%d · %.1f model metres remaining. Follow the line; manual progress."),
                    index + 1, route.nodes.size(), remaining);
        }
        void showSteps() {
            if (route == null) return;
            dismissDialog();
            LinearLayout panel = new LinearLayout(activity);
            panel.setOrientation(VERTICAL);
            panel.setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(8));
            panel.addView(activity.text(t("Схематические подсказки. Прогресс отмечаете вы, не GPS. Сверяйтесь с указателями на месте.",
                    "Schematic guidance. You mark progress manually; this is not GPS. Check signs on site."), 13, secondary));
            TextView current = activity.text(guidance(progress), 17, ink);
            current.setPadding(0, activity.dp(12), 0, activity.dp(12));
            current.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            panel.addView(current);
            LinearLayout actions = new LinearLayout(activity);
            TextView previous = button(t("Назад", "Previous"), () -> {
                progress = Math.max(0, progress - 1); map.progress = progress;
                current.setText(guidance(progress)); map.focusRouteStep(); announceProgress();
            });
            TextView next = button(t("Далее вручную", "Next manually"), () -> {
                progress = Math.min(route.nodes.size() - 1, progress + 1); map.progress = progress;
                current.setText(guidance(progress)); map.focusRouteStep(); announceProgress();
            });
            actions.addView(previous, new LayoutParams(0, activity.dp(48), 1));
            actions.addView(next, new LayoutParams(0, activity.dp(48), 1));
            panel.addView(actions);
            ScrollView scroll = new ScrollView(activity);
            LinearLayout steps = new LinearLayout(activity); steps.setOrientation(VERTICAL);
            for (int i = 0; i < route.nodes.size(); i++) {
                final int step = i;
                TextView row = button(guidance(i), () -> {
                    progress = step; map.progress = step; map.focusRouteStep(); announceProgress(); dismissDialog();
                });
                row.setGravity(Gravity.CENTER_VERTICAL); row.setMinHeight(activity.dp(56));
                steps.addView(row, new LayoutParams(-1, -2));
            }
            scroll.addView(steps); panel.addView(scroll, new LayoutParams(-1, 0, 1));
            dialog = new AlertDialog.Builder(activity).setTitle(t("Маршрут по шагам", "Route steps"))
                    .setView(panel).setPositiveButton(t("К карте", "Back to map"), (d, w) -> dismissDialog()).create();
            dialog.show(); dialog.getWindow().setLayout(-1, (int) (getHeight() * .8f));
        }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            // Keep a usable map viewport in landscape and on compact screens.
            if (fields == null || footer == null) return;
            boolean compact = h < activity.dp(470);
            fields.setOrientation(compact ? HORIZONTAL : VERTICAL);
            fromButton.setLayoutParams(new LayoutParams(compact ? 0 : -1, activity.dp(48), compact ? 1 : 0));
            toButton.setLayoutParams(new LayoutParams(compact ? 0 : -1, activity.dp(48), compact ? 1 : 0));
            footer.setOrientation(compact ? HORIZONTAL : VERTICAL);
            zoom.setLayoutParams(new LayoutParams(compact ? 0 : -1, -2, compact ? 1 : 0));
            extras.setLayoutParams(new LayoutParams(compact ? 0 : -1, -2, compact ? 1 : 0));
        }
        @Override protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility != View.VISIBLE && voice != null) voice.stop();
        }
        @Override protected void onDetachedFromWindow() {
            disposed = true; generation++; navigating = false; voice.dispose(); dismissDialog();
            handler.removeCallbacksAndMessages(null); worker.shutdownNow();
            super.onDetachedFromWindow();
        }
    }

    private static final class MapView extends View {
        final Screen screen;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final ScaleGestureDetector pinch;
        Data data;
        Poi selected;
        EventVenueGraph.Route route;
        int progress = -1;
        float cx, cy, scale = 1, minimum = 1, lastX, lastY, downX, downY;
        boolean pinched, dragged;
        MapView(Screen screen) {
            super(screen.activity); this.screen = screen;
            setBackgroundColor(Color.WHITE);
            setContentDescription(screen.t("Карта второго этажа. Все зоны доступны в списке «Поиск / зоны». Выберите начало вручную.",
                    "Floor 2 map. All zones are available in Search / zones. Choose start manually."));
            pinch = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    float x = detector.getFocusX(), y = detector.getFocusY();
                    float wx = cx + (x - getWidth() / 2f) / scale, wy = cy - (y - getHeight() / 2f) / scale;
                    scale = clamp(scale * detector.getScaleFactor());
                    cx = wx - (x - getWidth() / 2f) / scale; cy = wy + (y - getHeight() / 2f) / scale;
                    pinched = true; invalidate(); return true;
                }
            });
        }
        float dp(float value) { return screen.activity.dp(value); }
        float clamp(float value) { return Math.max(minimum * .6f, Math.min(minimum * 24, value)); }
        void zoom(float factor) { if (data != null) { scale = clamp(scale * factor); invalidate(); } }
        void fit() {
            if (data == null || getWidth() == 0 || getHeight() == 0) return;
            cx = (data.bounds[0] + data.bounds[2]) / 2; cy = (data.bounds[1] + data.bounds[3]) / 2;
            minimum = .92f * Math.min(getWidth() / (data.bounds[2] - data.bounds[0]), getHeight() / (data.bounds[3] - data.bounds[1]));
            scale = minimum; invalidate();
        }
        void fitRoute() {
            if (route == null || route.nodes.size() < 2) { fit(); return; }
            float left = Float.MAX_VALUE, top = Float.MAX_VALUE, right = -Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
            for (EventVenueGraph.Node n : route.nodes) { left = Math.min(left, n.x); right = Math.max(right, n.x); top = Math.min(top, n.y); bottom = Math.max(bottom, n.y); }
            cx = (left + right) / 2; cy = (top + bottom) / 2;
            scale = clamp(.8f * Math.min(getWidth() / Math.max(10, right - left), getHeight() / Math.max(10, bottom - top)));
            invalidate();
        }
        void focus(float x, float y) { cx = x; cy = y; scale = clamp(minimum * 4); invalidate(); }
        void focusRouteStep() {
            if (route == null || progress < 0) return;
            EventVenueGraph.Node n = route.nodes.get(progress); focus(n.x, n.y);
        }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { fit(); }
        float sx(float x) { return getWidth() / 2f + (x - cx) * scale; }
        float sy(float y) { return getHeight() / 2f - (y - cy) * scale; }
        void text(Canvas canvas, String text, float x, float y, float size) {
            paint.setTextSize(dp(size)); paint.setTextAlign(Paint.Align.CENTER);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(3)); paint.setColor(Color.WHITE);
            canvas.drawText(text, x, y, paint);
            paint.setStyle(Paint.Style.FILL); paint.setColor(data.ink); canvas.drawText(text, x, y, paint);
        }
        void marker(Canvas canvas, float x, float y, String label, int color) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE); canvas.drawCircle(x, y, dp(11), paint);
            paint.setColor(color); canvas.drawCircle(x, y, dp(9), paint);
            paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(dp(11)); paint.setColor(Color.WHITE);
            canvas.drawText(label, x, y + dp(4), paint);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); if (data == null) return;
            canvas.save(); canvas.translate(getWidth() / 2f, getHeight() / 2f); canvas.scale(scale, -scale); canvas.translate(-cx, -cy);
            paint.setStyle(Paint.Style.FILL); paint.setColor(data.blocked);
            for (Path block : data.blocks) canvas.drawPath(block, paint);
            paint.setColor(0xfff0f6f3);
            for (Path path : data.circulation) canvas.drawPath(path, paint);
            paint.setColor(data.highlight);
            for (Zone zone : data.zones) canvas.drawPath(zone.path, paint);
            paint.setColor(data.wall);
            for (Path path : data.walls) canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(.12f);
            for (Path path : data.lines) canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.restore();
            if (route != null && !route.nodes.isEmpty()) {
                Path path = new Path();
                for (int i = 0; i < route.nodes.size(); i++) {
                    EventVenueGraph.Node n = route.nodes.get(i);
                    if (i == 0) path.moveTo(sx(n.x), sy(n.y)); else path.lineTo(sx(n.x), sy(n.y));
                }
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeJoin(Paint.Join.ROUND); paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(Color.WHITE); paint.setStrokeWidth(dp(7)); canvas.drawPath(path, paint);
                paint.setColor(data.route); paint.setStrokeWidth(dp(4)); canvas.drawPath(path, paint);
            }
            ArrayList<RectF> occupied = new ArrayList<>();
            for (Zone zone : data.zones) {
                boolean numberedOverview = !zone.poi.number.isEmpty() && scale < minimum * 2;
                String label = numberedOverview ? zone.poi.number
                        : (zone.poi.number.isEmpty() ? "" : zone.poi.number + ". ") + screen.name(zone.poi);
                float x = sx(zone.x), y = sy(zone.y);
                paint.setTextSize(dp(11));
                RectF box = new RectF(x - paint.measureText(label) / 2 - dp(2), y - dp(13),
                        x + paint.measureText(label) / 2 + dp(2), y + dp(4));
                boolean collision = false;
                for (RectF existing : occupied) if (RectF.intersects(existing, box)) { collision = true; break; }
                // Overview numbers match the named, searchable legend; zoom reveals full names.
                if (!collision || numberedOverview || zone.poi == selected) {
                    text(canvas, label, x, y, 11);
                    occupied.add(box);
                }
            }
            for (Poi poi : data.pois.values()) {
                boolean hasZone = false;
                for (Zone zone : data.zones) if (zone.poi == poi) { hasZone = true; break; }
                if (hasZone && poi != selected) continue;
                float x = sx(poi.x), y = sy(poi.y);
                if (x < -dp(24) || x > getWidth() + dp(24) || y < -dp(24) || y > getHeight() + dp(24)) continue;
                marker(canvas, x, y, poi == selected ? "*" : "i", data.ink);
                if (scale >= minimum * 2 || poi == selected) {
                    String label = screen.name(poi);
                    paint.setTextSize(dp(11));
                    RectF box = new RectF(x - paint.measureText(label) / 2, y + dp(12), x + paint.measureText(label) / 2, y + dp(29));
                    boolean collision = false;
                    for (RectF existing : occupied) if (RectF.intersects(existing, box)) { collision = true; break; }
                    if (!collision || poi == selected) { text(canvas, label, x, y + dp(25), 11); occupied.add(box); }
                }
            }
            if (screen.from != null) marker(canvas, sx(screen.from.x), sy(screen.from.y), "A", data.route);
            if (screen.to != null) marker(canvas, sx(screen.to.x), sy(screen.to.y), "B", data.route);
            if (route != null && progress >= 0) {
                EventVenueGraph.Node node = route.nodes.get(progress);
                paint.setStyle(Paint.Style.STROKE); paint.setColor(data.route); paint.setStrokeWidth(dp(2));
                canvas.drawCircle(sx(node.x), sy(node.y), dp(15), paint);
            }
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        @Override public boolean onTouchEvent(MotionEvent event) {
            pinch.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    downX = lastX = event.getX(); downY = lastY = event.getY(); pinched = dragged = false; return true;
                case MotionEvent.ACTION_POINTER_DOWN: pinched = true; return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(event.getX() - downX, event.getY() - downY) > dp(8)) dragged = true;
                    if (!pinch.isInProgress() && event.getPointerCount() == 1 && !pinched) {
                        cx -= (event.getX() - lastX) / scale; cy += (event.getY() - lastY) / scale; invalidate();
                    }
                    lastX = event.getX(); lastY = event.getY(); return true;
                case MotionEvent.ACTION_UP:
                    if (!pinched && !dragged && data != null) {
                        performClick(); Poi nearest = null; double best = dp(28);
                        for (Poi poi : data.pois.values()) {
                            double distance = Math.hypot(sx(poi.x) - event.getX(), sy(poi.y) - event.getY());
                            if (distance < best) { best = distance; nearest = poi; }
                        }
                        if (nearest != null) screen.detail(nearest);
                    }
                    getParent().requestDisallowInterceptTouchEvent(false); return true;
                case MotionEvent.ACTION_CANCEL: getParent().requestDisallowInterceptTouchEvent(false); return true;
                default: return true;
            }
        }
    }
}

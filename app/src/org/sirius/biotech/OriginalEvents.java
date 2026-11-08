package org.sirius.biotech;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Locale;

/** Local fixture bindings for the retained event XML and original item/header classes. */
public final class OriginalEvents {
    private static final String NAME = "Sirius Biotech 2027";
    private static final String ID = "local2027";
    private static final String DATES = "20–22 мая 2027";
    private static final String PLACE = "Федеральная территория «Сириус», Олимпийский пр. 1";

    private OriginalEvents() {}

    public static View list(MainActivity activity) {
        try {
            FrameLayout root = screen(activity, "events_view");
            root.setBackgroundColor(0xffd6d6e2);
            required(root, "loadingView").setVisibility(View.GONE);
            required(root, "advertisementView").setVisibility(View.GONE);
            ImageView logo = (ImageView) required(root, "headerImageView");
            asset(logo, "menu-logo-2027.png");
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            ViewGroup.LayoutParams logoSize=logo.getLayoutParams();logoSize.width=activity.dp(160);logo.setLayoutParams(logoSize);logo.setPadding(0,activity.dp(10),0,activity.dp(10));
            // EventsViewLogoBehavior.onLayoutChild returns true without laying out the
            // child. The original host supplies that layout; this plain host does not.
            // Remove only this behavior, retaining the original logo's XML geometry.
            clearLogoBehavior(logo);
            navigation(required(root, "toolbar"), null);

            View recycler = required(root, "eventRecyclerView");
            recycler.setVisibility(View.GONE);
            ViewGroup coordinator = (ViewGroup) recycler.getParent();
            FrameLayout fixture = new FrameLayout(root.getContext());
            // Retain the XML scrolling behavior and app-bar offset. A single local
            // fixture needs no production adapter, differ, presenter, or image loader.
            coordinator.addView(fixture, coordinator.indexOfChild(recycler) + 1,
                    recycler.getLayoutParams());
            View row = LayoutInflater.from(root.getContext()).inflate(
                    resource(root.getContext(), "events_item_view", "layout"), fixture, false);
            if (!row.getClass().getName().equals(
                    "com.eventicious.multievent.ui.list.EventItemView")) {
                throw new IllegalStateException("events_item_view no longer uses EventItemView");
            }
            // setEvent(a) always accesses Picasso and cancels Coil requests, even
            // with null URLs. Bind its original children instead; never invent Dates.
            text(row, "titleView", NAME);
            text(row, "timeValueView", DATES);
            text(row, "placeValueView", PLACE);
            required(row, "statusView").setVisibility(View.GONE);
            required(row, "checkView").setVisibility(View.GONE);
            eventIcon((ImageView) required(row, "eventIconView"), activity);
            ImageView rowBackground=(ImageView)required(row,"backgroundView");
            rowBackground.setImageDrawable(null);
            rowBackground.setBackgroundResource(resource(root.getContext(), "image_background", "drawable"));
            round(row, activity.dp(8));
            round(rowBackground, activity.dp(6));
            bindIcon(row, "timeIconView", "icon_clock_16", 0xff4375c9);
            bindIcon(row, "placeIconView", "icon_pin_16", 0xff4375c9);
            FrameLayout.LayoutParams rowParams = new FrameLayout.LayoutParams(-1, -2);
            int margin = activity.dp(8);
            rowParams.setMargins(margin, activity.dp(2), margin, activity.dp(2));
            LinearLayout eventList=new LinearLayout(root.getContext());eventList.setOrientation(LinearLayout.VERTICAL);TextView category=activity.bold("ru".equals(activity.getResources().getConfiguration().getLocales().get(0).getLanguage())?"ПРЕДСТОЯЩИЕ СОБЫТИЯ":"UPCOMING EVENTS",13);category.setTextColor(android.graphics.Color.WHITE);category.setBackgroundColor(0xff929fa8);eventList.addView(category,new LinearLayout.LayoutParams(-1,-2));fixture.addView(eventList,new FrameLayout.LayoutParams(-1,-2));LinearLayout.LayoutParams itemParams=new LinearLayout.LayoutParams(-1,-2);itemParams.setMargins(margin,activity.dp(6),margin,activity.dp(6));eventList.addView(row,itemParams);
            android.graphics.drawable.GradientDrawable searchBackground=new android.graphics.drawable.GradientDrawable();searchBackground.setColor(0xffc4c5d0);searchBackground.setStroke(activity.dp(1),0xffc4c5d0);searchBackground.setCornerRadius(activity.dp(8));View searchSurface=required(root,"searchButton");searchSurface.setBackgroundTintList(null);searchSurface.setBackground(searchBackground);searchSurface.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){v.post(()->{v.setBackgroundTintList(null);v.setBackground(searchBackground);});}public void onViewDetachedFromWindow(View v){}});
            row.setContentDescription(activity.tr(NAME) + ". " + activity.tr(DATES) + ". " + activity.tr(PLACE) + ". " + ID);
            row.setOnClickListener(view -> activity.show("about"));

            TextView empty = (TextView) required(root, "emptyView");
            empty.setText(activity.getString(resource(activity, "events_placeholder", "string")));
            View search = required(root, "searchButton");
            TextView searchLabel = firstText(search);
            if (searchLabel == null) throw new IllegalStateException("Missing search label");
            Drawable searchIcon=activity.getDrawable(resource(activity,"icon_search_16","drawable")).mutate();
            searchIcon.setTint(activity.ink);
            searchLabel.setCompoundDrawablesRelativeWithIntrinsicBounds(searchIcon,null,null,null);
            searchLabel.setCompoundDrawablePadding(activity.dp(4));
            search.setFocusable(true);
            row.setFocusable(true);
            Runnable filter = () -> {
                String query = activity.query.trim().toLowerCase(Locale.ROOT);
                boolean matches = query.isEmpty()
                        || (NAME + " " + activity.tr(NAME) + " " + ID).toLowerCase(Locale.ROOT).contains(query);
                fixture.setVisibility(matches ? View.VISIBLE : View.GONE);
                empty.setVisibility(matches ? View.GONE : View.VISIBLE);
                searchLabel.setText(query.isEmpty() ? activity.getString(resource(activity,"events_search_hint","string"))
                        : activity.tr("Search") + ": " + activity.query + " (" + activity.tr("Long press to clear") + ")");
            };
            search.setOnClickListener(view -> activity.input("Search",
                    activity.getString(resource(activity,"events_search_hint","string")), query -> {
                        activity.query = query;
                        filter.run();
                    }));
            search.setOnLongClickListener(view -> {
                activity.query = "";
                filter.run();
                return true;
            });
            filter.run();
            return root;
        } catch (Exception | LinkageError error) {
            throw failure("event list", error);
        }
    }

    public static View details(MainActivity activity) {
        try {
            FrameLayout root = screen(activity, "event_view");
            ImageView backdrop=(ImageView)required(root,"backgroundView");
            android.graphics.drawable.Drawable art;
            try(InputStream stream=activity.getAssets().open("sirius_theme.webp")){art=Drawable.createFromStream(stream,null);}
            android.graphics.drawable.GradientDrawable surface=new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xffeeeef4,0xffeeeef4,0xddeeeef4});
            android.graphics.drawable.LayerDrawable layers=new android.graphics.drawable.LayerDrawable(new Drawable[]{art,surface});
            backdrop.setImageDrawable(layers);backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
            navigation(required(root, "toolbar"), () -> activity.show("events"));
            text(root, "toolbarTitleView", NAME);
            View header = required(root, "headerView");
            if (!header.getClass().getName().equals(
                    "com.eventicious.multievent.ui.details.EventHeaderView")) {
                throw new IllegalStateException("event_view no longer uses EventHeaderView");
            }
            // EventHeaderView.setEvent initializes Coil even for null artwork.
            text(header, "titleView", NAME);
            eventIcon((ImageView) required(header, "iconView"), activity);
            reveal(header);
            bindHeaderCollapse(root, header);
            text(root, "dateView", DATES);
            text(root, "locationView", PLACE);
            reveal(required(root, "dateLayout"));
            reveal(required(root, "eventLayout"));
            text(root, "descriptionView", "Your personal guide to Sirius Biotech 2027. Explore the programme, discover biotechnology projects, find researchers and partners, arrange meetings and stay up to date with the event.");
            reveal(required(root, "descriptionView"));
            LinearLayout buttons = (LinearLayout) required(root, "eventButtonsView");
            // The original EventPresenter creates MaterialButtons in this exact panel.
            TextView open = (TextView) Class.forName(
                    "com.google.android.material.button.MaterialButton")
                    .getConstructor(Context.class, AttributeSet.class)
                    .newInstance(root.getContext(), null);
            open.setText(activity.tr("Open"));
            open.setOnClickListener(view -> activity.show(
                    activity.store.getBoolean("signedIn", false) ? "home" : "welcome"));
            buttons.addView(open, new LinearLayout.LayoutParams(-1, -2));
            View panel = required(root, "buttonsPanel");
            reveal(panel);
            View card = required(root, "cardContent");
            panel.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
                int padding = Math.max(0, b - t);
                if (card.getPaddingBottom() != padding) {
                    card.setPadding(card.getPaddingLeft(), card.getPaddingTop(),
                            card.getPaddingRight(), padding);
                }
            });
            return root;
        } catch (Exception | LinkageError error) {
            throw failure("event details", error);
        }
    }

    private static FrameLayout screen(MainActivity activity, String layout) {
        Context context = new ContextThemeWrapper(activity,
                resource(activity, "Theme.Eventicious.App", "style"));
        // Both screens are <merge>. A plain FrameLayout intentionally has no
        // Flow lifecycle, original root casts, Koin, or production presenter.
        FrameLayout root = new FrameLayout(context);
        root.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        android.util.TypedValue surface = new android.util.TypedValue();
        if (context.getTheme().resolveAttribute(resource(context, "colorSurface", "attr"),
                surface, true)) {
            if (surface.resourceId != 0) root.setBackgroundResource(surface.resourceId);
            else root.setBackgroundColor(surface.data);
        }
        LayoutInflater.from(context).inflate(resource(context, layout, "layout"), root, true);
        return root;
    }

    private static void round(View view, int radius) {
        view.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline outline) {
                outline.setRoundRect(0,0,v.getWidth(),v.getHeight(),radius);
            }
        });
        view.setClipToOutline(true);
    }

    private static void bindIcon(View root, String name, String drawable, int tint) {
        ImageView icon=(ImageView)required(root,name);
        icon.setImageResource(resource(root.getContext(),drawable,"drawable"));
        icon.setColorFilter(tint);
        icon.setVisibility(View.VISIBLE);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private static void clearLogoBehavior(View logo) throws Exception {
        Object params = logo.getLayoutParams();
        params.getClass().getMethod("b", Class.forName("y3.b"))
                .invoke(params, new Object[]{null});
        logo.requestLayout();
    }

    private static void eventIcon(ImageView image, MainActivity activity) throws Exception {
        asset(image, "sirius_icon.jpg");
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ViewGroup.LayoutParams params = image.getLayoutParams();
        params.width = params.height = activity.dp(60);
        image.setLayoutParams(params);
        image.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), activity.dp(14));
            }
        });
        image.setClipToOutline(true);
    }

    private static void bindHeaderCollapse(View root, View header) throws Exception {
        View appbar = required(root, "appbarLayout");
        View toolbar = required(root, "toolbar");
        TextView title = (TextView) required(root, "toolbarTitleView");
        // Reuse the original shell's offset listener, not its Flow/Koin presenter.
        Object listener = Class.forName("sa.c").getConstructor(
                Class.forName("com.eventicious.toolbar.Toolbar"), TextView.class, TextView.class)
                .newInstance(toolbar, title, (TextView) required(header, "titleView"));
        appbar.getClass().getMethod("a", Class.forName("com.google.android.material.appbar.i"))
                .invoke(appbar, listener);
        root.post(() -> {
            try {
                listener.getClass().getMethod("onOffsetChanged", appbar.getClass(), int.class)
                        .invoke(listener, appbar, 0);
            } catch (Exception error) {
                android.util.Log.e("SiriusOriginalUI", "Header offset initialization failed", error);
            }
        });
    }

    private static void navigation(View toolbar, Runnable action) throws Exception {
        toolbar.getClass().getMethod("setNavigationOnClickListener", View.OnClickListener.class)
                .invoke(toolbar, action == null ? null
                        : (View.OnClickListener) view -> action.run());
        if (action == null) {
            toolbar.getClass().getMethod("setNavigationIcon", Drawable.class)
                    .invoke(toolbar, new Object[]{null});
        }
    }

    private static int resource(Context context, String name, String kind) {
        int id = context.getResources().getIdentifier(name, kind, context.getPackageName());
        if (id == 0) throw new IllegalStateException("Missing original " + kind + ": " + name);
        return id;
    }

    private static View required(View root, String name) {
        View view = root.findViewById(resource(root.getContext(), name, "id"));
        if (view == null) throw new IllegalStateException("Missing original child: " + name);
        return view;
    }

    private static void text(View root, String name, String value) {
        ((TextView) required(root, name)).setText(UiText.translate(root.getContext(),value));
    }

    private static void reveal(View view) {
        view.setVisibility(View.VISIBLE);
        view.setAlpha(1f);
    }

    private static TextView firstText(View view) {
        if (view instanceof TextView) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView text = firstText(group.getChildAt(index));
                if (text != null) return text;
            }
        }
        return null;
    }

    private static void asset(ImageView image, String name) throws Exception {
        try (InputStream stream = image.getContext().getAssets().open(name)) {
            Drawable drawable = Drawable.createFromStream(stream, name);
            if (drawable == null) throw new IllegalStateException("Cannot decode local asset: " + name);
            image.setImageDrawable(drawable);
        }
    }

    private static IllegalStateException failure(String screen, Throwable error) {
        Throwable cause = error instanceof InvocationTargetException
                && error.getCause() != null ? error.getCause() : error;
        return new IllegalStateException("Cannot bind retained original " + screen
                + " without production services. No replacement UI was created.", cause);
    }
}

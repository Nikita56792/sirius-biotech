package org.sirius.biotech;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

/** Offline bindings for the retained original widgets; never substitutes imitation views. */
public final class OriginalContent {
    private static final String TAG = "SiriusOriginalUI";
    private static final String MEETINGS = "com.eventicious.meetings.ui.list.";
    private static final String[] UNUSED_MESSAGE_VIEWS = {
        "replyIndicator", "replyIcon", "replyImage", "replyFile", "replyAuthor",
        "replyText", "replyOverlay", "attachedImage", "attachedFileBackground",
        "attachedFileIcon", "attachedFileProgress", "attachedFileTitle", "attachedFileSize",
        "linkPreviewIndicator", "linkPreviewProvider", "linkPreviewTitle",
        "linkPreviewDescription", "linkPreviewImage", "linkPreviewImageOverlay",
        "linkPreviewPlayButton", "linkPreviewDurationView"
    };

    private OriginalContent() {}

    public static View chatRow(MainActivity activity, int index) {
        try {
            Context context = themed(activity);
            Class<?> type = Class.forName("com.eventicious.chats.ui.chats.b0");
            View row = (View) type.getConstructor(Context.class).newInstance(context);
            String name = activity.tr(activity.names[index]);
            List<String> drafts = activity.entries("chat" + index);
            String preview = drafts.isEmpty() ? activity.tr("Demo conversation - Not connected")
                    : drafts.get(drafts.size() - 1);
            type.getMethod("setName", CharSequence.class).invoke(row, name);
            type.getMethod("setMessage", CharSequence.class).invoke(row, preview);
            type.getMethod("setDate", CharSequence.class).invoke(row, activity.tr("Local draft"));
            type.getMethod("setUnread", boolean.class).invoke(row, false);
            type.getMethod("setNotificationsEnabled", boolean.class).invoke(row, true);
            required(row, "onlineIndicator").setVisibility(View.GONE);
            placeholder(row, "image", "icon_attendee_56");
            row.setContentDescription(name + ". " + preview + ". " + activity.tr("Local draft"));
            return row;
        } catch (Exception | LinkageError error) {
            throw failure("chat row", error);
        }
    }

    public static View message(MainActivity activity, String text) {
        try {
            Object binding = messageBinding(themed(activity), 1);
            View row = bindingView(binding, "n");
            ((TextView) bindingView(binding, "b")).setText(text);
            ((TextView) bindingView(binding, "h")).setText(activity.tr("Local draft"));
            ((TextView) required(row, "attendeeName")).setText(activity.tr("You"));
            bindTextOnly(binding, row);
            // date is baseline-constrained to likeButton in message_outgoing. Keep its
            // original footer geometry without exposing an unsupported like action.
            bindingView(binding, "j").setVisibility(View.INVISIBLE);
            placeholder(row, "attendeeImage", "icon_attendee_56");
            row.setContentDescription(activity.tr("You") + ". " + text + ". " + activity.tr("Local draft, not delivered"));
            return row;
        } catch (Exception | LinkageError error) {
            throw failure("outgoing message", error);
        }
    }

    public static View post(MainActivity activity, String title, String text) {
        try {
            Object binding = messageBinding(themed(activity), 0);
            View row = bindingView(binding, "n");
            ((TextView) required(row, "attendeeName")).setText(activity.tr("Sirius Biotech 2027"));
            ((TextView) bindingView(binding, "h")).setText(activity.tr("Offline preview - Sample news"));
            // feed_post wraps the body TextView in the original ExpandableTextLayout.
            // Its setter also updates the original expansion control.
            Object expandable = binding.getClass().getMethod("A").invoke(binding);
            expandable.getClass().getMethod("setText", CharSequence.class)
                    .invoke(expandable, activity.tr(title) + "\n\n" + activity.tr(text));
            bindTextOnly(binding, row);
            placeholder(row, "attendeeImage", "icon_attendee_56");
            row.setContentDescription(activity.tr(title) + ". " + activity.tr(text) + ". " + activity.tr("Sample news"));
            return row;
        } catch (Exception | LinkageError error) {
            throw failure("feed post", error);
        }
    }

    public static View partner(MainActivity activity, String name, String description) {
        try {
            Context context = themed(activity);
            Class<?> type = Class.forName("fb.e");
            Object binding = type.getMethod("a", LayoutInflater.class)
                    .invoke(null, LayoutInflater.from(context));
            View row = (View) type.getMethod("getRoot").invoke(binding);
            TextView text = (TextView) row.findViewById(android.R.id.text1);
            ImageView icon = (ImageView) row.findViewById(android.R.id.icon);
            if (text == null || icon == null) {
                throw new IllegalStateException("Original catalog_item_text children missing");
            }
            text.setText(activity.tr(name) + "\n" + activity.tr(description));
            // No supplied partner artwork: do not imply a real sponsor logo.
            icon.setVisibility(View.GONE);
            row.setContentDescription(activity.tr(name) + ". " + activity.tr(description));
            return row;
        } catch (Exception | LinkageError error) {
            throw failure("partner catalog item", error);
        }
    }

    public static View meeting(MainActivity activity, String draft) {
        try {
            Context context = themed(activity);
            Class<?> dateType = Class.forName(MEETINGS + "b");
            Class<?> infoType = Class.forName(MEETINGS + "c");
            Class<?> rowType = Class.forName(MEETINGS + "e");
            Drawable calendar = drawable(context, "icon_calendar_16");
            Drawable clock = drawable(context, "icon_clock_16");
            Object date = dateType.getConstructor(String.class, Drawable.class,
                    String.class, Drawable.class).newInstance("", calendar,
                    "", clock);
            String[] parts = draft.split(" · ", 2);
            String[] name = activity.tr(parts[0].trim()).split("\\s+", 2);
            Object info = infoType.getConstructor(String.class, String.class,
                    String.class, String.class, dateType).newInstance(name[0],
                    name.length > 1 ? name[1] : "", parts.length > 1 ? parts[1] : activity.tr("Meeting"),
                    "", date);
            View row = (View) rowType.getConstructor(Context.class).newInstance(context);
            rowType.getMethod("setInfo", infoType).invoke(row, info);
            rowType.getMethod("setAuthorized", boolean.class).invoke(row, false);
            rowType.getMethod("setUnseen", boolean.class).invoke(row, false);
            required(row, "statusView").setVisibility(View.GONE);
            placeholder(row, "attendeeAvatarView", "icon_attendee_56");
            row.setContentDescription(draft);
            return row;
        } catch (Exception | LinkageError error) {
            throw failure("meeting row", error);
        }
    }

    private static Context themed(MainActivity activity) {
        return new ContextThemeWrapper(activity,
                resource(activity, "Theme.Eventicious.App", "style"));
    }

    private static Object messageBinding(Context context, int variant) throws Exception {
        // A detached, themed parent supplies inflation context and original LayoutParams only.
        ViewGroup parent = new FrameLayout(context);
        return Class.forName("ac.b").getConstructor(ViewGroup.class, int.class)
                .newInstance(parent, variant);
    }

    private static View bindingView(Object binding, String method) throws Exception {
        return (View) binding.getClass().getMethod(method).invoke(binding);
    }

    private static void bindTextOnly(Object binding, View row) throws Exception {
        for (String name : UNUSED_MESSAGE_VIEWS) required(row, name).setVisibility(View.GONE);
        bindingView(binding, "s").setVisibility(View.GONE);
        bindingView(binding, "j").setVisibility(View.GONE);
        bindingView(binding, "t").setVisibility(View.GONE);
        bindingView(binding, "x").setVisibility(View.GONE);
    }

    private static View required(View root, String name) {
        View child = root.findViewById(resource(root.getContext(), name, "id"));
        if (child == null) throw new IllegalStateException("Original view missing: " + name);
        return child;
    }

    private static void placeholder(View root, String viewName, String drawableName) {
        ((ImageView) required(root, viewName)).setImageResource(
                resource(root.getContext(), drawableName, "drawable"));
    }

    private static Drawable drawable(Context context, String name) {
        Drawable drawable = context.getDrawable(resource(context, name, "drawable"));
        if (drawable == null) throw new IllegalStateException("Original drawable missing: " + name);
        // MeetingAttendeeInfoView draws these directly onto its canvas, not in ImageViews.
        drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        return drawable;
    }

    private static int resource(Context context, String name, String type) {
        int id = context.getResources().getIdentifier(name, type, context.getPackageName());
        if (id == 0) throw new IllegalStateException("Original resource missing: " + type + "/" + name);
        return id;
    }

    private static IllegalStateException failure(String widget, Throwable error) {
        Throwable cause = error;
        while (cause instanceof InvocationTargetException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = "Original " + widget + " inflation/binding failed";
        Log.e(TAG, message, cause);
        return new IllegalStateException(message, cause);
    }
}

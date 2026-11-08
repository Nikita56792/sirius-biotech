package org.sirius.biotech;

import android.app.AlertDialog;
import android.content.Context;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Retained message widgets and inline composer with authenticated app-owned transport. */
final class ServerChatScreen {
    private static final String[] TEXT_ONLY_HIDDEN = {
        "replyIndicator", "replyIcon", "replyImage", "replyFile", "replyAuthor",
        "replyText", "replyOverlay", "attachedImage", "attachedFileBackground",
        "attachedFileIcon", "attachedFileProgress", "attachedFileTitle", "attachedFileSize",
        "linkPreviewIndicator", "linkPreviewProvider", "linkPreviewTitle",
        "linkPreviewDescription", "linkPreviewImage", "linkPreviewImageOverlay",
        "linkPreviewPlayButton", "linkPreviewDurationView"
    };

    static View create(MainActivity activity, String chatId, String title) throws Exception {
        return new Chat(activity, chatId, title).root;
    }

    private static final class Chat implements View.OnAttachStateChangeListener {
        final MainActivity activity;
        final String path, title, chatId;
        final Context context;
        final FrameLayout root;
        final View messages, scroll;
        final OriginalRecycler messageAdapter;
        String lastDate="";
        long acknowledged;
        final View composer;
        final Class<?> composerClass;
        final EditText input;
        final TextView status;
        final java.util.TreeSet<Long> displayed = new java.util.TreeSet<>();
        volatile boolean attached;
        volatile int generation;
        boolean loading, sending, initial = true;
        int offset;
        long inputRevision;
        String uncertainText;
        String pendingMessageId, photoId;
        byte[] pendingPhoto;
        final java.util.function.Consumer<android.net.Uri> photoSelected = uri -> choosePhoto(uri);
        final Runnable poll = () -> load();

        Chat(MainActivity a, String chatId, String title) throws Exception {
            activity = a;
            this.title = title;
            this.chatId = chatId;
            path = "/chats/" + android.net.Uri.encode(chatId) + "/messages";
            context = OriginalUi.theme(a);
            root = new FrameLayout(context);
            root.setBackgroundColor(a.bg);OriginalUi.insets(root);
            OriginalUi.inflate(context,"chat_view",root);
            View toolbar=required(root,"toolbar");
            OriginalUi.toolbar(a,toolbar,title,()->a.show("Messages"),null,null);
            View messageHost=required(root,"messagesView");
            messages=required(messageHost,"itemsView");scroll=messages;
            OriginalUi.list(messages,1,java.util.Collections.emptyList());
            messageAdapter=new OriginalRecycler(java.util.Collections.emptyList());
            OriginalUi.call(messages,"setAdapter",new Class<?>[]{OriginalUi.cls("b8.x0")},messageAdapter);
            status=(TextView)required(messageHost,"noConnectionView");status.setText("");status.setVisibility(View.GONE);
            status.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void afterTextChanged(android.text.Editable e){}public void onTextChanged(CharSequence s,int start,int before,int count){status.setVisibility(count>0?View.VISIBLE:View.GONE);status.setAlpha(1);}});
            View down=required(messageHost,"scrollDownView");
            required(messageHost,"scrollDownButton").setOnClickListener(v->scrollBottom());
            messages.getViewTreeObserver().addOnScrollChangedListener(()->down.setVisibility(atBottom()?View.GONE:View.VISIBLE));
            required(root,"operationProgressView").setVisibility(View.GONE);
            composerClass = Class.forName("com.eventicious.messaging.MessagesInputView");
            composer = required(messageHost,"input");
            composerClass.getMethod("setAnonymousButtonVisible", boolean.class).invoke(composer, false);
            composerClass.getMethod("setAttachmentButtonVisible", boolean.class).invoke(composer, true);
            required(composer,"pickMediaButton").setOnClickListener(v->{IntentPicker.open(activity);});
            input = (EditText) required(composer, "input");
            required(composer,"sendButton").setOnClickListener(v->submit(input.getText().toString()));
            input.setHint(label("Message", "Сообщение"));
            Class<?> listener = Class.forName("com.eventicious.messaging.q");
            Object callback = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, (p, m, args) -> {
                if (m.getDeclaringClass() == Object.class) {
                    if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                    if (m.getName().equals("equals")) return p == args[0];
                    return "AuthenticatedMessageListener";
                }
                if (m.getName().equals("a")) {
                    inputRevision++;
                    updateComposer();
                }
                if (m.getName().equals("b") && args != null && args.length > 0) submit(args[0].toString());
                return null;
            });
            composerClass.getMethod("setListener", listener).invoke(composer, callback);
            updateComposer();
            root.addOnAttachStateChangeListener(this);
        }

        String label(String en, String ru) {
            return "ru".equals(activity.getResources().getConfiguration().getLocales().get(0).getLanguage()) ? ru : en;
        }

        boolean active(int token) {
            return attached && generation == token && !activity.isFinishing() && !activity.isDestroyed();
        }

        @Override public void onViewAttachedToWindow(View view) {
            attached = true;
            activity.chatPhotoSelected=photoSelected;
            updateComposer();
            if(initial){
                loading=true;final int token=generation;
                activity.network.execute(()->{JSONArray saved=activity.api.cachedMessages(path);
                    activity.runOnUiThread(()->{loading=false;if(!active(token))return;
                        try{for(int i=0;i<saved.length();i++)appendMessage(saved.getJSONObject(i));}
                        catch(Exception | LinkageError error){android.util.Log.w("SiriusChat","Unable to display cached messages",error);}
                        load();
                    });
                });
            }else load();
        }

        @Override public void onViewDetachedFromWindow(View view) {
            attached = false;
            if(activity.chatPhotoSelected==photoSelected)activity.chatPhotoSelected=null;
            generation++;
            root.removeCallbacks(poll);
        }

        void updateComposer() {
            try {
                composerClass.getMethod("setSendButtonEnabled", boolean.class)
                        .invoke(composer, attached && !sending && (!input.getText().toString().trim().isEmpty() || pendingPhoto!=null));
                composerClass.getMethod("setProgressVisible", boolean.class).invoke(composer, sending);
                // The original progress setter disables input; keep drafts editable in flight.
                input.setEnabled(true);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Original composer binding failed", error);
            }
        }

        void load() {
            if (!attached || loading) return;
            root.removeCallbacks(poll);
            loading = true;
            final int token = generation;
            final int start = offset;
            activity.network.execute(() -> {
                ArrayList<JSONObject> batch = new ArrayList<>();
                boolean more = false, savedRead = false;
                Exception failure = null;
                try {
                    // The server returns oldest-first OFFSET pages, not the latest 100.
                    // Retain the consumed offset and drain pages before the next timed poll.
                    for (int page = 0; page < 10 && active(token); page++) {
                        JSONArray items = activity.api.request("GET", path + "?limit=100&offset=" + (start + batch.size()), null)
                                .getJSONArray("items");
                        savedRead |= activity.api.lastRequestOffline();
                        for (int i = 0; i < items.length(); i++) batch.add(items.getJSONObject(i));
                        more = items.length() == 100;
                        if (!more) break;
                    }
                } catch (Exception error) {
                    failure = error;
                }
                final boolean hasMore = more, fromCache = savedRead;
                final Exception problem = failure;
                activity.runOnUiThread(() -> {
                    loading = false;
                    if (!active(token)) {
                        if (attached) load();
                        return;
                    }
                    boolean follow = initial || atBottom();
                    boolean changed = false;
                    try {
                        for (JSONObject message : batch) {
                            long id = message.getLong("id");
                            changed |= appendMessage(message);
                            offset++;
                        }
                        if (problem instanceof org.sirius.biotech.data.ApiClient.AuthenticationException) {
                            root.removeCallbacks(poll);
                            activity.clearAccount();
                            activity.show("login");
                            return;
                        }
                        if (problem != null) {
                            status.setText(label("Unable to refresh messages. Retrying shortly.",
                                    "Не удалось обновить сообщения. Повторим позже."));
                        } else if (!sending && uncertainText == null) status.setText(fromCache ? label("Saved messages · offline", "Сохранённые сообщения · нет сети") : "");
                    } catch (Exception | LinkageError error) {
                        android.util.Log.e("SiriusOriginalUI", "Original message binding failed", error);
                        status.setText(label("Unable to display original message layout.",
                                "Не удалось показать исходный макет сообщения."));
                        root.postDelayed(poll, 5000);
                        return;
                    }
                    if (changed && follow) scroll.post(() -> {
                        if (active(token)) scrollBottom();
                    });
                    if (!hasMore && problem == null) initial = false;
                    if(problem==null&&!fromCache&&!displayed.isEmpty()&&displayed.last()>acknowledged){final long read=displayed.last();acknowledged=read;activity.network.execute(()->{try{activity.api.request("PUT",path.replace("/messages","/state"),new JSONObject().put("last_read",read).put("hidden",false));}catch(Exception error){acknowledged=0;}});}
                    root.postDelayed(poll, hasMore && problem == null ? 0 : 5000);
                });
            });
        }

        void choosePhoto(android.net.Uri uri){
            if(!attached||sending)return;final int token=generation;sending=true;updateComposer();status.setText(label("Preparing photo…","Обработка фото…"));
            activity.network.execute(()->{byte[] jpeg=null;Exception problem=null;try{String type=activity.getContentResolver().getType(uri);if(!"image/jpeg".equals(type)&&!"image/png".equals(type)&&!"image/webp".equals(type))throw new java.io.IOException("Unsupported image");java.io.ByteArrayOutputStream raw=new java.io.ByteArrayOutputStream();try(java.io.InputStream in=activity.getContentResolver().openInputStream(uri)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(raw.size()+n>ProfilePhotoInput.MAX_SOURCE)throw new java.io.IOException("Photo exceeds 20 MiB");raw.write(b,0,n);}}android.graphics.Bitmap image=ProfilePhotoInput.decode(raw.toByteArray());try{for(int quality=90;quality>=40;quality-=10){java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();image.compress(android.graphics.Bitmap.CompressFormat.JPEG,quality,out);jpeg=out.toByteArray();if(jpeg.length<=4*1024*1024)break;}}finally{image.recycle();}if(jpeg==null||jpeg.length>4*1024*1024)throw new java.io.IOException("Photo exceeds 4 MiB");}catch(Exception e){problem=e;}final byte[] prepared=jpeg;final Exception error=problem;activity.runOnUiThread(()->{sending=false;if(!active(token))return;if(error==null){pendingPhoto=prepared;photoId=null;pendingMessageId=null;uncertainText=null;status.setText(label("Photo attached","Фото прикреплено"));}else status.setText(label("Unable to attach photo (maximum 20 MB).","Не удалось прикрепить фото (не более 20 МБ)."));updateComposer();});});
        }

        boolean atBottom() {
            return !scroll.canScrollVertically(1);
        }

        void scrollBottom(){try{OriginalUi.call(messages,"l0",new Class<?>[]{int.class},Math.max(0,messageAdapter.c()-1));}catch(Exception error){android.util.Log.w("SiriusChat","Scroll failed",error);}}

        void submit(String submitted) {
            if (!attached || sending || submitted.trim().isEmpty() && pendingPhoto==null) return;
            if (submitted.length() > 10000) {
                status.setText(label("Maximum message length: 10000 characters.",
                        "Максимум 10000 символов."));
                return;
            }
            if (!submitted.equals(uncertainText) || pendingMessageId == null) pendingMessageId = java.util.UUID.randomUUID().toString();
            send(submitted);
        }

        void send(String submitted) {
            if (!attached || sending) return;
            sending = true;
            final int token = generation;
            final long submittedRevision = inputRevision;
            final String requestId = pendingMessageId;
            updateComposer();
            status.setText(label("Sending...", "Отправка..."));
            activity.network.execute(() -> {
                Exception failure = null;
                JSONObject acknowledged = null;
                try {
                    if(pendingPhoto!=null && photoId==null)photoId=activity.api.uploadChatPhoto(chatId,pendingPhoto).getString("attachment_id");
                    JSONObject body=new JSONObject().put("text", submitted).put("client_message_id", requestId);if(photoId!=null)body.put("attachment_id",photoId);
                    JSONObject ack = activity.api.request("POST", path, body);
                    if (ack.getLong("id") <= 0 || !submitted.equals(ack.getString("text")))
                        throw new IllegalStateException("Invalid message acknowledgement");
                    acknowledged=ack;
                } catch (Exception error) {
                    failure = error;
                }
                final Exception problem = failure;
                final JSONObject confirmed = acknowledged;
                activity.runOnUiThread(() -> {
                    sending = false;
                    if (!active(token)) {
                        if (attached) updateComposer();
                        return;
                    }
                    if (problem instanceof org.sirius.biotech.data.ApiClient.AuthenticationException) {
                        root.removeCallbacks(poll);
                        activity.clearAccount();
                        activity.show("login");
                        return;
                    }
                    if (problem == null) {
                        try{appendMessage(confirmed);scroll.postDelayed(this::scrollBottom,150);}catch(Exception | LinkageError error){android.util.Log.w("SiriusChat","Unable to display sent message",error);}
                        uncertainText = null;
                        pendingMessageId = null;pendingPhoto=null;photoId=null;
                        // Do not erase text edited while this exact submission was in flight.
                        if (inputRevision == submittedRevision && input.getText().toString().equals(submitted)) input.setText("");
                        status.setText("");
                    } else {
                        uncertainText = submitted;
                        status.setText(label("Send not confirmed. Your text is preserved. Retry will reuse the same message ID.",
                                "Отправка не подтверждена. Текст сохранён. Повторная отправка использует тот же ID без дублирования."));
                    }
                    updateComposer();
                    load();
                });
            });
        }

        boolean appendMessage(JSONObject message)throws Exception{
            long id=message.getLong("id");if(displayed.contains(id))return false;
            String day=message.optString("created_at").split("T")[0];
            if(!day.equals(lastDate)){final String label=day;messageAdapter.append(parent->{View divider=(View)OriginalUi.make("lb.r",new Class<?>[]{Context.class},context);((TextView)OriginalUi.call(divider,"getTextView",new Class<?>[]{})).setText(label);return divider;});lastDate=day;}
            messageAdapter.append(parent->messageView(message));displayed.add(id);return true;
        }

        View messageView(JSONObject message) throws Exception {
            boolean outgoing=message.getString("sender_id").equals(activity.ownPersonId);
            View row=(View)OriginalUi.make(outgoing?"lb.o":"lb.n",new Class<?>[]{Context.class},context);
            String text=message.getString("text"),author=outgoing?label("You","Вы"):title;
            OriginalUi.call(row,"setMessage",new Class<?>[]{CharSequence.class},text);
            OriginalUi.call(row,"setDate",new Class<?>[]{String.class},OriginalChats.date(message.optString("created_at")));
            OriginalUi.call(row,"setCanDelete",new Class<?>[]{boolean.class},false);
            for(String name:new String[]{"replyIndicator","replyImage","replyAuthor","replyText","attachedImage","urlIndicator","urlProvider","urlTitle","urlDescription","urlThumbnail","urlPlay","likeButton","replyButton","organizer","status","progressBar","errorView"}){
                View child=row.findViewById(resource(context,name,"id"));if(child!=null)child.setVisibility(View.GONE);
            }
            if(!outgoing){((TextView)required(row,"author")).setText(author);String image="";for(org.sirius.biotech.data.EventRepository.Person p:activity.participants)if(p.id.equals(message.optString("sender_id")))image=p.avatarUrl;ProfilePhotos.load(activity,(ImageView)required(row,"avatar"),image);}
            String attachment=message.optString("attachment_id","");
            if(!attachment.isEmpty()&&!attachment.equals("null")){ImageView picture=(ImageView)required(row,"attachedImage");picture.setVisibility(View.VISIBLE);ViewGroup.LayoutParams photoSize=picture.getLayoutParams();photoSize.width=activity.dp(240);photoSize.height=activity.dp(180);picture.setLayoutParams(photoSize);picture.setScaleType(ImageView.ScaleType.FIT_CENTER);picture.setContentDescription(label("Message photo","Фото из сообщения"));final int owner=activity.accountGeneration;activity.network.execute(()->{try{byte[] bytes=activity.api.chatPhoto(chatId,attachment);android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length);activity.runOnUiThread(()->{if(owner!=activity.accountGeneration||bitmap==null)return;picture.setImageBitmap(bitmap);picture.setOnClickListener(v->{ImageView expanded=new ImageView(activity);expanded.setImageBitmap(bitmap);expanded.setAdjustViewBounds(true);expanded.setScaleType(ImageView.ScaleType.FIT_CENTER);new AlertDialog.Builder(activity).setView(expanded).setPositiveButton(activity.tr("Close"),null).show();});});}catch(Exception error){android.util.Log.w("SiriusChat","Cannot load message photo",error);}});}
            ((TextView)required(row,"messageText")).setTextIsSelectable(true);
            row.setPadding(0,activity.dp(3),0,activity.dp(3));
            row.setContentDescription(author+". "+text+". "+date(message.optString("created_at")));
            return row;
        }

        String date(String value) {
            try {
                java.text.SimpleDateFormat parser = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
                String normalized = value.replaceFirst("\\.\\d+(?=[Z+-])", "");
                java.util.Date parsed = parser.parse(normalized);
                return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT,
                        activity.getResources().getConfiguration().getLocales().get(0)).format(parsed);
            } catch (Exception error) {
                return value;
            }
        }
    }

    private static final class IntentPicker {static void open(MainActivity activity){android.content.Intent pick=new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);pick.setType("image/*");pick.putExtra(android.content.Intent.EXTRA_MIME_TYPES,new String[]{"image/jpeg","image/png","image/webp"});pick.addCategory(android.content.Intent.CATEGORY_OPENABLE);activity.startActivityForResult(pick,702);}}

    private static View bindingView(Object binding, String method) throws Exception {
        return (View) binding.getClass().getMethod(method).invoke(binding);
    }

    private static int resource(Context context, String name, String type) {
        int id = context.getResources().getIdentifier(name, type, context.getPackageName());
        if (id == 0) throw new IllegalStateException("Original resource missing: " + type + "/" + name);
        return id;
    }

    private static View required(View root, String name) {
        int identifier=resource(root.getContext(),name,"id");View child=null;
        if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount()&&child==null;i++)child=group.getChildAt(i).findViewById(identifier);}else child=root.findViewById(identifier);
        if (child == null) throw new IllegalStateException("Original view missing: " + name);
        return child;
    }
}

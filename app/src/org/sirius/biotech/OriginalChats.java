package org.sirius.biotech;

import android.app.AlertDialog;
import android.content.Context;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import static org.sirius.biotech.OriginalUi.*;

/** Original MSS conversation list, toolbar, swipe refresh, rows and empty state. */
final class OriginalChats implements View.OnAttachStateChangeListener {
    final MainActivity a;final LinearLayout root;final View items;final int account;boolean attached,loading,showHidden;int epoch;String snapshot="";final Runnable poll=()->load(false);
    static View create(MainActivity a)throws Exception{return new OriginalChats(a).root;}
    OriginalChats(MainActivity activity)throws Exception{
        a=activity;account=a.accountGeneration;root=scene(a,"chats_view");items=view(root,"itemsView");
        toolbar(a,view(root,"toolbar"),"Messages",()->a.show("home"),"chats",id->pick(a,false));
        view(root,"emptyAddButton").setOnClickListener(v->pick(a,false));list(items,1,Collections.emptyList());
        // Resolve the listener ABI by method so obfuscation doesn't leak into screen code.
        for(java.lang.reflect.Method method:view(root,"chatsView").getClass().getMethods())if(method.getName().equals("setOnRefreshListener")){
            Class<?> listener=method.getParameterTypes()[0];method.invoke(view(root,"chatsView"),java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{listener},(p,m,args)->{load(true);return null;}));break;
        }
        view(root,"toolbar").setOnLongClickListener(v->{showHidden=!showHidden;load(false);return true;});root.addOnAttachStateChangeListener(this);
    }
    public void onViewAttachedToWindow(View v){attached=true;final int token=++epoch;a.network.execute(()->{JSONObject saved=a.api.cachedResponse("/chats?limit=100");a.runOnUiThread(()->{if(!active(token))return;if(saved!=null)render(saved.optJSONArray("items"));load(false);});});}
    public void onViewDetachedFromWindow(View v){attached=false;epoch++;root.removeCallbacks(poll);}
    boolean active(int token){return attached&&token==epoch&&account==a.accountGeneration;}
    void load(boolean force){if(!attached||loading)return;loading=true;root.removeCallbacks(poll);final int token=epoch;a.network.execute(()->{JSONArray result=null;Exception failure=null;try{JSONArray all=new JSONArray();for(int offset=0;offset<10000;offset+=100){JSONArray batch=a.api.request("GET","/chats?limit=100&offset="+offset,null).getJSONArray("items");for(int i=0;i<batch.length();i++)all.put(batch.get(i));if(batch.length()<100)break;}result=all;}catch(Exception error){failure=error;}final JSONArray chats=result;final Exception problem=failure;a.runOnUiThread(()->{loading=false;if(!active(token))return;try{call(view(root,"chatsView"),"setRefreshing",new Class<?>[]{boolean.class},false);}catch(Exception ignored){}visible(root,"progressView",false);if(problem==null)render(chats);else if(problem instanceof org.sirius.biotech.data.ApiClient.AuthenticationException){a.clearAccount();a.show("login");return;}else if(force)a.toast("Unable to refresh messages. Please retry.");root.postDelayed(poll,5000);});});}
    void render(JSONArray chats){try{String key=String.valueOf(chats)+showHidden;if(key.equals(snapshot))return;snapshot=key;
        List<JSONObject> visible=new ArrayList<>();if(chats!=null)for(int i=0;i<chats.length();i++){JSONObject chat=chats.optJSONObject(i);if(chat!=null&&(showHidden||!chat.optBoolean("hidden")))visible.add(chat);}visible.sort((x,y)->y.optString("updated_at").compareTo(x.optString("updated_at")));
        List<OriginalRecycler.Row> rows=new ArrayList<>();for(JSONObject chat:visible)rows.add(parent->swipe(row(chat),chat));replaceRows(items,rows);items.setVisibility(rows.isEmpty()?View.GONE:View.VISIBLE);OriginalUi.visible(root,"emptyView",rows.isEmpty());OriginalUi.visible(root,"progressView",false);
        call(view(root,"toolbar"),"setTitle",new Class<?>[]{CharSequence.class},a.tr("Messages")+(showHidden?" · "+label(a,"Скрытые","Hidden"):""));
    }catch(Exception error){failed(a,error);}}
    View row(JSONObject chat)throws Exception{
        View row=(View)make("com.eventicious.chats.ui.chats.b0",new Class<?>[]{Context.class},root.getContext());String id=chat.getString("id"),title=chat.optString("title");
        call(row,"setName",new Class<?>[]{CharSequence.class},title);call(row,"setMessage",new Class<?>[]{CharSequence.class},chat.optString("last_message"));call(row,"setDate",new Class<?>[]{CharSequence.class},date(chat.optString("updated_at")));call(row,"setUnread",new Class<?>[]{boolean.class},chat.optInt("unread")>0);call(row,"setNotificationsEnabled",new Class<?>[]{boolean.class},!chat.optBoolean("muted"));
        ProfilePhotos.load(a,(ImageView)view(row,"image"),chat.optString("avatar_url"));row.setOnClickListener(v->a.openConversation(id,title));row.setOnLongClickListener(v->{menu(chat);return true;});return row;
    }
    View swipe(View content,JSONObject chat){
        return new FrameLayout(root.getContext()){
            float startX,startY,delta;boolean dragging;
            final android.text.TextPaint paint=new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            {addView(content,new FrameLayout.LayoutParams(-1,-2));setWillNotDraw(false);paint.setTextSize(a.dp(16));paint.setTypeface(android.graphics.Typeface.create(a.getString(id(a,"font_medium","string")),0));paint.setColor(android.graphics.Color.WHITE);}
            public boolean onInterceptTouchEvent(android.view.MotionEvent event){
                if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN){startX=event.getX();startY=event.getY();dragging=false;}
                if(event.getActionMasked()==android.view.MotionEvent.ACTION_MOVE&&Math.abs(event.getX()-startX)>a.dp(12)&&Math.abs(event.getX()-startX)>Math.abs(event.getY()-startY)){dragging=true;getParent().requestDisallowInterceptTouchEvent(true);return true;}
                return super.onInterceptTouchEvent(event);
            }
            public boolean onTouchEvent(android.view.MotionEvent event){if(!dragging)return super.onTouchEvent(event);switch(event.getActionMasked()){
                case android.view.MotionEvent.ACTION_MOVE:delta=event.getX()-startX;content.setTranslationX(delta);invalidate();return true;
                case android.view.MotionEvent.ACTION_UP:if(Math.abs(delta)>getWidth()*.35f){hide(chat);content.setTranslationX(0);}else content.animate().translationX(0).setDuration(160).start();delta=0;dragging=false;invalidate();return true;
                case android.view.MotionEvent.ACTION_CANCEL:delta=0;dragging=false;content.setTranslationX(0);invalidate();return true;
            }return true;}
            protected void onDraw(android.graphics.Canvas canvas){super.onDraw(canvas);if(delta==0)return;android.graphics.Paint bg=new android.graphics.Paint();bg.setColor(0xff356aff);canvas.drawRect(delta>0?0:getWidth()+delta,0,delta>0?delta:getWidth(),getHeight(),bg);String label=a.getString(id(a,"chat_hidden_swipe_text","string"));paint.setTextAlign(delta>0?android.graphics.Paint.Align.LEFT:android.graphics.Paint.Align.RIGHT);canvas.drawText(label,delta>0?a.dp(32):getWidth()-a.dp(32),(getHeight()-paint.ascent()-paint.descent())/2,paint);}
        };
    }
    void hide(JSONObject chat){state(chat,"hidden",true,()->{try{Object snackbar=cls("ri.j").getMethod("k",View.class,CharSequence.class,int.class).invoke(null,view(root,"contentView"),a.getString(id(a,"chat_hidden_snackbar","string"),chat.optString("title")),5000);call(snackbar,"l",new Class<?>[]{int.class,View.OnClickListener.class},id(a,"chat_hidden_undo","string"),(View.OnClickListener)v->state(chat,"hidden",false,null));call(snackbar,"h",new Class<?>[]{});}catch(Exception error){a.toast(OriginalChats.label(a,"Чат скрыт","Chat hidden"));}});}
    void state(JSONObject chat,String key,boolean value,Runnable done){a.network.execute(()->{try{a.api.request("PUT","/chats/"+chat.optString("id")+"/state",new JSONObject().put(key,value));a.runOnUiThread(()->{if(!attached||account!=a.accountGeneration)return;load(false);if(done!=null)done.run();});}catch(Exception error){a.runOnUiThread(()->a.toast("Could not save changes. Please retry."));}});}
    void menu(JSONObject chat){boolean hidden=chat.optBoolean("hidden"),muted=chat.optBoolean("muted");new AlertDialog.Builder(root.getContext()).setTitle(chat.optString("title")).setItems(new String[]{label(a,hidden?"Показать чат":"Скрыть чат",hidden?"Show chat":"Hide chat"),label(a,muted?"Включить уведомления":"Выключить уведомления",muted?"Enable notifications":"Mute notifications")},(d,which)->{a.network.execute(()->{try{JSONObject patch=new JSONObject().put(which==0?"hidden":"muted",which==0?!hidden:!muted);a.api.request("PUT","/chats/"+chat.optString("id")+"/state",patch);a.runOnUiThread(()->load(false));}catch(Exception error){a.runOnUiThread(()->a.toast("Could not save changes. Please retry."));}});}).show();}
    static String label(MainActivity a,String ru,String en){return "ru".equals(a.store.getString("appLanguage","ru"))?ru:en;}
    static String date(String value){try{java.time.OffsetDateTime dt=java.time.OffsetDateTime.parse(value).atZoneSameInstant(java.time.ZoneId.systemDefault()).toOffsetDateTime();return dt.toLocalDate().equals(java.time.LocalDate.now())?dt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")):dt.format(java.time.format.DateTimeFormatter.ofPattern("d MMM",Locale.getDefault()));}catch(Exception error){return "";}}
    static void pick(MainActivity a,boolean meeting){try{
        Context c=theme(a);LinearLayout root=new LinearLayout(c);root.setOrientation(1);root.setBackgroundColor(a.bg);View toolbar=(View)make("com.eventicious.toolbar.Toolbar",new Class<?>[]{Context.class,android.util.AttributeSet.class},c,null);root.addView(toolbar);toolbar(a,toolbar,meeting?"Arrange a meeting":"New conversation",()->a.show(meeting?"Meetings":"Messages"),null,null);
        View search=(View)make("com.eventicious.ui.view.SearchInputView",new Class<?>[]{Context.class,android.util.AttributeSet.class},c,null);root.addView(search);FrameLayout holder=new FrameLayout(c);inflate(c,"attendees_view",holder);root.addView(holder,new LinearLayout.LayoutParams(-1,0,1));View list=view(holder,"attendeesView");list(list,1,Collections.emptyList());visible(holder,"progressView",false);visible(holder,"attendeesView",true);
        final Runnable[] update={null};EditText input=(EditText)view(search,"searchInputView");update[0]=()->{try{List<OriginalRecycler.Row> rows=new ArrayList<>();String q=input.getText().toString().toLowerCase(Locale.ROOT);for(int i=0;i<a.participants.size();i++){final int index=i;org.sirius.biotech.data.EventRepository.Person person=a.participants.get(i);if(person.archive||person.id.equals(a.ownPersonId)||!(person.name+" "+person.organization).toLowerCase(Locale.ROOT).contains(q))continue;rows.add(parent->{View row=a.originalPerson(index);if(row==null)throw new IllegalStateException("Missing original attendee row");row.setOnClickListener(v->{if(meeting)OriginalMeetings.propose(a,index);else a.chat(index);});return row;});}setRows(list,rows);visible(holder,"emptyView",rows.isEmpty());}catch(Exception error){failed(a,error);}};
        input.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){update[0].run();}public void afterTextChanged(android.text.Editable e){}});update[0].run();a.backTarget=meeting?"Meetings":"Messages";a.sceneBack=()->a.show(meeting?"Meetings":"Messages");a.setContentView(root);
    }catch(Exception error){failed(a,error);}}
}

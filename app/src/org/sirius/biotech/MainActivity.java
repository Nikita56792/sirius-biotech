package org.sirius.biotech;

import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.util.*;
import org.sirius.biotech.data.EventRepository;
import org.sirius.biotech.data.DemoRepository;

/** SBS transport and navigation hosting the retained original MSS UI. */
public class MainActivity extends Activity {
    final int ink=0xff2c333b, muted=0xff929fa8, accent=0xff252650, bg=0xffeeeef4;
    LinearLayout root,body; String page="events", query="", backTarget=null; int slide=0,day=0;
    android.content.SharedPreferences store;
    EventRepository repository;
    org.sirius.biotech.data.ApiClient api;
    List<EventRepository.Session> sessions=Collections.emptyList();
    List<EventRepository.Person> participants=Collections.emptyList();
    String[] titles=new String[0],times=new String[0],names=new String[0];
    final java.util.concurrent.ExecutorService network=java.util.concurrent.Executors.newSingleThreadExecutor();
    boolean authPending;
    java.util.function.Consumer<android.net.Uri> chatPhotoSelected;
    java.util.function.Consumer<String> meetingLocationSelected;
    String ownPersonId="";
    void bindSnapshot(){sessions=repository.sessions();participants=repository.people();titles=new String[sessions.size()];times=new String[sessions.size()];names=new String[participants.size()];for(int i=0;i<sessions.size();i++){titles[i]=sessions.get(i).title;times[i]=sessions.get(i).time;}for(int i=0;i<participants.size();i++)names[i]=participants.get(i).name;}
    interface NetworkJob{void run()throws Exception;}
    int accountGeneration;
    void clearAccount(){resetRecovery();accountGeneration++;authPending=false;ownPersonId="";serverProfile=new JSONObject();serverFavorites=new JSONArray();sessions=Collections.emptyList();participants=Collections.emptyList();titles=new String[0];times=new String[0];names=new String[0];store.edit().remove("profileName").remove("profileBio").remove("profileCompany").remove("profilePosition").putBoolean("signedIn",false).apply();}
    void signOutLive(){api.signOut();clearAccount();show("events");}
    void request(NetworkJob work,Runnable success){final int generation=accountGeneration;network.execute(()->{if(generation!=accountGeneration)return;try{work.run();runOnUiThread(()->{if(generation==accountGeneration&&!isFinishing()&&!isDestroyed())success.run();});}catch(Exception e){runOnUiThread(()->{if(generation!=accountGeneration||isFinishing()||isDestroyed())return;if(e instanceof org.sirius.biotech.data.ApiClient.AuthenticationException){clearAccount();show("login");toast("Your session has expired. Please sign in again.");}else if(org.sirius.biotech.data.ApiClient.offlineError(e)){offlineMode=true;dialog("Offline mode",originalScreens().originalString("error_network"));}else dialog("Unable to complete request","Please try again.");});}});}
    void authenticateLive(String code){if(!"login".equals(page))return;authenticate(code,null);}
    void authenticateRecovery(String code,String challenge){if(!challenge.equals(recoveryChallenge)||!"recoveryPin".equals(page))return;authenticate(code,challenge);}
    void authenticate(String code,String challenge){
        if(authPending)return;
        authPending=true;setAuthBlocked(true);
        final int generation=accountGeneration,flow=recoveryGeneration;
        network.execute(()->{
            boolean loggedIn=false;
            try{
                if(challenge==null)api.login(code);else api.verifyPin(challenge,code);
                loggedIn=true;
                if(generation!=accountGeneration||flow!=recoveryGeneration)return;
                ((org.sirius.biotech.data.ServerRepository)repository).refresh();fetchAccount();
                runOnUiThread(()->{if(generation!=accountGeneration||flow!=recoveryGeneration||isFinishing()||isDestroyed())return;authPending=false;setAuthBlocked(false);resetRecovery();bindSnapshot();applyAccount();store.edit().putBoolean("signedIn",true).apply();show("home");});
            }catch(Exception error){
                if(generation!=accountGeneration||flow!=recoveryGeneration)return;
                final boolean accepted=loggedIn;
                runOnUiThread(()->{
                    if(generation!=accountGeneration||flow!=recoveryGeneration||isFinishing()||isDestroyed())return;
                    authPending=false;setAuthBlocked(false);
                    if(accepted){api.signOut();clearAccount();show("login");recoveryError("error_network");return;}
                    String message=originalScreens().originalString(org.sirius.biotech.data.ApiClient.offlineError(error)?"error_network":"auth_enter_pin_incorrect_id");
                    if(error instanceof org.sirius.biotech.data.ApiClient.RateLimitException)message=tr("Too many sign-in attempts. Retry in seconds:")+" "+((org.sirius.biotech.data.ApiClient.RateLimitException)error).retryAfter;
                    final int retryFlow=recoveryGeneration;
                    new AlertDialog.Builder(this).setTitle(originalScreens().originalString("auth_registration")).setMessage(message).setNegativeButton(tr("Cancel"),null).setPositiveButton(tr("Retry"),(d,w)->{if(generation==accountGeneration&&retryFlow==recoveryGeneration){if(challenge==null)authenticateLive(code);else authenticateRecovery(code,challenge);}}).show();
                });
            }
        });
    }
    volatile boolean offlineMode;
    final android.os.Handler reconnectHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    boolean reconnectPending;
    final Runnable reconnect = new Runnable(){public void run(){
        if(api!=null && (offlineMode||api.connectionOffline) && !reconnectPending && api.signedIn() && repository instanceof org.sirius.biotech.data.ServerRepository){
            reconnectPending=true; final int owner=accountGeneration;
            network.execute(()->{try{((org.sirius.biotech.data.ServerRepository)repository).refresh();fetchAccount();
                runOnUiThread(()->{if(owner==accountGeneration&&!isFinishing()&&!isDestroyed()&&!offlineMode){bindSnapshot();applyAccount();}});
            }catch(Exception ignored){}finally{runOnUiThread(()->reconnectPending=false);}});
        }
        reconnectHandler.postDelayed(this,15000);
    }};
    JSONObject serverProfile=new JSONObject();
    JSONArray serverFavorites=new JSONArray();
    void fetchAccount()throws Exception{boolean cached=api.lastRequestOffline();serverProfile=api.request("GET","/profile",null);cached|=api.lastRequestOffline();ownPersonId=serverProfile.getString("id");serverFavorites=api.request("GET","/favorites",null).getJSONArray("items");offlineMode=cached||api.lastRequestOffline();}
    void applyAccount(){store.edit().putString("profileName",serverProfile.optString("name")).putString("profileBio",serverProfile.optString("bio")).putString("profileCompany",serverProfile.optString("company")).putString("profilePosition",serverProfile.optString("position")).apply();}
    void loadServer(){final int owner=accountGeneration;network.execute(()->{boolean restored=false;try{JSONObject profile=api.cachedResponse("/profile"),favorites=api.cachedResponse("/favorites");if(profile!=null&&favorites!=null&&((org.sirius.biotech.data.ServerRepository)repository).loadCached()){serverProfile=profile;serverFavorites=favorites.getJSONArray("items");ownPersonId=profile.getString("id");restored=true;runOnUiThread(()->{if(owner==accountGeneration&&"home".equals(page)){bindSnapshot();applyAccount();home();}});}}catch(Exception ignored){}final boolean hadCache=restored;try{((org.sirius.biotech.data.ServerRepository)repository).refresh();fetchAccount();runOnUiThread(()->{if(owner!=accountGeneration)return;bindSnapshot();applyAccount();if("home".equals(page))home();});}catch(Exception e){runOnUiThread(()->{if(owner!=accountGeneration)return;if(e instanceof org.sirius.biotech.data.ApiClient.AuthenticationException){clearAccount();show("login");}else if(!hadCache)dialog("Unable to complete request","Please try again.");});}});}
    void saveProfileLive(String name,String company,String position,String bio){request(()->{serverProfile=api.request("PATCH","/profile",new JSONObject().put("name",name).put("company",company).put("position",position).put("bio",bio));},()->{applyAccount();profile();toast("Profile saved");});}
    void serverSettings(){input("Server address","https://your-event-server.example",value->{try{api.configure(value);clearAccount();show("login");}catch(Exception e){dialog("Settings","Unable to update settings. Please try again.");}});}
    int speakerIndex(String name){for(int i=0;i<names.length;i++)if(names[i].equals(name))return i;return -1;}
    int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    @Override protected void attachBaseContext(Context base){String lang=base.getSharedPreferences("sirius-demo",0).getString("appLanguage","en");Locale locale=new Locale(lang);Locale.setDefault(locale);android.content.res.Configuration config=new android.content.res.Configuration(base.getResources().getConfiguration());config.setLocale(locale);config.setLocales(new android.os.LocaleList(locale));super.attachBaseContext(base.createConfigurationContext(config));}
    String tr(String value){return UiText.translate(this,value);}
    public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(0xffd7d7e3);getWindow().setNavigationBarColor(bg);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LaunchScreen.start(this,()->{
            store=getSharedPreferences("sirius-demo",0);api=new org.sirius.biotech.data.ApiClient(this);
            repository=new org.sirius.biotech.data.ServerRepository(api);bindSnapshot();
            store.edit().putBoolean("signedIn",api.signedIn()).apply();show("events");
        },()->{if(!store.contains("appLanguage"))OriginalScreens.showLanguageChooser(this,true);});
    }

    FrameLayout screenHost;
    View activeScreen;
    android.animation.AnimatorSet screenAnimation;
    int transitionGeneration;
    String previousRoute;
    boolean navigating;
    @Override public void setContentView(View view){
        if(screenHost==null){screenHost=new FrameLayout(this);screenHost.setBackgroundColor(bg);super.setContentView(screenHost);screenHost.getViewTreeObserver().addOnGlobalLayoutListener(()->{android.graphics.Rect visible=new android.graphics.Rect();screenHost.getWindowVisibleDisplayFrame(visible);int[] location=new int[2];screenHost.getLocationOnScreen(location);int covered=Math.max(0,location[1]+screenHost.getHeight()-visible.bottom);int bottom=covered>dp(120)?covered:0;if(screenHost.getPaddingBottom()!=bottom)screenHost.setPadding(0,0,0,bottom);});}
        if(view==activeScreen)return;
        final int generation=++transitionGeneration;
        if(screenAnimation!=null){screenAnimation.cancel();screenAnimation=null;}
        final View outgoing=activeScreen;
        for(int i=screenHost.getChildCount()-1;i>=0;i--)if(screenHost.getChildAt(i)!=outgoing)screenHost.removeViewAt(i);
        if(outgoing!=null){outgoing.animate().cancel();outgoing.setAlpha(1f);outgoing.setScaleX(1f);outgoing.setScaleY(1f);outgoing.setTranslationX(0f);outgoing.setTranslationY(0f);}
        activeScreen=view;
        screenHost.addView(view,new FrameLayout.LayoutParams(-1,-1));
        boolean enabled=android.os.Build.VERSION.SDK_INT>=26?android.animation.ValueAnimator.areAnimatorsEnabled():android.provider.Settings.Global.getFloat(getContentResolver(),android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f;
        if(outgoing==null||!enabled){if(outgoing!=null)screenHost.removeView(outgoing);return;}
        final boolean leavingMenu=navigating && !Objects.equals(previousRoute,page) && "home".equals(previousRoute), enteringMenu=navigating && !Objects.equals(previousRoute,page) && "home".equals(page);
        view.setAlpha(0f);
        view.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){
            public boolean onPreDraw(){
                view.getViewTreeObserver().removeOnPreDrawListener(this);
                if(generation!=transitionGeneration)return true;
                float w=screenHost.getWidth(),h=screenHost.getHeight(),bar=dp(56);
                float x=(getResources().getConfiguration().getLayoutDirection()==View.LAYOUT_DIRECTION_RTL?w/2f:-w/2f)+bar/2f,y=(bar-h)/2f;
                ArrayList<android.animation.Animator> animations=new ArrayList<>();
                animations.add(android.animation.ObjectAnimator.ofFloat(view,View.ALPHA,0f,1f));
                animations.add(android.animation.ObjectAnimator.ofFloat(outgoing,View.ALPHA,1f,0f));
                if(leavingMenu){animations.add(android.animation.ObjectAnimator.ofFloat(view,View.TRANSLATION_Y,h,0f));animations.add(android.animation.ObjectAnimator.ofFloat(outgoing,View.SCALE_X,1f,0f));animations.add(android.animation.ObjectAnimator.ofFloat(outgoing,View.SCALE_Y,1f,0f));animations.add(android.animation.ObjectAnimator.ofFloat(outgoing,View.TRANSLATION_X,0f,x));animations.add(android.animation.ObjectAnimator.ofFloat(outgoing,View.TRANSLATION_Y,0f,y));}
                else if(enteringMenu){animations.add(android.animation.ObjectAnimator.ofFloat(view,View.SCALE_X,0f,1f));animations.add(android.animation.ObjectAnimator.ofFloat(view,View.SCALE_Y,0f,1f));animations.add(android.animation.ObjectAnimator.ofFloat(view,View.TRANSLATION_X,x,0f));animations.add(android.animation.ObjectAnimator.ofFloat(view,View.TRANSLATION_Y,y,0f));}
                screenAnimation=new android.animation.AnimatorSet();screenAnimation.playTogether(animations);screenAnimation.setDuration(300L);screenAnimation.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                screenAnimation.addListener(new android.animation.AnimatorListenerAdapter(){public void onAnimationEnd(android.animation.Animator a){if(generation==transitionGeneration){screenHost.removeView(outgoing);screenAnimation=null;}}});screenAnimation.start();return true;
            }
        });
    }
    LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(tr(s));t.setTextSize(size);t.setTextColor(color);t.setPadding(dp(12),dp(10),dp(12),dp(10));return t;}
    TextView bold(String s,int size){TextView t=text(s,size,ink);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    LinearLayout card(LinearLayout parent){LinearLayout c=column();c.setBackground(shape(Color.WHITE,8));c.setElevation(dp(1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(10),dp(6),dp(10),dp(6));parent.addView(c,p);return c;}
    void button(LinearLayout parent,String label,Runnable fn){TextView b=text(label,16,accent);b.setGravity(Gravity.CENTER);b.setTypeface(Typeface.DEFAULT,1);b.setBackground(shape(0xffe5e5eb,14));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.setMargins(dp(14),dp(12),dp(14),dp(12));parent.addView(b,p);b.setOnClickListener(v->fn.run());}
    void image(LinearLayout parent,String asset,int size){ImageView i=new ImageView(this);try{i.setImageDrawable(Drawable.createFromStream(getAssets().open(asset),null));}catch(Exception e){}i.setScaleType(ImageView.ScaleType.CENTER_CROP);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(size),dp(size));p.gravity=Gravity.CENTER;p.setMargins(dp(12),dp(12),dp(12),dp(12));parent.addView(i,p);}
    void originalImage(LinearLayout parent,String name,int size){ImageView i=new ImageView(this);int id=getResources().getIdentifier(name,"drawable",getPackageName());if(id!=0)i.setImageResource(id);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(size),dp(size));p.gravity=Gravity.CENTER;p.setMargins(0,dp(28),0,dp(18));parent.addView(i,p);}
    void toolbar(String title,String back){LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);ImageView arrow=new ImageView(this);arrow.setImageResource(getResources().getIdentifier(back==null?"icon_menu_24":"icon_back_24","drawable",getPackageName()));arrow.setColorFilter(ink);arrow.setPadding(dp(14),dp(14),dp(14),dp(14));arrow.setContentDescription(tr(back==null?"Menu":"Back"));bar.addView(arrow,new LinearLayout.LayoutParams(dp(52),dp(58)));arrow.setOnClickListener(v->{if(sceneBack!=null)onBackPressed();else if(back!=null)show(back);else menu();});bar.addView(bold(title,21),new LinearLayout.LayoutParams(0,-2,1));root.addView(bar);}
    void base(String title,String back){getWindow().setStatusBarColor(0xffd7d7e3);getWindow().setNavigationBarColor(bg);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);backTarget=back;root=column();root.setBackgroundColor(bg);setContentView(root);if(title!=null)toolbar(title,back);ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);body=column();scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));}
    void note(LinearLayout p){}
    void show(String p){if(authPending||recoveryPending)return;previousRoute=page;navigating=activeScreen!=null;if(!p.startsWith("recovery")&&(page.startsWith("recovery")||recoverySurnameView!=null))resetRecovery();sceneBack=null;page=p;query="";try{switch(p){case "events":events();break;case "about":about();break;case "welcome":backTarget="about";setContentView(store.getBoolean("consents-v1",false)?FirstRunScreens.registration(this):FirstRunScreens.consent(this));break;case "login":login();break;case "recovery":recoverId();break;case "recoveryList":recoveryList();break;case "recoveryPin":recoveryPin();break;case "home":if(api.signedIn()&&ownPersonId.isEmpty())loadServer();else if(!api.signedIn())login();else home();break;case "Programme":programme(false);break;case "My schedule":programme(true);break;case "Participants":people();break;case "Messages":messages();break;case "Meetings":meetings();break;case "Venue":venue();break;case "News":news();break;case "Partners":partners();break;case "Profile":profile();break;case "Settings":settings();break;default:home();}}finally{navigating=false;}}
    void events(){backTarget=null;setContentView(OriginalEvents.list(this));}
    void eventsLegacy(){base(null,null);TextView brand=bold("Sirius\nBiotech\n2027",34);brand.setTextColor(accent);brand.setGravity(Gravity.CENTER);body.addView(brand);EditText search=new EditText(this);search.setSingleLine();search.setTextSize(16);search.setHint(tr("Search by event name or ID"));search.setBackground(shape(0xffd9d9e3,10));search.setPadding(dp(16),0,dp(16),0);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(54));sp.setMargins(dp(10),dp(8),dp(10),dp(12));body.addView(search,sp);TextView section=bold("UPCOMING EVENTS",13);section.setTextColor(Color.WHITE);section.setBackgroundColor(muted);body.addView(section);LinearLayout event=card(body);LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);image(header,"sirius_icon.jpg",64);header.addView(bold("Sirius Biotech 2027",18),new LinearLayout.LayoutParams(0,-2,1));event.addView(header);event.addView(text("2027 · Dates to be announced",15,ink));event.addView(text("Sirius, Russia · Venue to be confirmed",15,ink));event.setOnClickListener(v->show("about"));search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){String q=s.toString().toLowerCase(Locale.ROOT);event.setVisibility(q.isEmpty()||"sirius biotech 2027".contains(q)?View.VISIBLE:View.GONE);}public void afterTextChanged(Editable e){}});}
    void about(){backTarget="events";setContentView(OriginalEvents.details(this));}
    void aboutLegacy(){base("", "events");LinearLayout h=new LinearLayout(this);h.setGravity(Gravity.CENTER_VERTICAL);image(h,"sirius_icon.jpg",64);h.addView(bold("Sirius Biotech 2027",18),new LinearLayout.LayoutParams(0,-2,1));body.addView(h);LinearLayout c=card(body);c.addView(text("2027 · Dates to be announced",18,ink));c.addView(text("Sirius, Russia\nVenue to be confirmed",18,ink));c.addView(text("Your personal guide to Sirius Biotech 2027. Explore the programme, discover biotechnology projects, find researchers and partners, arrange meetings and stay up to date with the event.\n\nThis offline preview contains sample information. The final programme, speakers, venue and event dates will be added later.",16,ink));button(c,"Open",()->show(store.getBoolean("signedIn",false)?"home":"welcome"));}
    void welcome(){backTarget="about";setContentView(FirstRunScreens.consent(this));}
    void login(){try{backTarget="welcome";setContentView(originalScreens().login());return;}catch(Exception | LinkageError e){android.util.Log.e("SiriusOriginalUI","Original login failed; using local fallback",e);}base("Registration","welcome");LinearLayout c=card(body);originalImage(c,"image_auth_start_120",120);TextView t=bold("Enter ID",18);t.setGravity(Gravity.CENTER);c.addView(t);EditText pin=new EditText(this);pin.setGravity(Gravity.CENTER);pin.setTextSize(26);pin.setLetterSpacing(.4f);pin.setSingleLine();pin.setInputType(18);pin.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});pin.setHint("• • • • • •");c.addView(pin,new LinearLayout.LayoutParams(-1,dp(76)));pin.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){if(s.length()==6){authenticateLive(s.toString());}}public void afterTextChanged(Editable e){}});button(c,"Don't know your ID?",()->recoverId());pin.requestFocus();pin.postDelayed(()->((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(pin,InputMethodManager.SHOW_IMPLICIT),200);}
    OriginalMenu originalMenu;
    OriginalScreens originalScreens(){
        if(originalMenu==null)originalMenu=new OriginalMenu(this);
        return new OriginalScreens(this,originalMenu);
    }
    void home(){
        backTarget=null;
        try {
            if(originalMenu==null)originalMenu=new OriginalMenu(this);
            View menu=originalMenu.create();
            setContentView(menu);
        } catch(Exception | LinkageError e){
            android.util.Log.e("SiriusOriginalUI","Original menu failed",e);
            base("Sirius Biotech 2027",null);
            body.addView(text("Unable to open this page. Please try again.",16,ink));
            button(body,"Settings",()->show("Settings"));
        }
    }
    void menu(){show("home");}
    @Override protected void onResume(){super.onResume();reconnectHandler.removeCallbacks(reconnect);reconnectHandler.post(reconnect);if(originalMenu!=null){originalMenu.event("ON_START");originalMenu.event("ON_RESUME");}}
    @Override protected void onPause(){reconnectHandler.removeCallbacks(reconnect);if(originalMenu!=null)originalMenu.event("ON_PAUSE");super.onPause();}
    @Override protected void onStop(){if(originalMenu!=null)originalMenu.event("ON_STOP");super.onStop();}
    @Override protected void onDestroy(){reconnectHandler.removeCallbacks(reconnect);resetRecovery();accountGeneration++;if(screenAnimation!=null)screenAnimation.cancel();network.shutdownNow();if(originalMenu!=null)originalMenu.event("ON_DESTROY");super.onDestroy();}
    boolean isFavorite(String kind,String id){for(int n=0;n<serverFavorites.length();n++){JSONObject x=serverFavorites.optJSONObject(n);if(x!=null&&kind.equals(x.optString("kind"))&&id.equals(x.optString("item_id")))return true;}return false;}
    boolean saved(int i){return isFavorite("sessions",sessions.get(i).id);}
    void favorite(String kind,String id,boolean selected,Runnable done){request(()->{JSONArray current=api.request("GET","/favorites",null).getJSONArray("items"),next=new JSONArray();for(int n=0;n<current.length();n++){JSONObject x=current.getJSONObject(n);if(!(kind.equals(x.optString("kind"))&&id.equals(x.optString("item_id"))))next.put(x);}if(selected)next.put(new JSONObject().put("kind",kind).put("item_id",id));serverFavorites=api.request("PUT","/favorites",new JSONObject().put("items",next)).getJSONArray("items");},done);}
    Runnable sceneBack;
    String scheduleReturn="Programme";
    void programme(boolean only){try{backTarget="home";OriginalUi.backdrop(this);setContentView(OriginalSchedule.create(this,only));}catch(Exception error){OriginalUi.failed(this,error);}}
    void messages(){try{backTarget="home";OriginalUi.backdrop(this);setContentView(OriginalChats.create(this));}catch(Exception error){OriginalUi.failed(this,error);}}
    void meetings(){try{backTarget="home";OriginalUi.backdrop(this);setContentView(OriginalMeetings.create(this));}catch(Exception error){OriginalUi.failed(this,error);}}
    void proposeMeeting(int i){OriginalMeetings.propose(this,i);}
    void setFavoriteLive(String kind,String id,boolean selected)throws Exception{JSONArray current=api.request("GET","/favorites",null).getJSONArray("items"),next=new JSONArray();for(int n=0;n<current.length();n++){JSONObject x=current.getJSONObject(n);if(!(kind.equals(x.optString("kind"))&&id.equals(x.optString("item_id"))))next.put(x);}if(selected)next.put(new JSONObject().put("kind",kind).put("item_id",id));serverFavorites=api.request("PUT","/favorites",new JSONObject().put("items",next)).getJSONArray("items");}
    List<String> sessionDates(){ArrayList<String> dates=new ArrayList<>();for(EventRepository.Session s:sessions){String date=s.time.length()>=10?s.time.substring(0,10):s.time;if(!dates.contains(date))dates.add(date);}Collections.sort(dates);return dates;}
    interface Changed{void run(String s);}
    void search(LinearLayout p,Changed fn){EditText e=new EditText(this);e.setTextSize(16);e.setSingleLine();e.setHint(tr("Search"));p.addView(e);e.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){fn.run(s.toString().toLowerCase(Locale.ROOT));}public void afterTextChanged(Editable e){}});}
    View originalDetailsHeader(boolean attendee,String title){
        try {
            Context themed=new ContextThemeWrapper(this,getResources().getIdentifier("Theme.Eventicious.App","style",getPackageName()));
            String className=attendee?"com.eventicious.attendees.ui.details.AttendeeDetailsHeaderView":"com.eventicious.ui.session.SessionDetailsHeaderView";
            Class<?> headerClass=Class.forName(className);
            // Both original constructors inflate their <merge> layouts without resolving a presenter.
            View header=(View)headerClass.getConstructor(Context.class,android.util.AttributeSet.class).newInstance(themed,null);
            headerClass.getMethod(attendee?"setName":"setTitle",String.class).invoke(header,tr(title));
            // The offline screen has no collapsing pager; do not reserve space for its invisible tabs.
            ((View)headerClass.getMethod("getTabLayout").invoke(header)).setVisibility(View.GONE);
            if(attendee){
                ImageView avatar=(ImageView)header.findViewById(getResources().getIdentifier("avatarView","id",getPackageName()));
                // setData/setImage invoke Coil even for null photos; use the original placeholder directly.
                avatar.setImageResource(getResources().getIdentifier("attendee_square_100","drawable",getPackageName()));
            }
            return header;
        } catch(Exception | LinkageError e){android.util.Log.e("SiriusOriginalUI","Original details header inflation/binding failed",e);return null;}
    }
    View originalSessionInfoRow(String layout,String valueId,String value){
        try {
            Context themed=new ContextThemeWrapper(this,getResources().getIdentifier("Theme.Eventicious.App","style",getPackageName()));
            View row=LayoutInflater.from(themed).inflate(getResources().getIdentifier(layout,"layout",getPackageName()),body,false);
            ((TextView)row.findViewById(getResources().getIdentifier(valueId,"id",getPackageName()))).setText(tr(value));
            // Location navigation requires the original backend; keep this offline row informational.
            View chevron=row.findViewById(getResources().getIdentifier("chevronView","id",getPackageName()));
            if(chevron!=null)chevron.setVisibility(View.INVISIBLE);
            return row;
        } catch(Exception | LinkageError e){android.util.Log.e("SiriusOriginalUI","Original session info row inflation/binding failed",e);return null;}
    }
    void session(int i){try{setContentView(OriginalSession.create(this,i));}catch(Exception e){OriginalUi.failed(this,e);}}
    void sessionFeedback(String sessionId){request(()->{JSONArray questions=api.request("GET","/sessions/"+sessionId+"/questions",null).getJSONArray("items");JSONObject rating=api.request("GET","/sessions/"+sessionId+"/rating",null);StringBuilder result=new StringBuilder(tr("Your rating")).append(": ").append(rating.isNull("value")?tr("Not rated"):rating.optString("value")).append("\n\n");for(int n=0;n<questions.length();n++){JSONObject q=questions.getJSONObject(n);result.append(q.optString("text")).append("\n").append(tr(q.optString("status"))).append("\n");if(!q.optString("response").isEmpty())result.append(q.optString("response")).append("\n");result.append("\n");}runOnUiThread(()->dialog("My questions and rating",result.toString()));},()->{});}
    void people(){try{backTarget="home";setContentView(OriginalPeople.create(this));}catch(Exception e){OriginalUi.failed(this,e);}}
    View originalPerson(int i){
        try {
            Context themed=new ContextThemeWrapper(this,getResources().getIdentifier("Theme.Eventicious.App","style",getPackageName()));
            // The original constructor inflates attendee_item's <merge> into its ConstraintLayout.
            Class<?> rowClass=Class.forName("com.eventicious.ui.widget.d");
            View row=(View)rowClass.getConstructor(Context.class).newInstance(themed);
            EventRepository.Person item=participants.get(i);
            String[] name=tr(item.name).split(" ",2);
            Class<?> nameOrder=Class.forName("com.eventicious.ui.widget.AttendeeNameOrder");
            Object firstName=nameOrder.getMethod("valueOf",String.class).invoke(null,"FirstName");
            rowClass.getMethod("v",String.class,String.class,nameOrder,String.class,String.class,Class.forName("com.eventicious.meetings.MeetingStatus"))
                    .invoke(row,name[0],name.length>1?name[1]:"",firstName,tr(item.organization),tr(item.role),null);
            rowClass.getMethod("setAuthorized",boolean.class).invoke(row,false);
            rowClass.getMethod("setFavoriteVisible",boolean.class).invoke(row,true);
            rowClass.getMethod("setFavorite",boolean.class).invoke(row,isFavorite("people",participants.get(i).id));
            CompoundButton.OnCheckedChangeListener listener=(button,checked)->favorite("people",participants.get(i).id,checked,()->{});
            rowClass.getMethod("setOnFavoriteChangedListener",CompoundButton.OnCheckedChangeListener.class).invoke(row,listener);
            // Keep the XML avatar placeholder; setPhoto would initialize Coil's image loader.
            ImageView photo=(ImageView)row.findViewById(getResources().getIdentifier("avatarView","id",getPackageName()));if(photo!=null)ProfilePhotos.load(this,photo,item.avatarUrl);row.setContentDescription(tr(item.name));
            row.setOnClickListener(v->person(i));
            return row;
        } catch(Exception | LinkageError e){android.util.Log.e("SiriusOriginalUI","Original attendee inflation/binding failed",e);return null;}
    }
    void renderPeople(LinearLayout l){l.removeAllViews();int count=0;for(int i=0;i<names.length;i++){if(!tr(names[i]).toLowerCase(Locale.ROOT).contains(query)&&!names[i].toLowerCase(Locale.ROOT).contains(query))continue;final int id=i;View original=originalPerson(i);if(original!=null){l.addView(original,new LinearLayout.LayoutParams(-1,-2));count++;continue;}LinearLayout c=card(l);c.addView(bold(names[i],18));c.addView(text(participants.get(i).role+" · "+participants.get(i).organization,14,muted));c.setOnClickListener(v->person(id));count++;}if(count==0)l.addView(text("No matching participants",16,muted));}
    void person(int i){try{backTarget="Participants";setContentView(OriginalPeople.details(this,i));}catch(Exception e){OriginalUi.failed(this,e);}}

    void openConversation(String chatId,String title){try{backTarget="Messages";setContentView(ServerChatScreen.create(this,chatId,title));}catch(Exception e){android.util.Log.e("SiriusOriginalUI","Open original chat failed",e);dialog("Messages","Unable to open messages. Please try again.");}}
    void chat(int i){final String personId=participants.get(i).id;final String title=names[i];request(()->{JSONObject conversation=api.request("POST","/chats",new JSONObject().put("person_id",personId));String chatId=conversation.getString("id");runOnUiThread(()->{try{backTarget="Messages";setContentView(ServerChatScreen.create(this,chatId,title));}catch(Exception e){android.util.Log.e("SiriusOriginalUI","Open original chat failed",e);dialog("Messages","Unable to open messages. Please try again.");}});},()->{});}
    void meetingStatus(String id,String status){request(()->api.request("PATCH","/meetings/"+id,new JSONObject().put("status",status)),()->show("Meetings"));}
    void venue(){backTarget="home";getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);setContentView(VenueMaps.create(this));}
    void news(){try{setContentView(OriginalEditorial.news(this));}catch(Exception e){OriginalUi.failed(this,e);}}
    void partners(){try{setContentView(OriginalEditorial.partners(this));}catch(Exception e){OriginalUi.failed(this,e);}}
    String recoverySurname="",recoveryChallenge="",recoveryDestination="";
    int recoveryGeneration;
    boolean recoveryPending;
    View recoverySurnameView,authBlocker;
    final Map<Long,JSONObject> recoveryItems=new LinkedHashMap<>();
    void setAuthBlocked(boolean blocked){
        if(authBlocker!=null&&authBlocker.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)authBlocker.getParent()).removeView(authBlocker);
        authBlocker=null;
        if(blocked&&screenHost!=null){FrameLayout shield=new FrameLayout(this);shield.setClickable(true);shield.setFocusable(true);shield.setBackgroundColor(0x55ffffff);ProgressBar progress=new ProgressBar(this);shield.addView(progress,new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.CENTER));screenHost.addView(shield,new FrameLayout.LayoutParams(-1,-1));authBlocker=shield;}
    }
    void resetRecovery(){recoveryGeneration++;recoveryPending=false;authPending=false;setAuthBlocked(false);recoverySurname="";recoveryChallenge="";recoveryDestination="";recoveryItems.clear();if(recoverySurnameView!=null){originalScreens().disposeRecoveryComposition(recoverySurnameView);recoverySurnameView=null;}}
    void recoveryError(String resource){dialog(originalScreens().originalString("auth_registration"),originalScreens().originalString(resource));}
    void recoverId(){
        if(recoveryPending||authPending)return;
        page="recovery";backTarget="login";
        try{if(recoverySurnameView==null){recoverySurnameView=originalScreens().recovery();originalScreens().retainRecoveryComposition(recoverySurnameView);}if(recoverySurnameView.getParent()==null)setContentView(recoverySurnameView);}
        catch(Exception|LinkageError error){recoveryError("error_network");}
    }
    void recoveryRequest(NetworkJob job,Runnable success){
        if(recoveryPending||authPending)return;
        recoveryPending=true;setAuthBlocked(true);
        final int generation=accountGeneration,flow=recoveryGeneration;
        network.execute(()->{try{job.run();runOnUiThread(()->{if(generation!=accountGeneration||flow!=recoveryGeneration||isFinishing()||isDestroyed())return;recoveryPending=false;setAuthBlocked(false);success.run();});}
            catch(Exception error){runOnUiThread(()->{if(generation!=accountGeneration||flow!=recoveryGeneration||isFinishing()||isDestroyed())return;recoveryPending=false;setAuthBlocked(false);if(error instanceof org.sirius.biotech.data.ApiClient.RateLimitException)dialog(originalScreens().originalString("auth_registration"),tr("Too many sign-in attempts. Retry in seconds:")+" "+((org.sirius.biotech.data.ApiClient.RateLimitException)error).retryAfter);else recoveryError(error instanceof org.sirius.biotech.data.ApiClient.HttpException&&((org.sirius.biotech.data.ApiClient.HttpException)error).status==404?"auth_error_not_found":"error_network");});}
        });
    }
    void searchRecovery(String surname){
        if(recoveryPending||authPending)return;
        recoverySurname=surname;
        final JSONObject[] result={null};
        recoveryRequest(()->result[0]=api.searchAuth(surname),()->{
            try{JSONArray items=result[0].getJSONArray("items");recoveryItems.clear();recoveryChallenge="";for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);if(item.getString("selection_id").isEmpty())throw new IllegalArgumentException();item.getString("name");recoveryItems.put((long)i+1,item);}if(recoveryItems.isEmpty()){recoveryError("auth_error_not_found");return;}show("recoveryList");}catch(Exception error){recoveryError("error_network");}
        });
    }
    void recoveryList(){
        recoveryChallenge="";recoveryDestination="";backTarget="recovery";
        try{setContentView(originalScreens().recoveryAttendees());}catch(Exception|LinkageError error){show("recovery");recoveryError("error_network");}
    }
    void sendRecoveryPin(long localId){
        if(recoveryPending||authPending||!"recoveryList".equals(page))return;
        JSONObject item=recoveryItems.get(localId);if(item==null)return;
        if(item.isNull("contact")||item.optString("contact").isEmpty()){recoveryError("auth_error_no_contact");return;}
        final JSONObject[] result={null};
        recoveryRequest(()->result[0]=api.sendPin(item.getString("selection_id")),()->{
            try{JSONObject response=result[0];if(!"success".equals(response.getString("status")))throw new IllegalArgumentException();String challenge=response.getString("challenge_id"),contact=response.getString("contact");if(challenge.isEmpty()||contact.isEmpty())throw new IllegalArgumentException();recoveryChallenge=challenge;recoveryDestination=contact;show("recoveryPin");}catch(Exception error){recoveryError("error_network");}
        });
    }
    void recoveryPin(){backTarget="recoveryList";if(recoveryChallenge.isEmpty()){show("recoveryList");return;}try{setContentView(originalScreens().login(recoveryChallenge,recoveryDestination));}catch(Exception|LinkageError error){show("recoveryList");recoveryError("error_network");}}
    void choosePhoto(){new AlertDialog.Builder(this).setTitle(tr("Profile photo")).setMessage(tr("Your photo will be accessible by its direct link.")).setNegativeButton(tr("Cancel"),null).setPositiveButton(tr("Continue"),(d,w)->{Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.setType("image/*");pick.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/jpeg","image/png","image/webp"});pick.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(pick,701);}).show();}
    @Override protected void onActivityResult(int code,int result,Intent intent){super.onActivityResult(code,result,intent);if(code==703){java.util.function.Consumer<String> callback=meetingLocationSelected;meetingLocationSelected=null;if(callback!=null&&result==RESULT_OK&&intent!=null){String chosen=intent.getStringExtra("meeting_location");if(chosen!=null&&!chosen.trim().isEmpty())callback.accept(chosen);}return;}if(code==702){if(result==RESULT_OK&&intent!=null&&intent.getData()!=null&&chatPhotoSelected!=null)chatPhotoSelected.accept(intent.getData());return;}if(code!=701||result!=RESULT_OK||intent==null||intent.getData()==null)return;android.net.Uri uri=intent.getData();final int photoOwner=accountGeneration;request(()->{String mime=getContentResolver().getType(uri);if(!"image/jpeg".equals(mime)&&!"image/png".equals(mime)&&!"image/webp".equals(mime))throw new java.io.IOException("JPEG or PNG required");java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();try(java.io.InputStream in=getContentResolver().openInputStream(uri)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(bytes.size()+n>20*1024*1024)throw new java.io.IOException("Photo exceeds 20 MiB");bytes.write(b,0,n);}}byte[] raw=bytes.toByteArray();final android.graphics.Bitmap image=ProfilePhotoInput.decode(raw);runOnUiThread(()->{if(photoOwner!=accountGeneration||isDestroyed()){image.recycle();return;}backTarget="Profile";setContentView(AvatarCropView.create(this,image,jpeg->{if(jpeg==null){show("Profile");return;}request(()->{serverProfile=api.uploadAvatar(jpeg,"image/jpeg");},()->{applyAccount();show("Profile");});}));});},()->{});}
    void profile(){try{backTarget="home";FrameLayout layout=new FrameLayout(this);layout.addView(originalScreens().profile(),new FrameLayout.LayoutParams(-1,-1));
        int filled=0;for(String key:new String[]{"name","company","position","bio","avatar_url"})if(!serverProfile.optString(key,"").trim().isEmpty())filled++;final int percent=filled*20;
        FrameLayout completion=new FrameLayout(this);GradientDrawable completionBackground=new GradientDrawable();completionBackground.setColor(Color.WHITE);float corner=dp(8);completionBackground.setCornerRadii(new float[]{corner,corner,corner,corner,0,0,0,0});completion.setBackground(completionBackground);TextView progressLabel=new TextView(this);progressLabel.setGravity(Gravity.CENTER);progressLabel.setTextSize(12);progressLabel.setTypeface(null,Typeface.BOLD);progressLabel.setTextColor(muted);progressLabel.setText("ru".equals(store.getString("appLanguage","ru"))?"Профиль заполнен на "+percent+"%":"The profile is "+percent+"% complete");completion.addView(progressLabel,new FrameLayout.LayoutParams(-1,-1));View progressLine=new View(this);progressLine.setBackgroundColor(0xff15bf68);completion.addView(progressLine,new FrameLayout.LayoutParams(0,dp(2),Gravity.BOTTOM|Gravity.START));completion.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or_,ob)->{ViewGroup.LayoutParams lp=progressLine.getLayoutParams();int width=(r-l)*percent/100;if(lp.width!=width){lp.width=width;progressLine.setLayoutParams(lp);}});FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-1,dp(36),Gravity.TOP);cp.setMargins(dp(10),dp(88),dp(10),0);layout.addView(completion,cp);
        ImageView avatar=new ImageView(this);avatar.setImageResource(getResources().getIdentifier("icon_attendee_56","drawable",getPackageName()));ProfilePhotos.load(this,avatar,serverProfile.optString("avatar_url"));avatar.setContentDescription(tr("Change profile photo"));FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.TOP|Gravity.END);p.setMargins(0,dp(6),dp(12),0);layout.addView(avatar,p);avatar.setOnClickListener(v->choosePhoto());setContentView(layout);return;}catch(Exception | LinkageError e){android.util.Log.e("SiriusOriginalUI","Original profile failed; using local fallback",e);}base("Profile","home");LinearLayout c=card(body);c.addView(bold(store.getString("profileName","Participant"),24));c.addView(text(store.getString("profileBio","Your organisation / role"),16,ink));note(c);button(c,"Edit name",()->input("Profile name","Name",s->{store.edit().putString("profileName",s).apply();profile();}));button(c,"Edit organisation / role",()->input("Organisation / role","Details",s->{store.edit().putString("profileBio",s).apply();profile();}));button(c,"My saved contacts",()->{StringBuilder b=new StringBuilder();for(int i=0;i<names.length;i++)if(isFavorite("people",participants.get(i).id))b.append(names[i]).append("\n");dialog("Saved contacts",b.length()>0?b.toString():"No saved contacts yet.");});}
    void settings(){try{backTarget="home";setContentView(originalScreens().settings());return;}catch(Exception | LinkageError e){android.util.Log.e("SiriusOriginalUI","Settings renderer unavailable",e);}base("Settings","home");LinearLayout c=card(body);button(c,"Language",()->OriginalScreens.showLanguageChooser(this,false));button(c,"Privacy Policy",()->dialog("Privacy Policy",dataUseText()));button(c,"Sign out",this::signOutLive);}
    String dataUseText(){return tr("Your profile, favorites, meetings and messages are used to provide event services. Your photo is accessible by direct link. Contact the organizer to request deletion of your data.");}
    void input(String title,String hint,Changed fn){Context themed=new ContextThemeWrapper(this,getResources().getIdentifier("Theme.Eventicious.App","style",getPackageName()));LinearLayout panel=new LinearLayout(themed);panel.setOrientation(1);panel.setPadding(dp(20),dp(8),dp(20),dp(8));EditText e=new EditText(themed);e.setHint(tr(hint));e.setTextColor(ink);e.setTextSize(16);e.setPadding(dp(16),dp(14),dp(16),dp(14));e.setBackground(shape(0xfff1f2f5,12));e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)});panel.addView(e,new LinearLayout.LayoutParams(-1,-2));AlertDialog d=new AlertDialog.Builder(themed).setTitle(tr(title)).setView(panel).setNegativeButton(tr("Cancel"),null).setPositiveButton(tr("Continue"),null).create();d.setOnShowListener(v->{d.getButton(-1).setOnClickListener(w->{String s=e.getText().toString().trim();if(s.isEmpty()){e.setError(tr("Please enter text"));return;}d.dismiss();fn.run(s);});e.requestFocus();d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);});d.show();}
    void append(String key,String s){JSONArray a=new JSONArray();try{a=new JSONArray(store.getString(key,"[]"));}catch(Exception e){}a.put(s);store.edit().putString(key,a.toString()).apply();}
    List<String> entries(String key){List<String> list=new ArrayList<>();try{JSONArray a=new JSONArray(store.getString(key,"[]"));for(int i=0;i<a.length();i++)list.add(a.getString(i));}catch(Exception e){}return list;}
    void dialog(String title,String message){new AlertDialog.Builder(this).setTitle(tr(title)).setMessage(tr(message)).setPositiveButton(tr("OK"),null).show();}
    void toast(String s){Toast.makeText(this,tr(s),Toast.LENGTH_LONG).show();}
    @Override public void onBackPressed(){if(sceneBack!=null){Runnable back=sceneBack;sceneBack=null;back.run();return;}if(backTarget!=null){show(backTarget);}else if(page.equals("events")){super.onBackPressed();}else if(page.equals("welcome")){show("about");}else if(page.equals("home")){show("events");}else show("home");}
}

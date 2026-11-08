package org.sirius.biotech;

import android.app.AlertDialog;
import android.view.View;
import java.lang.reflect.*;
import java.util.*;

/** Local state adapters for the retained screen renderers; never creates production view models. */
final class OriginalScreens {
    final MainActivity activity;
    final OriginalMenu host;
    OriginalScreens(MainActivity activity, OriginalMenu host) { this.activity=activity; this.host=host; }
    static Class<?> type(String name) throws Exception { return Class.forName(name); }
    static Object unit() throws Exception { return type("rl.q").getField("a").get(null); }
    static Object singleton(String name,String field) throws Exception { return type(name).getField(field).get(null); }
    static Object make(String name,Class<?>[] types,Object... args) throws Exception { return type(name).getConstructor(types).newInstance(args); }
    static Object enumeration(String name,String value) throws Exception { return type(name).getMethod("valueOf",String.class).invoke(null,value); }
    interface Call { Object invoke(Object[] args) throws Exception; }
    static Object callback(String name,Call call) throws Exception {
        return Proxy.newProxyInstance(OriginalScreens.class.getClassLoader(),new Class<?>[]{type(name)},(p,m,a)->{
            if(m.getDeclaringClass()==Object.class) {
                if(m.getName().equals("hashCode"))return System.identityHashCode(p);
                if(m.getName().equals("equals"))return p==a[0];
                return "OfflineScreenCallback";
            }
            return call.invoke(a);
        });
    }
    Object action(Runnable run) throws Exception { return callback("em.a",a->{run.run();return unit();}); }
    View render(String name,String method,Class<?>[] signature,Object[] prefix,Object[] suffix) throws Exception {
        Method renderer=type(name).getMethod(method,signature);
        return host.themedHost(callback("em.h",a->{
            Object[] args=new Object[prefix.length+1+suffix.length];
            System.arraycopy(prefix,0,args,0,prefix.length);args[prefix.length]=a[0];
            System.arraycopy(suffix,0,args,prefix.length+1,suffix.length);
            renderer.invoke(null,args);return unit();
        }));
    }
    static void showLanguageChooser(MainActivity activity, boolean firstInstall) {
        android.content.Context themed=new android.view.ContextThemeWrapper(activity,activity.getResources().getIdentifier("Theme.Eventicious.App","style",activity.getPackageName()));
        android.view.LayoutInflater inflater=android.view.LayoutInflater.from(themed);
        View sheet=inflater.inflate(activity.getResources().getIdentifier("languages_sheet","layout",activity.getPackageName()),null,false);
        android.widget.LinearLayout languages=sheet.findViewById(activity.getResources().getIdentifier("languages","id",activity.getPackageName()));
        AlertDialog dialog=new AlertDialog.Builder(themed).setView(sheet).setCancelable(!firstInstall).create();
        for(String code:new String[]{"ru","en"}) {
            android.widget.TextView row=(android.widget.TextView)inflater.inflate(activity.getResources().getIdentifier("languages_item_view","layout",activity.getPackageName()),languages,false);
            Locale locale=Locale.forLanguageTag(code);
            String nativeName=locale.getDisplayLanguage(locale);
            row.setText(nativeName.substring(0,1).toUpperCase(locale)+nativeName.substring(1));
            row.setOnClickListener(v->{activity.store.edit().putString("appLanguage",code).apply();dialog.dismiss();activity.recreate();});
            languages.addView(row);
        }
        View close=sheet.findViewById(android.R.id.closeButton);
        close.setVisibility(firstInstall?View.GONE:View.VISIBLE);
        close.setOnClickListener(v->dialog.dismiss());
        dialog.show();
    }
    String originalString(String name) {
        return activity.getString(activity.getResources().getIdentifier(name,"string",activity.getPackageName()));
    }
    Object authInfo() throws Exception {return authInfo("Regular");}
    Object authInfo(String mode) throws Exception {
        return make("xa.f",new Class<?>[]{long.class,type("com.eventicious.auth.AuthInfo$Mode"),type("com.eventicious.auth.AuthInfo$Context"),type("com.eventicious.auth.AuthSource"),List.class,List.class},1L,enumeration("com.eventicious.auth.AuthInfo$Mode",mode),enumeration("com.eventicious.auth.AuthInfo$Context","Default"),enumeration("com.eventicious.auth.AuthSource","EventOpen"),Collections.emptyList(),Collections.emptyList());
    }
    Object authRouter(String destination) throws Exception {
        return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{type("com.eventicious.auth.common.p0")},(p,m,a)->{
            if(m.getDeclaringClass()==Object.class){if(m.getName().equals("hashCode"))return System.identityHashCode(p);if(m.getName().equals("equals"))return p==a[0];return "AuthRouter";}
            activity.show(destination);return null;
        });
    }
    View registration() throws Exception {
        Object settings=make("com.eventicious.api.content.entities.EventComponent$Profile$Settings",new Class<?>[]{int.class,String.class,boolean.class,boolean.class,boolean.class,type("com.eventicious.api.content.entities.EventComponent$Profile$AuthType"),Set.class,Set.class,List.class},6,null,true,true,false,null,null,null,null);
        Object state=make("com.eventicious.auth.pin.a",new Class<?>[]{type("xa.f"),type("com.eventicious.api.content.entities.EventComponent$Profile$Settings"),String.class,String.class},authInfo("Initial"),settings,originalString("auth_registration"),originalString("auth_by_pin_hint"));
        return render("com.eventicious.auth.pin.d","b",new Class<?>[]{type("com.eventicious.auth.pin.a"),type("com.eventicious.auth.common.p0"),type("em.a"),type("em.a"),type("androidx.compose.ui.n"),type("androidx.compose.runtime.l"),int.class},new Object[]{state,authRouter("about"),action(()->activity.show("login")),action(()->activity.recoverId()),singleton("androidx.compose.ui.k","a")},new Object[]{0});
    }
    View recovery() throws Exception {
        Object settings=make("com.eventicious.api.content.entities.EventComponent$Profile$Settings",new Class<?>[]{int.class,String.class,boolean.class,boolean.class,boolean.class,type("com.eventicious.api.content.entities.EventComponent$Profile$AuthType"),Set.class,Set.class,List.class},6,null,true,true,false,null,null,null,null);
        Object state=make("com.eventicious.auth.pin.method.e",new Class<?>[]{type("xa.f"),type("com.eventicious.api.content.entities.EventComponent$Profile$Settings"),boolean.class},authInfo(),settings,false);
        Object next=callback("em.f",args->{
            String name=(String)args[0];
            if(name.trim().isEmpty()){activity.toast(activity.tr("Please enter text"));return unit();}
            if(name.trim().length()>100){activity.toast(activity.tr("Last name must be 100 characters or fewer."));return unit();}
            activity.searchRecovery(name.trim());
            return unit();
        });
        Object external=callback("ya.a",args->{activity.toast(activity.tr("Please sign in with your registration ID"));return null;});
        return render("com.eventicious.auth.pin.method.j","b",new Class<?>[]{type("com.eventicious.auth.pin.method.e"),type("com.eventicious.auth.common.p0"),type("em.f"),type("ya.a"),type("androidx.compose.ui.n"),type("androidx.compose.runtime.l"),int.class},new Object[]{state,authRouter("login"),next,external,singleton("androidx.compose.ui.k","a")},new Object[]{0});
    }
    View recoveryAttendees() throws Exception {
        Object settings=make("com.eventicious.api.content.entities.EventComponent$Profile$Settings",new Class<?>[]{int.class,String.class,boolean.class,boolean.class,boolean.class,type("com.eventicious.api.content.entities.EventComponent$Profile$AuthType"),Set.class,Set.class,List.class},6,null,true,true,false,null,null,null,null);
        List<Object> profiles=new ArrayList<>();
        for(Map.Entry<Long,org.json.JSONObject> entry:activity.recoveryItems.entrySet()){
            org.json.JSONObject item=entry.getValue();
            Object contact=item.isNull("contact")||item.optString("contact").isEmpty()?null:make("com.eventicious.api.mobile.entities.AuthProfile$Contact",new Class<?>[]{type("com.eventicious.api.mobile.entities.AuthProfile$Contact$Type"),String.class},enumeration("com.eventicious.api.mobile.entities.AuthProfile$Contact$Type","Email"),item.getString("contact"));
            // Serialization mask: id, firstName, lastName, thumbnail, contact are all explicitly supplied.
            profiles.add(make("com.eventicious.api.mobile.entities.AuthProfile",new Class<?>[]{int.class,long.class,String.class,String.class,String.class,type("com.eventicious.api.mobile.entities.AuthProfile$Contact")},31,entry.getKey(),item.getString("name"),null,null,contact));
        }
        Object state=make("com.eventicious.auth.pin.attendees.d",new Class<?>[]{type("xa.f"),type("com.eventicious.api.content.entities.EventComponent$Profile$Settings"),List.class,boolean.class},authInfo(),settings,profiles,false);
        Object selected=callback("em.f",args->{long id=(Long)type("com.eventicious.api.mobile.entities.AuthProfile").getMethod("a").invoke(args[0]);activity.sendRecoveryPin(id);return unit();});
        return render("com.eventicious.auth.pin.attendees.n","b",new Class<?>[]{type("com.eventicious.auth.pin.attendees.d"),type("com.eventicious.auth.common.p0"),type("em.f"),type("em.a"),type("androidx.compose.ui.n"),type("androidx.compose.runtime.l"),int.class},new Object[]{state,authRouter("recovery"),selected,action(()->activity.recoveryError("auth_error_not_found")),singleton("androidx.compose.ui.k","a")},new Object[]{0});
    }
    // Keep the surname renderer's own remembered text while temporarily showing the participant list.
    void retainRecoveryComposition(View view) throws Exception {
        Object strategy=callback("androidx.compose.ui.platform.o2",args->action(()->{}));
        type("androidx.compose.ui.platform.a").getMethod("setViewCompositionStrategy",type("androidx.compose.ui.platform.o2")).invoke(view,strategy);
    }
    void disposeRecoveryComposition(View view) {
        if(view==null)return;
        try{type("androidx.compose.ui.platform.a").getMethod("e").invoke(view);}catch(Exception ignored){}
    }
    View welcome() throws Exception {
        Object description=make("c3.g",new Class<?>[]{String.class},activity.tr("Your personal guide to Sirius Biotech 2027."));
        Object welcome=make("com.eventicious.auth.welcome.c",new Class<?>[]{String.class,type("c3.g"),boolean.class},activity.tr("Sirius Biotech 2027"),description,false);
        Object onboarding=make("com.eventicious.auth.welcome.b",new Class<?>[]{String.class,String.class},activity.tr("Connect and explore"),activity.tr("Find participants, plan sessions and discover biotechnology projects."));
        Object state=make("com.eventicious.auth.welcome.d",new Class<?>[]{type("com.eventicious.auth.welcome.c"),type("com.eventicious.auth.welcome.b"),boolean.class},welcome,onboarding,false);
        Object next=action(()->activity.show("login"));
        return render("com.eventicious.auth.welcome.a","c",new Class<?>[]{type("com.eventicious.auth.welcome.d"),type("em.a"),type("em.a"),type("androidx.compose.ui.n"),type("androidx.compose.runtime.l"),int.class,int.class},new Object[]{state,next,next,null},new Object[]{0,8});
    }
    View settings() throws Exception {
        String base="com.eventicious.profile.ui.settings.";
        Object about=singleton(base+"c","c"),language=make(base+"e",new Class<?>[]{String.class},Locale.forLanguageTag(activity.store.getString("appLanguage","en")).getDisplayLanguage(activity.getResources().getConfiguration().getLocales().get(0))),notifications=singleton(base+"f","c"),personal=singleton(base+"g","c");
        Object privacy=make(base+"h",new Class<?>[]{String.class},activity.tr("Privacy Policy")),signout=make(base+"h",new Class<?>[]{String.class},activity.tr("Sign out"));
        List<Object> buttons=Arrays.asList(about,language,notifications,personal,privacy,signout);
        Object click=callback("em.f",a->{
            Object item=a[0];
            if(item==about)activity.dialog(activity.tr("Sirius Biotech 2027"),activity.tr("Your personal guide to Sirius Biotech 2027."));
            else if(item==language)showLanguageChooser(activity,false);
            else if(item==notifications){android.content.Intent intent=new android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,activity.getPackageName());activity.startActivity(intent);}
            else if(item==personal)activity.dialog(activity.tr("Consent to personal data processing"),activity.dataUseText());
            else if(item==privacy)activity.dialog(activity.tr("Privacy Policy"),activity.dataUseText());
            else if(item==signout)activity.signOutLive();
            return unit();
        });
        return render(base+"a","b",new Class<?>[]{List.class,type("em.f"),boolean.class,type("androidx.compose.ui.n"),type("androidx.compose.runtime.l"),int.class,int.class},new Object[]{buttons,click,false,null},new Object[]{0,8});
    }
    View login() throws Exception {return login(null,null);}
    View login(String challengeId,String contact) throws Exception {
        final boolean challenge=challengeId!=null;
        Object auth=make("xa.f",new Class<?>[]{long.class,type("com.eventicious.auth.AuthInfo$Mode"),type("com.eventicious.auth.AuthInfo$Context"),type("com.eventicious.auth.AuthSource"),List.class,List.class},1L,enumeration("com.eventicious.auth.AuthInfo$Mode","Regular"),enumeration("com.eventicious.auth.AuthInfo$Context","Default"),enumeration("com.eventicious.auth.AuthSource","EventOpen"),Collections.emptyList(),Collections.emptyList());
        // Closed registration selects the original "Forgot ID?" action instead of signup.
        Object settings=make("com.eventicious.api.content.entities.EventComponent$Profile$Settings",new Class<?>[]{int.class,String.class,boolean.class,boolean.class,boolean.class,type("com.eventicious.api.content.entities.EventComponent$Profile$AuthType"),Set.class,Set.class,List.class},6,null,true,true,false,null,null,null,null);
        Object state=make("com.eventicious.auth.enterpin.a",new Class<?>[]{type("xa.f"),type("com.eventicious.api.content.entities.EventComponent$Profile$Settings"),String.class,String.class,String.class,String.class,boolean.class,boolean.class,boolean.class,boolean.class,String.class},auth,settings,activity.tr("Registration"),challenge?String.format(originalString("auth_enter_pin_sent_id_hint"),android.text.BidiFormatter.getInstance().unicodeWrap(contact)):originalString("auth_by_pin_hint"),null,challenge?originalString("auth_enter_pin_description"):activity.tr("Enter the six-digit ID provided by the organizer."),!challenge,false,false,false,null);
        Object text=make("a2.n",new Class<?>[]{String.class,long.class},"",0L);
        Object router=Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{type("com.eventicious.auth.common.p0")},(p,m,a)->{
            if(m.getDeclaringClass()==Object.class){if(m.getName().equals("hashCode"))return System.identityHashCode(p);if(m.getName().equals("equals"))return p==a[0];return "OfflineAuthRouter";}
            activity.show(challenge?"recoveryList":"welcome");return null;
        });
        Object help=action(()->activity.recoverId());
        View view=render("com.eventicious.auth.enterpin.n","a",new Class<?>[]{type("com.eventicious.auth.enterpin.a"),type("a2.n"),type("com.eventicious.auth.common.p0"),type("em.a"),type("em.a"),type("androidx.compose.ui.n"),type("androidx.compose.runtime.l"),int.class},new Object[]{state,text,router,help,help,singleton("androidx.compose.ui.k","a")},new Object[]{0});
        // The retained wrapper normally submits through its network ViewModel. Observe only while attached.
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            boolean attached; String last="";
            final Runnable poll=new Runnable(){public void run(){
                if(!attached)return;
                try{
                    String value=type("a2.n").getMethod("c").invoke(text).toString();
                    if(!value.equals(last)){
                        last=value;
                        if(value.length()==6){
                            ((android.view.inputmethod.InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(view.getWindowToken(),0);
                            if(challenge)activity.authenticateRecovery(value,challengeId);else activity.authenticateLive(value);
                        }
                    }
                }catch(Exception e){android.util.Log.e("SiriusOriginalUI","PIN state observation failed",e);return;}
                view.postDelayed(this,100);
            }};
            public void onViewAttachedToWindow(View v){attached=true;v.post(poll);}
            public void onViewDetachedFromWindow(View v){attached=false;v.removeCallbacks(poll);}
        });
        return view;
    }
    View profile() throws Exception {
        String base="com.eventicious.profile.ui.edit.";
        List<Object> fields=new ArrayList<>();
        for(String[] field:new String[][]{{"name",activity.tr("Name")},{"company",OriginalUi.label(activity,"Организация","Organization")},{"position",OriginalUi.label(activity,"Должность","Position")},{"bio",OriginalUi.label(activity,"О себе","About")}})
            fields.add(make(base+"n",new Class<?>[]{String.class,boolean.class,String.class,String.class,type("a2.a"),type("androidx.compose.ui.autofill.p"),type("androidx.compose.foundation.text.q0"),int.class},field[0],false,field[1],"",null,null,null,480));
        int icon=activity.getResources().getIdentifier("icon_profile_16","drawable",activity.getPackageName());
        Object group=make(base+"p",new Class<?>[]{int.class,String.class,List.class},icon,activity.tr("Profile"),fields);
        Object groups=make("rm.h",new Class<?>[]{Object[].class},(Object)new Object[]{group});
        Object emptyMap=singleton("sm.c","c");
        Object builder=make("sm.d",new Class<?>[]{type("sm.c")},emptyMap);
        for(String[] entry:new String[][]{{"name",activity.store.getString("profileName","")},{"company",activity.store.getString("profileCompany","")},{"position",activity.store.getString("profilePosition","")},{"bio",activity.store.getString("profileBio","")}}){
            Object value=make(base+"q",new Class<?>[]{String.class,String.class,boolean.class,boolean.class},entry[0],entry[1],false,false);
            type("sm.d").getMethod("put",Object.class,Object.class).invoke(builder,entry[0],value);
        }
        Object values=type("sm.d").getMethod("f").invoke(builder);
        Object state=make(base+"b",new Class<?>[]{type("qm.b"),type("qm.b"),type("qm.e"),String.class},groups,singleton("rm.h","b"),values,"RU");
        Object close=action(()->activity.show("home"));
        Object settings=action(()->activity.show("Settings"));
        Object info=action(()->activity.dialog("Entry information", activity.ownPersonId.isEmpty()
                ? activity.tr("No participant information is available. Sign in with your organizer-issued ID.")
                : activity.tr("Participant ID") + ": " + activity.ownPersonId));
        Object save=callback("em.h",a->{
            Method read=type(base+"f0").getMethod("b",String.class);
            String name=(String)read.invoke(a[0],"name"),company=(String)read.invoke(a[0],"company"),position=(String)read.invoke(a[0],"position"),bio=(String)read.invoke(a[0],"bio");
            activity.saveProfileLive(name,company,position,bio);return unit();
        });
        return render(base+"e0","c",new Class<?>[]{type(base+"e"),type("em.a"),type("em.a"),type("em.a"),type("em.a"),type("em.a"),type("em.h"),type("androidx.compose.ui.n"),type("com.eventicious.ViewMode"),type("androidx.compose.runtime.l"),int.class},new Object[]{state,settings,info,info,info,close,save,singleton("androidx.compose.ui.k","a"),enumeration("com.eventicious.ViewMode","Default")},new Object[]{0});
    }
}

package org.sirius.biotech;

import android.os.Bundle;
import android.view.View;
import java.lang.reflect.*;
import java.util.*;

/** Reflection bridge to the retained MSS Compose bytecode, without its Flow/Koin bootstrap. */
final class OriginalMenu {
    private final MainActivity activity;
    private Object lifecycle, registry, controller, owner, dispatcher;

    OriginalMenu(MainActivity activity) { this.activity = activity; }
    private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name); }
    private static Object field(Object object, String name) throws Exception {
        return object.getClass().getField(name).get(object);
    }
    private static Object unit() throws Exception { return type("rl.q").getField("a").get(null); }
    interface Call { Object invoke(Object[] args) throws Exception; }
    private static Object proxy(String name, Call call) throws Exception {
        return Proxy.newProxyInstance(OriginalMenu.class.getClassLoader(), new Class<?>[]{type(name)}, (p,m,a)-> {
            if (m.getDeclaringClass() == Object.class) {
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("equals")) return p == a[0];
                return "OfflineOriginalMenuCallback";
            }
            return call.invoke(a);
        });
    }
    private void owners() throws Exception {
        if (owner != null) return;
        // The owner proxy supplies the same lifecycle and saved-state contracts as ComponentActivity.
        dispatcher = type("androidx.activity.f0").getConstructor(Runnable.class).newInstance((Runnable)()->activity.onBackPressed());
        owner = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type("f8.f"),type("androidx.activity.h0")}, (p,m,a)-> {
            if (m.getName().equals("c")) return dispatcher;
            if (m.getName().equals("getLifecycle")) return lifecycle;
            if (m.getName().equals("k")) return registry;
            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
            if (m.getName().equals("equals")) return p == a[0];
            return "OfflineMenuOwner";
        });
        lifecycle = type("androidx.lifecycle.a0").getConstructor(type("androidx.lifecycle.y"),boolean.class).newInstance(owner,true);
        Object attach = type("com.eventicious.streams.ui.selector.d").getConstructor(Object.class,int.class).newInstance(owner,9);
        Object state = type("androidx.media3.common.b0").getConstructor(type("f8.f"),type("com.eventicious.streams.ui.selector.d")).newInstance(owner,attach);
        controller = type("f2.l0").getConstructor(type("androidx.media3.common.b0")).newInstance(state);
        registry = field(controller,"c");
        controller.getClass().getMethod("B",Bundle.class).invoke(controller,(Object)null);
        event("ON_CREATE");
        event("ON_START");
        event("ON_RESUME");
        View decor = activity.getWindow().getDecorView();
        decor.setTag(activity.getResources().getIdentifier("view_tree_lifecycle_owner","id",activity.getPackageName()),owner);
        decor.setTag(activity.getResources().getIdentifier("view_tree_saved_state_registry_owner","id",activity.getPackageName()),owner);
        decor.setTag(activity.getResources().getIdentifier("view_tree_on_back_pressed_dispatcher_owner","id",activity.getPackageName()),owner);
    }
    void event(String name) {
        if (lifecycle == null) return;
        try {
            Object event = type("androidx.lifecycle.Lifecycle$Event").getMethod("valueOf",String.class).invoke(null,name);
            lifecycle.getClass().getMethod("e",type("androidx.lifecycle.Lifecycle$Event")).invoke(lifecycle,event);
        } catch (Exception e) { android.util.Log.e("SiriusOriginalUI","Menu lifecycle failed",e); }
    }
    private int drawable(String name) {
        int id=activity.getResources().getIdentifier(name,"drawable",activity.getPackageName());
        if(id==0) throw new IllegalArgumentException("Missing original icon: "+name);
        return id;
    }
    private String menuLogo() throws Exception {
        // Coil preserves source pixels; prepare a density-correct source for this header only.
        int width=activity.dp(128);java.io.File file=new java.io.File(activity.getCacheDir(),"menu-logo-2027-v1-"+width+".png");
        if(!file.isFile())try(java.io.InputStream source=activity.getAssets().open("menu-logo-2027.png")){
            android.graphics.Bitmap original=android.graphics.BitmapFactory.decodeStream(source);
            int left=original.getWidth(),top=original.getHeight(),right=0,bottom=0;
            for(int y=0;y<original.getHeight();y++)for(int x=0;x<original.getWidth();x++)if(android.graphics.Color.alpha(original.getPixel(x,y))>16){left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x);bottom=Math.max(bottom,y);}
            android.graphics.Bitmap tight=right>=left&&bottom>=top?android.graphics.Bitmap.createBitmap(original,left,top,right-left+1,bottom-top+1):original;
            android.graphics.Bitmap scaled=android.graphics.Bitmap.createScaledBitmap(tight,width,Math.round(width*tight.getHeight()/(float)tight.getWidth()),true);
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){scaled.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}scaled.recycle();if(tight!=original)tight.recycle();original.recycle();
        }
        return android.net.Uri.fromFile(file).toString();
    }
    View create() throws Exception {
        owners();
        List<Object> main = new ArrayList<>();
        String[][] items = {
            {"Programme","icon_calendar_16"}, {"Profile","icon_profile_16"},
            {"Participants","icon_user_16"}, {"Messages","icon_message_16"},
            {"Meetings","icon_meeting_16"}, {"My schedule","icon_star_16"},
            {"Venue","icon_map_16"}, {"News","icon_news_16"}, {"Partners","icon_hand_16"}
        };
        Class<?> component=type("com.eventicious.api.content.entities.EventComponent");
        Object unknown=type("com.eventicious.api.content.entities.EventComponent$Unknown").getField("INSTANCE").get(null);
        Constructor<?> item=type("com.eventicious.menu.h").getConstructor(String.class,String.class,int.class,boolean.class,component);
        try(java.io.InputStream stream=activity.getAssets().open("shared.json")){java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;while((count=stream.read(buffer))!=-1)bytes.write(buffer,0,count);org.json.JSONArray sections=new org.json.JSONObject(bytes.toString("UTF-8")).getJSONArray("sections");boolean ru="ru".equals(activity.getResources().getConfiguration().getLocales().get(0).getLanguage());for(int n=0;n<sections.length();n++){org.json.JSONObject s=sections.getJSONObject(n);main.add(item.newInstance(s.getString("androidId"),s.getString(ru?"labelRu":"labelEn"),drawable(s.getString("drawable")),false,unknown));}}
        catch(java.io.FileNotFoundException e){for(String[] entry:items)main.add(item.newInstance(entry[0],activity.tr(entry[0]),drawable(entry[1]),false,unknown));}
        List<Object> system=Collections.singletonList(type("com.eventicious.menu.i").getConstructor(String.class).newInstance(activity.tr("Events")));
        Object header=type("com.eventicious.menu.e").getConstructor(String.class,String.class).newInstance(menuLogo(),null);
        Class<?> stateType=type("com.eventicious.menu.l");
        Object state=stateType.getConstructor(type("com.eventicious.menu.f"),type("com.eventicious.menu.b"),List.class,List.class,type("com.eventicious.menu.c"),type("com.eventicious.menu.k"))
            .newInstance(header,null,main,system,null,null);
        Object click=proxy("em.f",args->{
            String id=(String)type("com.eventicious.menu.j").getMethod("c").invoke(args[0]);
            activity.show(id.equals("exit")?"events":id);
            return unit();
        });
        Object settings=proxy("em.a",args->{activity.show("Settings");return unit();});
        Object expiration=proxy("em.a",args->unit());
        Class<?> composer=type("androidx.compose.runtime.l"), function=type("em.h");
        Method render=type("com.google.android.gms.internal.mlkit_vision_barcode.tb").getMethod("a",stateType,type("em.f"),type("em.a"),type("em.a"),type("androidx.compose.ui.n"),String.class,composer,int.class,int.class);
        Object content=proxy("em.h",args->{render.invoke(null,state,click,settings,expiration,null,null,args[0],0,48);return unit();});
        return themedHost(content);
    }
    View themedHost(Object content) throws Exception {
        owners();
        Class<?> composer=type("androidx.compose.runtime.l");
        Class<?> lambda=type("androidx.compose.runtime.internal.a");
        Object menuContent=lambda.getConstructor(int.class,Object.class,boolean.class).newInstance(1927401,content,true);
        Class<?> colors=type("ob.b");
        // MSS names the event theme by its foreground: Dark provides dark ink on light surfaces.
        Object darkInk=type("com.eventicious.Theme").getMethod("valueOf",String.class).invoke(null,"Dark");
        Method color=type("androidx.compose.ui.graphics.c0").getMethod("d",long.class);
        Object palette=colors.getConstructor(type("com.eventicious.Theme"),long.class,long.class).newInstance(darkInk,color.invoke(null,0xff252650L),color.invoke(null,(long)activity.bg & 0xffffffffL));
        Method theme=type("ob.c").getMethod("a",colors,colors,lambda,composer,int.class);
        Object themed=proxy("em.h",args->{theme.invoke(null,palette,palette,menuContent,args[0],0);return unit();});
        // MSS also retains this simple Compose host; it needs no production screen services.
        Class<?> host=type("androidx.compose.material3.z2");
        View view=(View)host.getConstructor(android.content.Context.class).newInstance(activity);
        // Supply the light Flow backdrop for all retained screens hosted here.
        view.setBackgroundColor(activity.bg);
        activity.getWindow().setStatusBarColor(0xffd7d7e3);
        activity.getWindow().setNavigationBarColor(activity.bg);
        activity.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        Object mutable=field(view,"i");
        type("androidx.compose.runtime.x0").getMethod("setValue",Object.class).invoke(mutable,themed);
        host.getField("j").setBoolean(view,true);
        return view;
    }
}

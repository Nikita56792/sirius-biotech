package org.sirius.biotech;

import android.content.*;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;

/** Shared binding helpers; layout, drawables, typography and widgets come from MSS. */
final class OriginalUi {
    interface Call { Object invoke(Object[] args)throws Exception; }
    static Class<?> cls(String name)throws ClassNotFoundException{return Class.forName(name);}
    static Object make(String name,Class<?>[] args,Object...values)throws Exception{return cls(name).getConstructor(args).newInstance(values);}
    static Object call(Object target,String name,Class<?>[] args,Object...values)throws Exception{return target.getClass().getMethod(name,args).invoke(target,values);}
    static Object proxy(String name,Call work)throws Exception{return Proxy.newProxyInstance(OriginalUi.class.getClassLoader(),new Class<?>[]{cls(name)},(p,m,a)->{if(m.getDeclaringClass()==Object.class){if(m.getName().equals("hashCode"))return System.identityHashCode(p);if(m.getName().equals("equals"))return p==a[0];return "SbsOriginalBinding";}return work.invoke(a);});}
    static Object unit()throws Exception{return cls("rl.q").getField("a").get(null);}
    static int id(Context c,String name,String type){int id=c.getResources().getIdentifier(name,type,c.getPackageName());if(id==0)throw new IllegalArgumentException(type+"/"+name);return id;}
    static View view(View root,String name){View v=root.findViewById(id(root.getContext(),name,"id"));if(v==null)throw new IllegalStateException("Missing MSS child "+name);return v;}
    static void visible(View root,String name,boolean shown){View v=view(root,name);v.setVisibility(shown?View.VISIBLE:View.GONE);v.setAlpha(1);}
    static Context theme(MainActivity a){return new ScreenContext(a,id(a,"Theme.Eventicious.App","style"));}
    static final class ScreenContext extends ContextThemeWrapper{
        LayoutInflater inflater;
        ScreenContext(Context base,int theme){super(base,theme);}
        public Object getSystemService(String name){if(Context.LAYOUT_INFLATER_SERVICE.equals(name)){if(inflater==null)inflater=configured(((LayoutInflater)super.getSystemService(name)).cloneInContext(this));return inflater;}return super.getSystemService(name);}
    }
    static ViewGroup constraint(Context c)throws Exception{return (ViewGroup)make("androidx.constraintlayout.widget.ConstraintLayout",new Class<?>[]{Context.class,AttributeSet.class},c,null);}
    static void inflate(Context c,String layout,ViewGroup parent)throws Exception{inflater(c).inflate(id(c,layout,"layout"),parent,true);}
    static View inflate(Context c,String layout,ViewGroup parent,boolean attach)throws Exception{return inflater(c).inflate(id(c,layout,"layout"),parent,attach);}
    static LayoutInflater inflater(Context c){
        LayoutInflater inflater=LayoutInflater.from(c);return inflater.getFactory2()==null?configured(inflater.cloneInContext(c)):inflater;
    }
    static LayoutInflater configured(LayoutInflater inflater){
        inflater.setFactory2(new LayoutInflater.Factory2(){
            public View onCreateView(String name,Context context,AttributeSet attrs){return onCreateView(null,name,context,attrs);}
            public View onCreateView(View parent,String name,Context context,AttributeSet attrs){
                try{
                    // Only the original Flow/Koin lifecycle wrappers are replaced; inflate their exact XML.
                    if(name.equals("com.eventicious.ui.schedule.tags.button.ScheduleFilterView")){
                        ViewGroup result=(ViewGroup)make("androidx.constraintlayout.widget.ConstraintLayout",new Class<?>[]{Context.class,AttributeSet.class},context,attrs);inflate(context,"schedule_filter_view",result);return result;
                    }
                    if(name.equals("com.eventicious.chats.ui.chat.messages.ChatMessagesView")){
                        FrameLayout result=new FrameLayout(context,attrs);inflate(context,"chat_messages_view",result);return result;
                    }
                    if(name.equals("com.eventicious.chats.ui.chat.messages.invitation.ChatInvitationView")){
                        FrameLayout result=new FrameLayout(context,attrs);result.setVisibility(View.GONE);return result;
                    }
                    if(name.equals("com.eventicious.pager.ViewShifter"))return new FrameLayout(context,attrs);
                    View result;
                    if(name.equals("com.eventicious.toolbar.Toolbar")){
                        result=(View)make(name,new Class<?>[]{Context.class,AttributeSet.class},context,null);
                        int identifier=attrs.getAttributeResourceValue("http://schemas.android.com/apk/res/android","id",-1);if(identifier!=-1)result.setId(identifier);
                    }
                    else if(name.equals("Button"))result=(View)make("com.google.android.material.button.MaterialButton",new Class<?>[]{Context.class,AttributeSet.class},context,attrs);
                    else if(name.contains("."))result=(View)make(name,new Class<?>[]{Context.class,AttributeSet.class},context,attrs);
                    else result=(View)make(name.equals("View")||name.equals("ViewStub")?"android.view."+name:"android.widget."+name,new Class<?>[]{Context.class,AttributeSet.class},context,attrs);
                    if(result instanceof ImageView){int src=context.getResources().getIdentifier("srcCompat","attr",context.getPackageName()),tint=context.getResources().getIdentifier("tint","attr",context.getPackageName());android.content.res.TypedArray image=context.obtainStyledAttributes(attrs,new int[]{src,tint});Drawable drawable=image.getDrawable(0);if(drawable!=null)((ImageView)result).setImageDrawable(drawable);android.content.res.ColorStateList color=image.getColorStateList(1);if(color!=null)((ImageView)result).setImageTintList(color);image.recycle();}
                    if(result instanceof TextView){
                        String[] names={"drawableStartCompat","drawableTopCompat","drawableEndCompat","drawableBottomCompat","drawableTint","lineHeight"};int[] attributes=new int[names.length];for(int n=0;n<names.length;n++)attributes[n]=id(context,names[n],"attr");
                        android.content.res.TypedArray tv=context.obtainStyledAttributes(attrs,attributes);Drawable[] icons=new Drawable[4];boolean supplied=false;
                        for(int n=0;n<4;n++){icons[n]=tv.getDrawable(n);supplied|=icons[n]!=null;}
                        if(supplied)((TextView)result).setCompoundDrawablesRelativeWithIntrinsicBounds(icons[0],icons[1],icons[2],icons[3]);
                        android.content.res.ColorStateList color=tv.getColorStateList(4);if(color!=null)((TextView)result).setCompoundDrawableTintList(color);
                        if(tv.hasValue(5))((TextView)result).setLineHeight(tv.getDimensionPixelSize(5,0));tv.recycle();
                    }
                    int radiusId=id(context,"cornerRadius","attr");android.content.res.TypedArray values=context.obtainStyledAttributes(attrs,new int[]{radiusId});float radius=values.getDimension(0,0);values.recycle();
                    android.content.res.TypedArray dynamic=context.obtainStyledAttributes(attrs,new int[]{id(context,"dynamicBackground","attr")});int mode=dynamic.getInt(0,0);dynamic.recycle();if(mode==1||mode==3)result.setBackgroundColor(0xff252650);
                    if(radius>0){result.setClipToOutline(true);result.setOutlineProvider(new ViewOutlineProvider(){public void getOutline(View view,Outline outline){outline.setRoundRect(0,0,view.getWidth(),view.getHeight(),radius);}});}
                    return result;
                }catch(ReflectiveOperationException error){throw new android.view.InflateException("MSS inflation failed: "+name,error);}catch(Exception error){throw new IllegalStateException(error);}
            }
        });return inflater;
    }
    static LinearLayout scene(MainActivity a,String layout)throws Exception{Context c=theme(a);LinearLayout root=new LinearLayout(c);root.setOrientation(1);root.setBackgroundColor(a.bg);insets(root);inflate(c,layout,root);View toolbar=findToolbar(root);if(toolbar!=null&&toolbar.getLayoutParams().width==0){ViewGroup.LayoutParams params=toolbar.getLayoutParams();params.width=-1;toolbar.setLayoutParams(params);}return root;}
    static void toolbar(MainActivity a,View toolbar,String title,Runnable back,String menu,java.util.function.Consumer<String> click)throws Exception{
        call(toolbar,"setTitle",new Class<?>[]{CharSequence.class},a.tr(title));call(toolbar,"setNavigationIcon",new Class<?>[]{int.class},id(a,"icon_back_24","drawable"));
        call(toolbar,"setNavigationOnClickListener",new Class<?>[]{View.OnClickListener.class},(View.OnClickListener)v->back.run());
        if(menu!=null){call(toolbar,"m",new Class<?>[]{int.class},id(a,menu,"menu"));Object listener=proxy("o.v2",args->{MenuItem item=(MenuItem)args[0];click.accept(a.getResources().getResourceEntryName(item.getItemId()));return true;});call(toolbar,"setOnMenuItemClickListener",new Class<?>[]{cls("o.v2")},listener);}
        toolbar.setAlpha(1);toolbar.post(()->{tintIcons(toolbar,a.ink);try{Menu actions=(Menu)call(toolbar,"getMenu",new Class<?>[]{});for(int n=0;n<actions.size();n++){MenuItem item=actions.getItem(n);if(item.getActionView()!=null)item.getActionView().setContentDescription(item.getTitle());}}catch(Exception ignored){}});

    }
    static void insets(View root){root.setOnApplyWindowInsetsListener((v,insets)->{int bottom=android.os.Build.VERSION.SDK_INT>=30?insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom:insets.getStableInsetBottom();v.setPadding(v.getPaddingLeft(),v.getPaddingTop(),v.getPaddingRight(),bottom);return insets;});root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewDetachedFromWindow(View v){}public void onViewAttachedToWindow(View v){v.post(()->{WindowInsets i=v.getRootWindowInsets();if(i!=null){int bottom=android.os.Build.VERSION.SDK_INT>=30?i.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom:i.getStableInsetBottom();v.setPadding(v.getPaddingLeft(),v.getPaddingTop(),v.getPaddingRight(),bottom);}});}});root.requestApplyInsets();}
    static void tintIcons(View view,int color){if(view instanceof ImageView)((ImageView)view).setImageTintList(android.content.res.ColorStateList.valueOf(color));if(view instanceof ViewGroup)for(int n=0;n<((ViewGroup)view).getChildCount();n++)tintIcons(((ViewGroup)view).getChildAt(n),color);}
    static View findToolbar(View root){if(root.getClass().getName().equals("com.eventicious.toolbar.Toolbar"))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View result=findToolbar(((ViewGroup)root).getChildAt(i));if(result!=null)return result;}return null;}
    static void list(View list,int orientation,List<OriginalRecycler.Row> rows)throws Exception{
        Object manager=make("androidx.recyclerview.widget.LinearLayoutManager",new Class<?>[]{int.class,boolean.class},orientation,false);
        call(list,"setLayoutManager",new Class<?>[]{cls("b8.h1")},manager);setRows(list,rows);list.setAlpha(1);
    }
    static void setRows(View list,List<OriginalRecycler.Row> rows)throws Exception{call(list,"setAdapter",new Class<?>[]{cls("b8.x0")},new OriginalRecycler(rows));}
    static void replaceRows(View list,List<OriginalRecycler.Row> rows)throws Exception{Object manager=call(list,"getLayoutManager",new Class<?>[]{});android.os.Parcelable state=manager==null?null:(android.os.Parcelable)call(manager,"i0",new Class<?>[]{});setRows(list,rows);if(state!=null)call(manager,"h0",new Class<?>[]{android.os.Parcelable.class},state);}
    static Drawable drawable(Context c,String name){Drawable d=c.getDrawable(id(c,name,"drawable")).mutate();d.setBounds(0,0,d.getIntrinsicWidth(),d.getIntrinsicHeight());return d;}
    static void text(View root,String name,CharSequence value){((TextView)view(root,name)).setText(value);}
    static View pager(Context c,View tabs,List<View> pages,String[] titles)throws Exception{
        View pager=(View)make("com.eventicious.widget.ViewPager",new Class<?>[]{Context.class,AttributeSet.class},c,null);bindPager(pager,tabs,pages,titles);return pager;
    }
    static void bindPager(View pager,View tabs,List<View> pages,String[] titles)throws Exception{
        Object adapter=make("com.eventicious.pager.g",new Class<?>[]{cls("com.eventicious.widget.ViewPager"),cls("com.eventicious.flow.i")},pager,null);List<Object> items=new ArrayList<>();
        for(View page:pages)items.add(java.lang.reflect.Proxy.newProxyInstance(OriginalUi.class.getClassLoader(),new Class<?>[]{cls("com.eventicious.pager.o")},(p,m,args)->{if(m.getName().equals("b"))return page;if(m.getName().equals("equals"))return p==args[0];if(m.getName().equals("hashCode"))return System.identityHashCode(p);return null;}));
        call(adapter,"b",new Class<?>[]{List.class},items);call(tabs,"setupWithViewPager",new Class<?>[]{cls("u8.h")},pager);tabs.setAlpha(1);tabs.setVisibility(View.VISIBLE);
        for(int n=0;n<titles.length;n++){Object tab=call(tabs,"h",new Class<?>[]{int.class},n);call(tab,"d",new Class<?>[]{CharSequence.class},titles[n]);}
    }
    static String label(MainActivity a,String ru,String en){return "ru".equals(a.store.getString("appLanguage","ru"))?ru:en;}
    static TextView labelView(Context c,String value,int size,int color){TextView v=new TextView(c);v.setText(value);v.setTextSize(size);v.setTextColor(color);return v;}
    static View field(Context c,ViewGroup parent,String name,String value,boolean link)throws Exception{View row=inflate(c,link?"attendee_field_link_view":"attendee_field_view",parent,false);((TextView)row.findViewById(android.R.id.text1)).setText(name);((TextView)row.findViewById(android.R.id.text2)).setText(value);return row;}
    static void backdrop(MainActivity a){a.getWindow().setStatusBarColor(0xffd7d7e3);a.getWindow().setNavigationBarColor(a.bg);a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);}
    static void failed(MainActivity a,Throwable error){android.util.Log.e("SiriusOriginalUI","MSS scene binding",error);a.toast("Unable to open this page. Please try again.");}
}

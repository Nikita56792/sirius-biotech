package org.sirius.biotech;

import android.content.Context;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.time.*;
import java.time.format.*;
import org.sirius.biotech.data.EventRepository;
import static org.sirius.biotech.OriginalUi.*;

/** Retained MSS pager, schedule XML, day/location controls, filters, search and saved list. */
public final class OriginalSchedule {
    final MainActivity a;final Context c;final boolean only;final FrameLayout root;final ViewGroup page;final View list;
    final List<String> dates;String selectedDay,room="",search="";final Set<String> tags=new HashSet<>();boolean pending;
    static View create(MainActivity a,boolean only)throws Exception{return new OriginalSchedule(a,only).root;}
    OriginalSchedule(MainActivity activity,boolean favorites)throws Exception{
        a=activity;only=favorites;c=theme(a);dates=a.sessionDates();selectedDay=a.store.getString("schedule-day",dates.isEmpty()?"":dates.get(0));if(!dates.contains(selectedDay))selectedDay=dates.isEmpty()?"":dates.get(0);room=a.store.getString("schedule-room","");
        root=new FrameLayout(c);root.setBackgroundColor(a.bg);insets(root);inflate(c,"pager_view",root);
        if(only){page=new FrameLayout(c);inflate(c,"favorite_schedule_view",page);list=view(page,"sessionRecyclerView");}
        else{page=constraint(c);inflate(c,"regular_schedule_view",page);list=view(page,"sessionsRecyclerView");}
        View toolbar=view(root,"toolbar"),pager=view(root,"viewPager"),tabs=view(root,"tabLayout");
        toolbar(a,toolbar,"",()->a.show("home"),"search",id->{try{searchScene();}catch(Exception error){failed(a,error);}});
        Object adapter=make("com.eventicious.pager.g",new Class<?>[]{cls("com.eventicious.widget.ViewPager"),cls("com.eventicious.flow.i")},pager,null);
        Object tab=proxy("com.eventicious.pager.o",args->null);
        tab=java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{cls("com.eventicious.pager.o")},(p,m,args)->{
            if(m.getName().equals("b"))return page;
            if(m.getName().equals("equals"))return p==args[0];if(m.getName().equals("hashCode"))return 1;if(m.getName().equals("toString"))return "SBS schedule tab";
            return null;
        });
        call(adapter,"b",new Class<?>[]{List.class},Collections.singletonList(tab));
        call(tabs,"setupWithViewPager",new Class<?>[]{cls("u8.h")},pager);
        Object label=call(tabs,"h",new Class<?>[]{int.class},0);if(label!=null)call(label,"d",new Class<?>[]{CharSequence.class},a.tr(only?"My schedule":"Programme"));
        if(!only){controls();View filter=view(page,"filterButton");filter.setAlpha(1);filter.setVisibility(View.VISIBLE);TextView button=(TextView)view(filter,"button");button.setText(a.getString(id(c,"schedule_filters_all","string")));button.setOnClickListener(v->{try{filters();}catch(Exception error){failed(a,error);}});}
        visible(page,"progressView",false);list(list,1,Collections.emptyList());render();
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewDetachedFromWindow(View v){}public void onViewAttachedToWindow(View v){final int owner=a.accountGeneration;a.network.execute(()->{try{org.json.JSONArray favorites=a.api.request("GET","/favorites",null).getJSONArray("items");a.runOnUiThread(()->{if(owner==a.accountGeneration&&root.isAttachedToWindow()){a.serverFavorites=favorites;safeRender();}});}catch(Exception error){android.util.Log.w("SiriusSchedule","Favorites refresh unavailable",error);}});}});
    }
    void controls()throws Exception{
        List<String> rooms=new ArrayList<>();for(EventRepository.Session s:a.sessions)if(!s.room.isEmpty()&&!rooms.contains(s.room))rooms.add(s.room);Collections.sort(rooms);if(!rooms.contains(room))room=rooms.isEmpty()?"":rooms.get(0);
        List<OriginalRecycler.Row> rr=new ArrayList<>();for(String value:rooms)rr.add(parent->{View row=(View)make("com.eventicious.ui.schedule.regular.sessions.r",new Class<?>[]{Context.class},c);call(row,"setLocationName",new Class<?>[]{String.class},value);row.setSelected(value.equals(room));row.setOnClickListener(v->{room=value;a.store.edit().putString("schedule-room",room).apply();safeRender();try{controls();}catch(Exception error){failed(a,error);}});return row;});
        list(view(page,"locationsRecyclerView"),0,rr);visible(page,"locationsRecyclerView",rooms.size()>1);
        List<OriginalRecycler.Row> dd=new ArrayList<>();for(String date:dates)dd.add(parent->{View row=(View)make("com.eventicious.ui.schedule.regular.sessions.n",new Class<?>[]{Context.class},c);LocalDate day=LocalDate.parse(date);row.setLayoutParams(new ViewGroup.LayoutParams(Math.round(c.getResources().getDisplayMetrics().widthPixels/(dates.size()<=5?(float)Math.max(1,dates.size()):4.5f)),-2));row.setSelected(date.equals(selectedDay));TextView digit=(TextView)view(row,"dayDigitView");digit.setBackground(a.shape(date.equals(selectedDay)?a.accent:0x00000000,12));digit.setTextColor(date.equals(selectedDay)?android.graphics.Color.WHITE:a.ink);text(row,"dayDigitView",String.valueOf(day.getDayOfMonth()));text(row,"dayNameView",day.format(DateTimeFormatter.ofPattern(dates.size()>4?"EEE":"EEEE",Locale.getDefault())).toUpperCase(Locale.getDefault()));row.setContentDescription(day.format(DateTimeFormatter.ofPattern("d MMMM",Locale.getDefault())));row.setOnClickListener(v->{selectedDay=date;a.store.edit().putString("schedule-day",date).apply();safeRender();try{controls();}catch(Exception error){failed(a,error);}});return row;});
        View days=view(page,"daysRecyclerView");list(days,0,dd);visible(page,"daysRecyclerView",dates.size()>1);
    }
    boolean matches(EventRepository.Session s,boolean searching){return (!only||a.isFavorite("sessions",s.id))&&(searching||s.time.startsWith(selectedDay))&&(searching||only||room.isEmpty()||s.room.equals(room))&&(tags.isEmpty()||tags.contains(s.track))&&(search.isEmpty()||(s.title+" "+s.room+" "+s.track+" "+s.speaker+" "+s.description).toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)));}
    void safeRender(){try{render();}catch(Exception error){failed(a,error);}}
    void render()throws Exception{renderList(list,false);}
    void renderList(View target,boolean searching)throws Exception{
        List<Integer> indices=new ArrayList<>();for(int i=0;i<a.sessions.size();i++)if(matches(a.sessions.get(i),searching))indices.add(i);indices.sort(Comparator.comparing(i->a.sessions.get(i).time));
        List<OriginalRecycler.Row> rows=new ArrayList<>();List<Object> models=new ArrayList<>();
        for(int i:indices){models.add(model(i));rows.add(parent->sessionRow(parent,i));}
        setRows(target,rows);decorate(target,models,indices);if(!searching){visible(page,only?"emptyView":"placeholder",rows.isEmpty());visible(page,only?"sessionRecyclerView":"sessionsRecyclerView",!rows.isEmpty());}
    }
    Object model(int i)throws Exception{EventRepository.Session s=a.sessions.get(i);return make("ze.h",new Class<?>[]{long.class,long.class,String.class,Date.class,Date.class,boolean.class,boolean.class,CharSequence.class,List.class,ArrayList.class,boolean.class},(long)i,(long)i,s.title,instant(s,false),instant(s,true),a.saved(i),false,s.room,Collections.emptyList(),new ArrayList<>(),false);
    }
    void decorate(View target,List<Object> models,List<Integer> indices)throws Exception{
        cls("com.google.android.gms.internal.mlkit_vision_common.w6").getMethod("a",cls("androidx.recyclerview.widget.RecyclerView")).invoke(null,target);
        Class<?> theme=cls("com.eventicious.Theme");Object dark=theme.getMethod("valueOf",String.class).invoke(null,"Dark");
        Object headers=cls("com.google.android.gms.internal.mlkit_vision_barcode.pc").getMethod("a",List.class,Context.class,theme).invoke(null,models,c,dark);
        LinkedHashMap<Integer,android.text.StaticLayout> days=new LinkedHashMap<>();
        if(only){Object paintData=make("androidx.compose.foundation.lazy.layout.h1",new Class<?>[]{Context.class,theme},c,dark);android.text.TextPaint paint=(android.text.TextPaint)paintData.getClass().getField("c").get(paintData);int width=paintData.getClass().getField("b").getInt(paintData);String previous="";for(int n=0;n<indices.size();n++){String date=date(a.sessions.get(indices.get(n)));if(date.equals(previous))continue;previous=date;String text=LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEEE, d MMMM",Locale.getDefault())).toUpperCase(Locale.getDefault());days.put(n,android.text.StaticLayout.Builder.obtain(text,0,text.length(),paint,width).setMaxLines(1).build());}}
        Object spacing=make("com.eventicious.ui.recycler.b",new Class<?>[]{Context.class,int.class},c,headers.getClass().getField("a").getInt(headers));call(target,"i",new Class<?>[]{cls("b8.f1")},spacing);
        Object sticky=make("com.eventicious.ui.schedule.favorites.g",new Class<?>[]{Context.class,theme,LinkedHashMap.class,cls("ze.a")},c,dark,days,headers);call(target,"i",new Class<?>[]{cls("b8.f1")},sticky);
    }
    View sessionRow(ViewGroup parent,int i)throws Exception{
        EventRepository.Session s=a.sessions.get(i);View row=inflate(c,"regular_schedule_report_view",parent,false);
        Object model=model(i);
        call(row,"setItem",new Class<?>[]{cls("ze.h")},model);
        View speakers=view(row,"speakersView");if(!s.speaker.isEmpty()){speakers.setVisibility(View.VISIBLE);visible(speakers,"photoView",false);visible(speakers,"iconView",false);visible(speakers,"speakerView",false);visible(speakers,"companyView",false);visible(speakers,"nameView",true);text(speakers,"nameView",s.speaker);}
        call(row,"setFavoriteAction",new Class<?>[]{cls("em.h")},proxy("em.h",args->{CompoundButton b=(CompoundButton)view(row,"favoriteButton");if(pending){b.setChecked(a.saved(i));return unit();}pending=true;b.setEnabled(false);final int owner=a.accountGeneration;final boolean wanted=(Boolean)args[1];a.network.execute(()->{Exception failure=null;try{a.setFavoriteLive("sessions",s.id,wanted);}catch(Exception error){failure=error;}final Exception problem=failure;a.runOnUiThread(()->{pending=false;if(owner!=a.accountGeneration)return;b.setEnabled(true);if(problem!=null){b.setChecked(a.saved(i));a.toast("Could not save changes. Please retry.");}else if(only)safeRender();});});return unit();}));
        row.setOnClickListener(v->{a.scheduleReturn=only?"My schedule":"Programme";a.session(i);});return row;
    }
    static String date(EventRepository.Session s){return s.time.length()>=10?s.time.substring(0,10):"";}
    static String clock(EventRepository.Session s,boolean end){java.util.regex.Matcher m=java.util.regex.Pattern.compile("(\\d{2}:\\d{2})").matcher(s.time);String start=m.find()?m.group(1):"00:00";return end&&m.find()?m.group(1):start;}
    static Date instant(EventRepository.Session s,boolean end){try{return Date.from(LocalDateTime.parse(date(s)+"T"+clock(s,end)).toInstant(ZoneOffset.UTC));}catch(Exception error){return new Date(0);}}
    void searchScene()throws Exception{
        LinearLayout scene=new LinearLayout(c);scene.setOrientation(1);scene.setBackgroundColor(a.bg);View toolbar=(View)make("com.eventicious.toolbar.Toolbar",new Class<?>[]{Context.class,android.util.AttributeSet.class},c,null);scene.addView(toolbar);toolbar(a,toolbar,"Search",()->a.programme(only),null,null);
        FrameLayout content=new FrameLayout(c);inflate(c,"search_schedule_view",content);scene.addView(content,new LinearLayout.LayoutParams(-1,0,1));View input=view(content,"inputView"),results=view(content,"sessionRecyclerView");list(results,1,Collections.emptyList());
        EditText edit=(EditText)view(input,"searchInputView");edit.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void afterTextChanged(android.text.Editable e){}public void onTextChanged(CharSequence s,int st,int before,int count){search=s.toString();try{renderList(results,true);visible(content,"emptyView",search.trim().isEmpty());}catch(Exception error){failed(a,error);}}});
        a.backTarget=only?"My schedule":"Programme";a.sceneBack=()->a.programme(only);a.setContentView(scene);edit.requestFocus();((android.view.inputmethod.InputMethodManager)a.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(edit,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }
    void filters()throws Exception{
        Object dialog=make("com.google.android.material.bottomsheet.o",new Class<?>[]{Context.class},c);ViewGroup content=constraint(c);inflate(c,"tags_selection_view",content);content.setBackgroundColor(a.bg);List<String> values=new ArrayList<>();for(EventRepository.Session s:a.sessions)if(!values.contains(s.track))values.add(s.track);
        Set<String> next=new HashSet<>(tags);List<OriginalRecycler.Row> rows=new ArrayList<>();rows.add(parent->{View header=inflate(c,"filters_header_item_view",parent,false);text(header,"descriptionView",a.getString(id(c,"schedule_filters_header_description","string")));return header;});for(String tag:values)rows.add(parent->{ViewGroup row=constraint(c);inflate(c,"tag_selection_item_view",row);text(row,"nameView",tag.isEmpty()?a.getString(id(c,"schedule_filters_empty","string")):tag);((ImageView)view(row,"colorView")).setImageTintList(android.content.res.ColorStateList.valueOf(a.accent));View selected=view(row,"selectedView");selected.setVisibility(next.contains(tag)?View.VISIBLE:View.INVISIBLE);row.setOnClickListener(v->{if(!next.add(tag))next.remove(tag);selected.setVisibility(next.contains(tag)?View.VISIBLE:View.INVISIBLE);});return row;});
        list(view(content,"recyclerView"),1,rows);visible(content,"emptyView",false);TextView confirm=(TextView)view(content,"confirmButton");confirm.setText(a.getString(id(c,"schedule_filters_confirm","string")));confirm.setAlpha(1);confirm.setOnClickListener(v->{tags.clear();tags.addAll(next);try{call(dialog,"dismiss",new Class<?>[]{});((TextView)view(view(page,"filterButton"),"button")).setText(tags.isEmpty()?a.getString(id(c,"schedule_filters_all","string")):a.getString(id(c,"schedule_filters_button_label","string"),tags.size()));render();}catch(Exception error){failed(a,error);}});
        TextView clear=(TextView)view(content,"actionView");clear.setText(a.tr("Clear filters"));clear.setAlpha(1);clear.setOnClickListener(v->{next.clear();tags.clear();try{call(dialog,"dismiss",new Class<?>[]{});render();}catch(Exception error){failed(a,error);}});
        call(dialog,"setContentView",new Class<?>[]{View.class,ViewGroup.LayoutParams.class},content,new ViewGroup.LayoutParams(-1,Math.min(a.dp(520),c.getResources().getDisplayMetrics().heightPixels-a.dp(100))));call(dialog,"show",new Class<?>[]{});Object behavior=call(dialog,"g",new Class<?>[]{});call(behavior,"setState",new Class<?>[]{int.class},3);
    }
}

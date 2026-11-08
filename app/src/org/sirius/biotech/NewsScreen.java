package org.sirius.biotech;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.*;
import org.sirius.biotech.data.EventRepository.Post;

/** Same news layout and filters as web contentCard/listContent. */
final class NewsScreen {
    static View create(MainActivity a) { return create(a,a.repository.posts(),"posts"); }
    static View create(MainActivity a, java.util.List<Post> items, String kind) {
        LinearLayout page=a.column();page.setPadding(a.dp(10),0,a.dp(10),a.dp(20));
        LinearLayout filters=new LinearLayout(a);filters.setGravity(Gravity.CENTER_VERTICAL);
        EditText search=new EditText(a);search.setSingleLine();search.setTextSize(16);
        search.setTextColor(a.ink);search.setHint(a.tr("Search"));search.setPadding(a.dp(12),0,a.dp(12),0);
        search.setBackground(surface(a));
        LinearLayout.LayoutParams searchParams=new LinearLayout.LayoutParams(0,a.dp(48),1);filters.addView(search,searchParams);
        ArrayList<String> dates=new ArrayList<>();dates.add(a.tr("All dates"));
        TreeSet<String> unique=new TreeSet<>();for(Post p:items)if(!p.date.isEmpty())unique.add(p.date);dates.addAll(unique);
        TextView date=a.text("",16,a.ink);date.setPadding(a.dp(12),0,a.dp(14),0);date.setGravity(Gravity.CENTER_VERTICAL);date.setBackground(surface(a));
        android.graphics.drawable.Drawable chevron=new android.graphics.drawable.Drawable(){
            final android.graphics.Paint paint=new android.graphics.Paint(3);
            public void draw(android.graphics.Canvas canvas){paint.setColor(a.ink);paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(a.dp(1.5f));paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);android.graphics.Path path=new android.graphics.Path();android.graphics.Rect b=getBounds();path.moveTo(b.left+a.dp(3),b.top+a.dp(5));path.lineTo(b.left+a.dp(8),b.top+a.dp(10));path.lineTo(b.left+a.dp(13),b.top+a.dp(5));canvas.drawPath(path,paint);}
            public void setAlpha(int value){paint.setAlpha(value);}public void setColorFilter(android.graphics.ColorFilter value){paint.setColorFilter(value);}public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
        };chevron.setBounds(0,0,a.dp(16),a.dp(16));date.setCompoundDrawables(null,null,chevron,null);date.setCompoundDrawablePadding(a.dp(10));
        LinearLayout.LayoutParams dateParams=new LinearLayout.LayoutParams(a.dp(140),a.dp(48));dateParams.leftMargin=a.dp(8);filters.addView(date,dateParams);page.addView(filters);
        LinearLayout list=a.column();LinearLayout.LayoutParams rows=new LinearLayout.LayoutParams(-1,-2);rows.topMargin=a.dp(16);page.addView(list,rows);
        final String[] selected={""};
        Runnable render=()->{
            list.removeAllViews();String query=search.getText().toString().toLowerCase(Locale.ROOT);ArrayList<Post> posts=new ArrayList<>(items);posts.sort(Comparator.comparing(p->p.date));int count=0;
            for(Post post:posts){if(!selected[0].isEmpty()&&!selected[0].equals(post.date)||!(post.title+" "+post.body).toLowerCase(Locale.ROOT).contains(query))continue;
                LinearLayout card=a.column();card.setPadding(a.dp(18),a.dp(18),a.dp(18),a.dp(18));card.setBackground(surface(a));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.bottomMargin=a.dp(12);list.addView(card,cp);
                LinearLayout head=new LinearLayout(a);head.setGravity(Gravity.TOP);LinearLayout copy=a.column();head.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
                if(!post.date.isEmpty()){TextView meta=label(a,post.date,12,a.muted,false);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,-2);mp.bottomMargin=a.dp(7);copy.addView(meta,mp);}
                copy.addView(label(a,post.title,18,a.ink,true));
                if(!post.id.isEmpty()){TextView star=label(a,a.isFavorite(kind,post.id)?"★":"☆",26,0xff766392,false);star.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);star.setContentDescription(a.tr("Favorite"));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(a.dp(44),a.dp(44));sp.leftMargin=a.dp(12);head.addView(star,sp);star.setOnClickListener(v->{star.setEnabled(false);a.favorite(kind,post.id,!a.isFavorite(kind,post.id),()->{star.setText(a.isFavorite(kind,post.id)?"★":"☆");star.setEnabled(true);});});}
                card.addView(head);TextView description=label(a,post.body,16,a.ink,false);description.setLineSpacing(0,1.65f);LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(-1,-2);dp.topMargin=a.dp(12);dp.bottomMargin=a.dp(12);card.addView(description,dp);
                Uri url=Uri.parse(post.url);if(("https".equalsIgnoreCase(url.getScheme())||"http".equalsIgnoreCase(url.getScheme()))&&url.getHost()!=null){TextView link=label(a,"ru".equals(a.store.getString("appLanguage","ru"))?"Открыть сайт ↗":"Open website ↗",16,0xff51436f,false);link.setPaintFlags(link.getPaintFlags()|android.graphics.Paint.UNDERLINE_TEXT_FLAG);link.setMinHeight(a.dp(44));link.setGravity(Gravity.CENTER_VERTICAL);card.addView(link);link.setOnClickListener(v->{try{a.startActivity(new Intent(Intent.ACTION_VIEW,url));}catch(android.content.ActivityNotFoundException error){a.dialog("News",post.url);}});}
                count++;
            }
            if(count==0)list.addView(label(a,a.tr("No matching items"),16,a.muted,false));
        };
        date.setText(dates.get(0));date.setOnClickListener(v->new android.app.AlertDialog.Builder(a).setItems(dates.toArray(new String[0]),(dialog,index)->{selected[0]=index==0?"":dates.get(index);date.setText(dates.get(index));render.run();}).show());
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){render.run();}public void afterTextChanged(Editable e){}});render.run();return page;
    }
    private static TextView label(MainActivity a,String text,int size,int color,boolean bold){TextView t=new TextView(a);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private static GradientDrawable surface(MainActivity a){GradientDrawable d=a.shape(Color.WHITE,8);d.setStroke(a.dp(1),0xffe5e5eb);return d;}
}

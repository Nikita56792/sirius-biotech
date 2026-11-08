package org.sirius.biotech;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.Locale;
import org.sirius.biotech.data.EventRepository.Person;

/** Attendee cards match the web peopleView: portrait, name and organisation/position. */
final class PeopleScreen {
    static View create(MainActivity a) {
        LinearLayout page=a.column();page.setPadding(a.dp(10),0,a.dp(10),a.dp(20));
        EditText search=new EditText(a);search.setSingleLine();search.setTextSize(16);search.setTextColor(a.ink);
        search.setHint("ru".equals(a.store.getString("appLanguage","ru"))?"Имя, компания":"Name, company");
        search.setPadding(a.dp(12),0,a.dp(12),0);search.setBackground(surface(a));
        page.addView(search,new LinearLayout.LayoutParams(-1,a.dp(48)));
        LinearLayout list=a.column();LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=a.dp(16);page.addView(list,lp);
        Runnable render=()->{
            list.removeAllViews();String query=search.getText().toString().toLowerCase(Locale.ROOT);
            for(int i=0;i<a.participants.size();i++) {
                Person person=a.participants.get(i);final int index=i;
                if(!(person.name+" "+person.organization+" "+person.role).toLowerCase(Locale.ROOT).contains(query))continue;
                LinearLayout card=a.column();card.setPadding(a.dp(18),a.dp(18),a.dp(18),a.dp(18));card.setBackground(surface(a));
                LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.bottomMargin=a.dp(12);list.addView(card,cp);
                LinearLayout row=new LinearLayout(a);row.setGravity(Gravity.CENTER_VERTICAL);
                FrameLayout portrait=new FrameLayout(a);portrait.setBackground(a.shape(0xffe2ecf7,100));
                TextView initials=label(a,initials(person.name),16,0xff396bff,true);initials.setGravity(Gravity.CENTER);portrait.addView(initials,new FrameLayout.LayoutParams(-1,-1));
                ImageView photo=new ImageView(a);portrait.addView(photo,new FrameLayout.LayoutParams(-1,-1));
                if(!person.avatarUrl.isEmpty())ProfilePhotos.load(a,photo,person.avatarUrl,false);
                row.addView(portrait,new LinearLayout.LayoutParams(a.dp(44),a.dp(44)));
                TextView name=label(a,person.name,18,a.ink,true);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,-2,1);np.leftMargin=a.dp(12);row.addView(name,np);
                TextView star=label(a,a.isFavorite("people",person.id)?"★":"☆",26,0xff766392,false);star.setGravity(Gravity.CENTER);star.setContentDescription(a.tr("Favorite"));row.addView(star,new LinearLayout.LayoutParams(a.dp(44),a.dp(44)));
                star.setOnClickListener(v->a.favorite("people",person.id,!a.isFavorite("people",person.id),()->star.setText(a.isFavorite("people",person.id)?"★":"☆")));
                card.addView(row);String meta=person.organization+(person.organization.isEmpty()||person.role.isEmpty()?"":" · ")+person.role;
                if(!meta.isEmpty()){TextView details=label(a,meta,14,a.muted,false);details.setLineSpacing(0,1.5f);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,-2);mp.topMargin=a.dp(8);card.addView(details,mp);}
                card.setOnClickListener(v->a.person(index));
            }
            if(list.getChildCount()==0)list.addView(label(a,a.tr("No matching participants"),16,a.muted,false));
        };
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){render.run();}public void afterTextChanged(Editable e){}});
        render.run();return page;
    }
    static String initials(String name){StringBuilder out=new StringBuilder();for(String word:name.trim().split("\\s+")){if(!word.isEmpty())out.appendCodePoint(word.codePointAt(0));if(out.codePointCount(0,out.length())==2)break;}return out.toString().toUpperCase(Locale.ROOT);}
    static TextView label(MainActivity a,String value,int size,int color,boolean bold){TextView v=new TextView(a);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setIncludeFontPadding(false);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    static GradientDrawable surface(MainActivity a){GradientDrawable d=a.shape(Color.WHITE,8);d.setStroke(a.dp(1),0xffe5e5eb);return d;}
}

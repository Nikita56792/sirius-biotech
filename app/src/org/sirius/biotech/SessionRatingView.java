package org.sirius.biotech;
import android.content.Context;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.*;
import static org.sirius.biotech.OriginalUi.*;

/** Exact MSS rating rows and the original poll/rating/completion XML, bound to SBS rating API. */
final class SessionRatingView {
    static View create(MainActivity a,String sessionId){return create(a,sessionId,()->a.show(a.scheduleReturn));}
    static View create(MainActivity a,String sessionId,Runnable back){
        Context c=theme(a);LinearLayout host=new LinearLayout(c);host.setOrientation(1);final int[] confirmed={0};final boolean[] ready={false};String path="/sessions/"+android.net.Uri.encode(sessionId)+"/rating";final Runnable[] render={null};
        render[0]=()->{try{host.removeAllViews();View row=inflate(c,confirmed[0]>0?"session_rating_rated_view":"session_rating_not_rated_view",host,false);host.addView(row);row.setEnabled(ready[0]);row.setContentDescription(a.getString(id(c,confirmed[0]>0?"session_rating_rated":"session_rating_not_rated","string")));row.setOnClickListener(v->open(a,sessionId,confirmed[0],back));}catch(Exception error){failed(a,error);}};render[0].run();final int owner=a.accountGeneration;
        a.network.execute(()->{try{JSONObject cached=a.api.cachedResponse(path);if(cached!=null)a.runOnUiThread(()->{if(owner==a.accountGeneration){confirmed[0]=cached.optInt("value",0);ready[0]=true;render[0].run();}});JSONObject result=a.api.request("GET",path,null);a.runOnUiThread(()->{if(owner==a.accountGeneration){confirmed[0]=result.optInt("value",0);ready[0]=true;render[0].run();}});}catch(Exception error){a.runOnUiThread(()->{if(owner==a.accountGeneration){ready[0]=true;render[0].run();}});}});return host;
    }
    static void open(MainActivity a,String sessionId,int initial,Runnable back){try{
        Context c=theme(a);LinearLayout root=scene(a,"poll_view");toolbar(a,view(root,"toolbar"),a.getString(id(c,"session_rating_not_rated","string")),back,null,null);visible(root,"screenView",true);visible(root,"completion",false);root.findViewById(android.R.id.progress).setVisibility(View.GONE);
        final RatingBar[] choice={null};List<OriginalRecycler.Row> rows=new ArrayList<>();rows.add(parent->{TextView title=(TextView)inflate(c,"poll_question_group_header",parent,false);title.setText(a.getString(id(c,"session_rating_not_rated","string")));title.setPadding(a.dp(16),a.dp(16),a.dp(16),a.dp(16));return title;});rows.add(parent->{View row=inflate(c,"poll_question_rating",parent,false);choice[0]=(RatingBar)view(row,"rating");choice[0].setNumStars(5);choice[0].setMax(5);choice[0].setStepSize(1);choice[0].setRating(initial);choice[0].setOnRatingBarChangeListener((bar,value,user)->view(root,"continueButton").setEnabled(value>=1));return row;});list(view(root,"questions"),1,rows);TextView submit=(TextView)view(root,"continueButton");submit.setText(label(a,"Отправить","Send"));submit.setEnabled(initial>0);
        submit.setOnClickListener(v->{if(choice[0]==null||choice[0].getRating()<1)return;final int value=Math.round(choice[0].getRating()),owner=a.accountGeneration;submit.setEnabled(false);choice[0].setEnabled(false);a.network.execute(()->{try{JSONObject accepted=a.api.request("PUT","/sessions/"+android.net.Uri.encode(sessionId)+"/rating",new JSONObject().put("value",value));int saved=accepted.getInt("value");a.runOnUiThread(()->{if(owner!=a.accountGeneration)return;visible(root,"screenView",false);View completion=view(root,"completion");completion.setVisibility(View.VISIBLE);completion.setAlpha(1);completion.findViewById(android.R.id.icon).setVisibility(View.VISIBLE);completion.findViewById(android.R.id.title).setVisibility(View.VISIBLE);((TextView)completion.findViewById(android.R.id.title)).setText(a.getString(id(c,"poll_completion_thank_you","string")));visible(completion,"score",false);visible(completion,"results",false);visible(completion,"retake",false);view(completion,"next").setOnClickListener(button->back.run());});}catch(Exception error){a.runOnUiThread(()->{if(owner!=a.accountGeneration)return;submit.setEnabled(true);choice[0].setEnabled(true);a.toast("Could not send your rating. Please retry.");});}});});a.sceneBack=back;a.setContentView(root);
    }catch(Exception e){failed(a,e);}}
}

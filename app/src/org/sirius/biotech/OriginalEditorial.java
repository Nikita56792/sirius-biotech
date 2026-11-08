package org.sirius.biotech;

import android.content.Context;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.sirius.biotech.data.EventRepository;
import static org.sirius.biotech.OriginalUi.*;

/** Real SBS news and partners bound to the retained MSS feed/catalog layouts. */
final class OriginalEditorial {
    private static final String[] UNUSED_POST_PARTS={
        "replyIndicator","replyIcon","replyImage","replyFile","replyAuthor","replyText","replyOverlay",
        "attachedImage","attachedFileBackground","attachedFileIcon","attachedFileProgress","attachedFileTitle","attachedFileSize",
        "linkPreviewIndicator","linkPreviewProvider","linkPreviewTitle","linkPreviewDescription","linkPreviewImage","linkPreviewImageOverlay","linkPreviewPlayButton","linkPreviewDurationView",
        "likeButton","replyButton","deleteButton"
    };
    private OriginalEditorial(){}
    static View news(MainActivity a)throws Exception{
        Context c=theme(a);FrameLayout root=new FrameLayout(c);root.setBackgroundColor(a.bg);insets(root);inflate(c,"feed",root);
        toolbar(a,view(root,"toolbar"),"News",()->a.show("home"),null,null);
        View background=view(root,"backgroundView");background.setBackgroundColor(a.bg);
        View progress=root.findViewById(android.R.id.progress);if(progress!=null)progress.setVisibility(View.GONE);
        ViewGroup content=(ViewGroup)view(root,"content");content.setVisibility(View.VISIBLE);
        FrameLayout page=new FrameLayout(c);inflate(c,"feed_posts_tab",page);content.addView(page,new FrameLayout.LayoutParams(-1,-1));
        View pageProgress=page.findViewById(android.R.id.progress);if(pageProgress!=null)pageProgress.setVisibility(View.GONE);
        List<OriginalRecycler.Row> rows=new ArrayList<>();for(EventRepository.Post post:a.repository.posts())rows.add(parent->post(a,c,parent,post));
        list(view(page,"postsView"),1,rows);
        View empty=page.findViewById(android.R.id.empty);if(empty!=null){empty.setVisibility(rows.isEmpty()?View.VISIBLE:View.GONE);TextView label=(TextView)empty.findViewById(id(c,"refreshText","id"));if(label!=null)label.setText(label(a,"Пока нет новостей","No news yet"));View refresh=empty.findViewById(id(c,"refreshButton","id"));if(refresh!=null)refresh.setVisibility(View.GONE);}
        a.sceneBack=()->a.show("home");return root;
    }
    private static View post(MainActivity a,Context c,ViewGroup parent,EventRepository.Post post)throws Exception{
        Object binding=make("ac.b",new Class<?>[]{ViewGroup.class,int.class},parent,0);
        View root=(View)call(binding,"n",new Class<?>[]{});
        text(root,"attendeeName",label(a,"Сириус Биотех 2027","Sirius Biotech 2027"));
        TextView date=(TextView)call(binding,"h",new Class<?>[]{});date.setText(post.date);date.setVisibility(post.date.isEmpty()?View.GONE:View.VISIBLE);
        ImageView avatar=(ImageView)view(root,"attendeeImage");try(java.io.InputStream image=a.getAssets().open("news-avatar.png")){avatar.setImageDrawable(android.graphics.drawable.Drawable.createFromStream(image,null));}
        for(String name:UNUSED_POST_PARTS)view(root,name).setVisibility(View.GONE);
        SpannableStringBuilder body=new SpannableStringBuilder();if(!post.title.isEmpty())body.append(post.title);if(!post.body.isEmpty()){if(body.length()>0)body.append("\n\n");body.append(post.body);}
        if(validUrl(post.url)){
            if(body.length()>0)body.append("\n\n");int start=body.length();body.append(label(a,"Открыть сайт ↗","Open website ↗"));
            body.setSpan(new ClickableSpan(){@Override public void onClick(View ignored){ContentActions.url(a,post.url);}},start,body.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        Object expandable=call(binding,"A",new Class<?>[]{});call(expandable,"setText",new Class<?>[]{CharSequence.class},body);
        TextView message=(TextView)view(root,"expandableText");message.setMovementMethod(LinkMovementMethod.getInstance());
        return root;
    }
    static View partners(MainActivity a)throws Exception{
        Context c=theme(a);LinearLayout root=scene(a,"catalog_view");toolbar(a,view(root,"toolbar"),"Partners",()->a.show("home"),null,null);
        View progress=root.findViewById(android.R.id.progress);if(progress!=null)progress.setVisibility(View.GONE);
        List<OriginalRecycler.Row> rows=new ArrayList<>();for(EventRepository.Partner partner:a.repository.partners())rows.add(parent->partner(a,c,parent,partner));
        list(root.findViewById(android.R.id.list),1,rows);visible(root,"emptyView",rows.isEmpty());
        a.sceneBack=()->a.show("home");return root;
    }
    private static View partner(MainActivity a,Context c,ViewGroup parent,EventRepository.Partner partner)throws Exception{
        View row=inflate(c,"catalog_item_text",parent,false);
        String description=partner.name+(partner.description.isEmpty()?"":"\n"+partner.description);
        ((TextView)row.findViewById(android.R.id.text1)).setText(description);
        View arrow=row.findViewById(android.R.id.icon);boolean link=validUrl(partner.url);arrow.setVisibility(link?View.VISIBLE:View.GONE);
        if(link){row.setOnClickListener(v->ContentActions.url(a,partner.url));row.setContentDescription(description+". "+label(a,"Открыть сайт","Open website"));}
        return row;
    }
    private static boolean validUrl(String value){try{Uri uri=Uri.parse(value);return uri.getHost()!=null&&("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme()));}catch(Exception ignored){return false;}}
}

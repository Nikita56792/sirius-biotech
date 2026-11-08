package org.sirius.biotech.data;

import org.json.*;
import java.util.*;

/** Authenticated server snapshot; failures never silently substitute demo content. */
public final class ServerRepository implements EventRepository {
    private final ApiClient api;
    private volatile JSONObject snapshot=new JSONObject();
    public JSONObject raw(String kind,String id){JSONArray items=snapshot.optJSONArray(kind);if(items!=null)for(int n=0;n<items.length();n++){JSONObject item=items.optJSONObject(n);if(item!=null&&id.equals(item.optString("id")))return item;}return new JSONObject();}
    private volatile List<Session> sessions=Collections.emptyList();
    private volatile List<Person> people=Collections.emptyList();
    private volatile List<Post> posts=Collections.emptyList();
    private volatile List<Partner> partners=Collections.emptyList();
    private volatile String status="Server connection required";
    public ServerRepository(ApiClient api){this.api=api;}
    public void refresh()throws Exception{
        apply(api.request("GET","/bootstrap",null));
    }
    public boolean loadCached()throws Exception{JSONObject root=api.cachedResponse("/bootstrap");if(root==null)return false;apply(root);return true;}
    private void apply(JSONObject root)throws Exception{
        ArrayList<Session> ss=new ArrayList<>();JSONArray a=root.optJSONArray("sessions");
        if(a!=null)for(int i=0;i<a.length();i++){JSONObject x=a.getJSONObject(i);ss.add(new Session(x.getString("id"),x.optString("title"),x.optString("time",x.optString("date")+" · "+x.optString("start_time")+"-"+x.optString("end_time")),x.optString("room"),trackTitle(x),x.optString("speaker"),x.optString("description")+(x.optString("source_url").isEmpty()?"":"\n"+x.optString("source_url"))));}
        ArrayList<Person> pp=new ArrayList<>();a=root.optJSONArray("people");if(a!=null)for(int i=0;i<a.length();i++){JSONObject x=a.getJSONObject(i);pp.add(new Person(x.getString("id"),x.optString("name"),x.optString("position",x.optString("role")),x.optString("company",x.optString("organization")),x.optString("avatar_url"),x.optBoolean("archive",false)));}
        ArrayList<Post> ns=new ArrayList<>();a=root.optJSONArray("posts");if(a!=null)for(int i=0;i<a.length();i++){JSONObject x=a.getJSONObject(i);ns.add(new Post(x.optString("id"),x.optString("title"),x.optString("body",x.optString("description")),x.optString("date"),x.optString("url")));}
        ArrayList<Partner> ps=new ArrayList<>();a=root.optJSONArray("partners");if(a!=null)for(int i=0;i<a.length();i++){JSONObject x=a.getJSONObject(i);ps.add(new Partner(x.optString("id"),x.optString("title",x.optString("name")),x.optString("description"),x.optString("date"),x.optString("url")));}
        snapshot=root; sessions=Collections.unmodifiableList(ss);people=Collections.unmodifiableList(pp);posts=Collections.unmodifiableList(ns);partners=Collections.unmodifiableList(ps);status=(api.lastRequestOffline()?"Offline - saved data: ":"Connected: ")+api.baseUrl();
    }
    private static String trackTitle(JSONObject item){String supplied=item.optString("track");if(!supplied.isEmpty())return supplied;switch(item.optString("track_id")){case "biotech":return "Биотехнологические препараты";case "delivery":return "Доставка лекарств";case "small-molecules":return "Малые молекулы";case "partners":return "Партнёрские сессии";default:return "";}}
    public List<Session> sessions(){return sessions;}
    public List<Person> people(){return people;}
    public List<Post> posts(){return posts;}
    public List<Partner> partners(){return partners;}
    public boolean authenticate(String code){throw new IllegalStateException("Use asynchronous API login");}
    public boolean demoMode(){return false;}
    public String apiStatus(){return status;}
}

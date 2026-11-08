package org.sirius.biotech.data;

import org.json.*;
import java.util.*;

/** Contiguous oldest-first history, independent of requested pagination size. */
final class MessageCache {
    static String route(String path){String route=path.split("\\?",2)[0];return route.matches("/chats/[^/]+/messages")?route:null;}
    static int parameter(String path,String key,int fallback){try{String query=new java.net.URI(path).getQuery();if(query!=null)for(String part:query.split("&")){String[] pair=part.split("=",2);if(pair.length==2&&pair[0].equals(key))return Integer.parseInt(pair[1]);}}catch(Exception ignored){}return fallback;}
    static JSONObject merge(JSONObject saved,JSONArray rows,int offset,int limit)throws JSONException{
        JSONArray old=saved==null?null:saved.optJSONArray("items");int length=old==null?0:old.length();if(offset>length)return saved;
        JSONArray items=new JSONArray();int end=rows.length()<limit?offset+rows.length():Math.max(length,offset+rows.length());
        Set<String> ids=new HashSet<>();for(int i=0;i<end;i++){JSONObject item=i>=offset&&i<offset+rows.length()?rows.getJSONObject(i-offset):old.getJSONObject(i);items.put(item);ids.add(Long.toString(item.getLong("id")));}
        JSONArray tail=new JSONArray(),previous=saved==null?null:saved.optJSONArray("tail");if(previous!=null)for(int i=0;i<previous.length();i++)if(!ids.contains(Long.toString(previous.getJSONObject(i).getLong("id"))))tail.put(previous.getJSONObject(i));
        return new JSONObject().put("items",items).put("tail",tail).put("complete",rows.length()<limit);
    }
    static JSONArray all(JSONObject saved)throws JSONException{
        TreeMap<Long,JSONObject> rows=new TreeMap<>();for(String field:new String[]{"items","tail"}){JSONArray items=saved==null?null:saved.optJSONArray(field);if(items!=null)for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);rows.put(item.getLong("id"),item);}}
        JSONArray result=new JSONArray();for(JSONObject item:rows.values())result.put(item);return result;
    }
    static JSONObject record(JSONObject saved,JSONObject message)throws JSONException{
        if(saved==null)saved=new JSONObject().put("items",new JSONArray()).put("tail",new JSONArray()).put("complete",false);
        JSONArray all=all(saved);for(int i=0;i<all.length();i++)if(all.getJSONObject(i).getLong("id")==message.getLong("id"))return saved;
        JSONArray tail=saved.optJSONArray("tail");if(tail==null){tail=new JSONArray();saved.put("tail",tail);}tail.put(message);return saved;
    }
    static JSONObject page(JSONObject saved,int offset,int limit)throws JSONException{
        if(saved==null)return null;JSONArray items=saved.getJSONArray("items");if(offset>items.length()||offset==items.length()&&!saved.optBoolean("complete",false))return null;
        JSONArray rows=new JSONArray();for(int i=offset;i<Math.min(items.length(),offset+limit);i++)rows.put(items.getJSONObject(i));return new JSONObject().put("items",rows);
    }
}

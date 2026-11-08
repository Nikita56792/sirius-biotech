package org.sirius.biotech.data;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** App-owned API transport. Call network methods only from a worker thread. */
public final class ApiClient {
    public static final class AuthenticationException extends IOException {public AuthenticationException(){super("Authentication required");}}
    public static final class RateLimitException extends IOException {public final int retryAfter;public RateLimitException(int seconds){super("Too many attempts");retryAfter=seconds;}}
    public static final class HttpException extends IOException {public final int status;public HttpException(int status){super("API HTTP "+status);this.status=status;}}
    public static final class OfflineException extends IOException {public OfflineException(IOException cause){super("Server unavailable; no saved response for this request",cause);}}
    private static final class InvalidResponseException extends IOException {InvalidResponseException(String message){super(message);}}
    private static final String DEFAULT_URL="https://example.invalid";
    private static final int CACHE_SCHEMA=1, MAX_RESPONSE=4*1024*1024, MAX_ENTRIES=128;
    private static final long MAX_CACHE_BYTES=12L*1024*1024, MAX_CACHE_AGE=7L*24*60*60*1000;
    private final android.content.SharedPreferences preferences;
    private final File cacheDirectory;
    private final ThreadLocal<Boolean> offline=new ThreadLocal<>();
    public volatile boolean connectionOffline;
    private long configurationGeneration;

    public ApiClient(Context context){
        preferences=context.getSharedPreferences("sirius-api",0);
        cacheDirectory=new File(context.getNoBackupFilesDir(),"authenticated-read-cache");
        String old=preferences.getString("baseUrl","");
        if(old.equals("https://10.82.10.8:8443")||old.equals("https://10.41.201.156:8443")||old.equals("http://127.0.0.1:8080")||old.equals("http://localhost:8080")){
            preferences.edit().putString("baseUrl",DEFAULT_URL).remove("token").apply();
            clearCache();
        }
        trimCache();
    }
    public String baseUrl(){return preferences.getString("baseUrl",DEFAULT_URL);}
    public synchronized void configure(String value){
        URI uri=URI.create(value.trim());
        boolean loopback="127.0.0.1".equals(uri.getHost())||"localhost".equals(uri.getHost());
        if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||(!"https".equals(uri.getScheme())&&!(loopback&&"http".equals(uri.getScheme()))))throw new IllegalArgumentException("HTTPS required; HTTP is allowed only through localhost development tunnel");
        configurationGeneration++;
        preferences.edit().putString("baseUrl",value.trim().replaceAll("/+$","")).remove("token").apply();
        clearCache();offline.set(false);
    }
    public boolean signedIn(){String token=preferences.getString("token",null);return token!=null&&!token.isEmpty();}
    public synchronized void signOut(){configurationGeneration++;preferences.edit().remove("token").apply();clearCache();offline.set(false);}
    /** Per worker thread: inspect immediately after request(), not on the UI thread. */
    public boolean lastRequestOffline(){return Boolean.TRUE.equals(offline.get());}
    public static boolean offlineError(Throwable error){return error instanceof OfflineException;}
    public synchronized boolean hasCachedSession(){return hasCachedResponse("/bootstrap");}
    public synchronized boolean hasCachedResponse(String path){
        String token=preferences.getString("token",null);
        return token!=null&&cacheable(path)&&readCache(scope(baseUrl(),token),path)!=null;
    }
    /** Immediate private cache read; never performs a network request. */
    public synchronized JSONObject cachedResponse(String path){
        String token=preferences.getString("token",null);if(token==null)return null;
        String scope=scope(baseUrl(),token),route=MessageCache.route(path);
        if(route!=null)try{JSONObject page=MessageCache.page(readCache(scope,"@conversation:"+route),MessageCache.parameter(path,"offset",0),MessageCache.parameter(path,"limit",50));if(page!=null)return page;}catch(Exception ignored){}
        return readCache(scope,path);
    }
    public synchronized org.json.JSONArray cachedMessages(String path){
        String token=preferences.getString("token",null);if(token==null)return new org.json.JSONArray();
        try{return MessageCache.all(readCache(scope(baseUrl(),token),"@conversation:"+MessageCache.route(path)));}catch(Exception ignored){return new org.json.JSONArray();}
    }
    public JSONObject login(String code)throws Exception{
        return authenticate("/auth/login",new JSONObject().put("code",code));
    }
    public JSONObject verifyPin(String challengeId,String code)throws Exception{
        return authenticate("/auth/verify-pin",new JSONObject().put("challenge_id",challengeId).put("code",code));
    }
    private JSONObject authenticate(String path,JSONObject body)throws Exception{
        final long generation;final String origin;
        synchronized(this){generation=configurationGeneration;origin=baseUrl();}
        JSONObject result=request("POST",path,body,false);
        String token=result.getString("access_token");
        if(token.isEmpty())throw new InvalidResponseException("Missing access token");
        synchronized(this){if(generation!=configurationGeneration||!origin.equals(baseUrl()))throw new InvalidResponseException("Server or account changed during authentication");configurationGeneration++;clearCache();preferences.edit().putString("token",token).apply();}
        return result;
    }
    public JSONObject uploadAvatar(byte[] bytes,String mime)throws Exception{
        if(bytes.length>4*1024*1024)throw new IOException("Photo exceeds 4 MiB");String bearer=preferences.getString("token",null);if(bearer==null)throw new AuthenticationException();String boundary="sbs"+UUID.randomUUID().toString().replace("-","");HttpURLConnection c=(HttpURLConnection)new URL(baseUrl()+"/api/v1/profile/avatar").openConnection();c.setConnectTimeout(10000);c.setReadTimeout(20000);c.setRequestMethod("POST");c.setInstanceFollowRedirects(false);c.setDoOutput(true);c.setRequestProperty("Authorization","Bearer "+bearer);c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
        try{try(OutputStream out=c.getOutputStream()){out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"avatar\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(bytes);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));}int status=c.getResponseCode();if(status==401){signOut();throw new AuthenticationException();}if(status!=200)throw new HttpException(status);ByteArrayOutputStream data=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(data.size()+n>MAX_RESPONSE)throw new IOException("Response too large");data.write(b,0,n);}}invalidateFamily("/profile");return new JSONObject(data.toString("UTF-8"));}finally{c.disconnect();}
    }
    public JSONObject uploadChatPhoto(String chatId,byte[] bytes)throws Exception{
        if(!chatId.matches("[0-9a-f]{32}")||bytes.length>4*1024*1024)throw new IOException("Invalid photo");
        final String origin,bearer;final long generation;synchronized(this){origin=baseUrl();bearer=preferences.getString("token",null);generation=configurationGeneration;}
        if(bearer==null)throw new AuthenticationException();
        String boundary="sbs"+UUID.randomUUID().toString().replace("-","");HttpURLConnection c=(HttpURLConnection)new URL(origin+"/api/v1/chats/"+chatId+"/attachments").openConnection();
        c.setConnectTimeout(10000);c.setReadTimeout(30000);c.setRequestMethod("POST");c.setInstanceFollowRedirects(false);c.setDoOutput(true);c.setRequestProperty("Authorization","Bearer "+bearer);c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
        try{try(OutputStream out=c.getOutputStream()){out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(bytes);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));}int status=c.getResponseCode();synchronized(this){requireCurrent(generation);if(status==401){signOut();throw new AuthenticationException();}}if(status!=201)throw new HttpException(status);try(InputStream in=c.getInputStream()){return new JSONObject(readBounded(in,MAX_RESPONSE));}}finally{c.disconnect();}
    }
    public byte[] chatPhoto(String chatId,String photoId)throws Exception{
        if(!chatId.matches("[0-9a-f]{32}")||!photoId.matches("[0-9a-f]{64}"))throw new IOException("Invalid attachment");
        final String origin,bearer;final long generation;synchronized(this){origin=baseUrl();bearer=preferences.getString("token",null);generation=configurationGeneration;}
        if(bearer==null)throw new AuthenticationException();
        File folder=new File(cacheDirectory.getParentFile(),"private-chat-photos");File file=new File(folder,scope(origin,bearer)+"-"+hash(chatId+"/"+photoId)+".jpg");
        synchronized(this){requireCurrent(generation);if(file.isFile()&&file.length()<=4*1024*1024){try(InputStream in=new FileInputStream(file)){ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)data.write(buf,0,n);return data.toByteArray();}}}
        HttpURLConnection c=(HttpURLConnection)new URL(origin+"/api/v1/chats/"+chatId+"/attachments/"+photoId).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Authorization","Bearer "+bearer);
        try{int status=c.getResponseCode();synchronized(this){requireCurrent(generation);if(status==401){signOut();throw new AuthenticationException();}}if(status!=200)throw new HttpException(status);ByteArrayOutputStream data=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(data.size()+n>4*1024*1024)throw new IOException("Photo too large");data.write(buf,0,n);}}byte[] bytes=data.toByteArray();synchronized(this){requireCurrent(generation);if(folder.isDirectory()||folder.mkdirs()){File[] files=folder.listFiles();if(files!=null){Arrays.sort(files,Comparator.comparingLong(File::lastModified));long total=bytes.length;for(File old:files)total+=old.length();for(File old:files)if(total>64L*1024*1024){long size=old.length();if(old.delete())total-=size;}}try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);}catch(IOException ignored){}}}return bytes;}finally{c.disconnect();}
    }
    public JSONObject searchAuth(String lastName)throws Exception{return request("POST","/auth/search",new JSONObject().put("last_name",lastName),false);}
    public JSONObject sendPin(String selectionId)throws Exception{return request("POST","/auth/send-pin",new JSONObject().put("selection_id",selectionId),false);}
    public JSONObject request(String method,String path,JSONObject body)throws Exception{return request(method,path,body,true);}
    private JSONObject request(String method,String path,JSONObject body,boolean authenticated)throws Exception{
        offline.set(false);
        if(!path.startsWith("/")||path.contains("..")||path.contains("#"))throw new IllegalArgumentException("Invalid API path");
        final String origin,bearer;final long generation;
        synchronized(this){origin=baseUrl();generation=configurationGeneration;bearer=preferences.getString("token",null);}
        if(authenticated&&(bearer==null||bearer.isEmpty()))throw new AuthenticationException();
        final boolean cachedRead=authenticated&&"GET".equals(method)&&body==null&&cacheable(path);
        final String cacheScope=cachedRead?scope(origin,bearer):null;
        HttpURLConnection connection=null;
        try{
            connection=(HttpURLConnection)new URL(origin+"/api/v1"+path).openConnection();
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);connection.setReadTimeout(15000);connection.setRequestMethod(method);connection.setRequestProperty("Accept","application/json");
            if(authenticated)connection.setRequestProperty("Authorization","Bearer "+bearer);
            if(body!=null){connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");try(OutputStream out=connection.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int status=connection.getResponseCode();
            synchronized(this){
                requireCurrent(generation);
                if(status==401){if(authenticated&&Objects.equals(bearer,preferences.getString("token",null)))signOut();throw new AuthenticationException();}
            }
            // Classify HTTP failures before reading their body: even a truncated error is not offline.
            if(status==429){int seconds=60;try{seconds=Integer.parseInt(connection.getHeaderField("Retry-After"));}catch(Exception ignored){}throw new RateLimitException(Math.max(1,seconds));}
            if(status<200||status>=300)throw new HttpException(status);
            String text;
            try(InputStream in=connection.getInputStream()){text=readBounded(in,MAX_RESPONSE);}
            JSONObject result=parseResponse(text);
            synchronized(this){
                requireCurrent(generation);
                if(cachedRead)writeCache(cacheScope,path,result);
                if(authenticated&&path.startsWith("/chats"))updateChatCache(scope(origin,bearer),method,path,result);
                else if(authenticated&&!"GET".equals(method)){invalidateFamily(path);if("PUT".equals(method)&&path.matches("/sessions/[^/]+/rating"))writeCache(scope(origin,bearer),path,result);}
            }
            connectionOffline=false;return result;
        }catch(IOException error){
            if(error instanceof AuthenticationException||error instanceof RateLimitException||error instanceof HttpException||error instanceof InvalidResponseException)throw error;
            synchronized(this){
                requireCurrent(generation);
                if(cachedRead){JSONObject saved=cachedResponse(path);if(saved!=null){offline.set(true);connectionOffline=true;return saved;}}
            }
            connectionOffline=true;throw new OfflineException(error);
        }finally{if(connection!=null)connection.disconnect();}
    }
    private void requireCurrent(long generation)throws InvalidResponseException{if(generation!=configurationGeneration)throw new InvalidResponseException("Stale account response");}
    private static JSONObject parseResponse(String text)throws org.json.JSONException{
        if(text.trim().isEmpty())return new JSONObject();
        if(text.trim().startsWith("["))return new JSONObject().put("items",new org.json.JSONArray(text));
        return new JSONObject(text);
    }
    private static String readBounded(InputStream input,int limit)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=input.read(buffer))!=-1){if(bytes.size()+n>limit)throw new InvalidResponseException("API response too large");bytes.write(buffer,0,n);}
        return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
    }
    private static boolean cacheable(String path){
        String route=path.split("\\?",2)[0];
        return route.equals("/bootstrap")||route.equals("/profile")||route.equals("/favorites")||route.equals("/chats")||(route.equals("/meetings")||route.equals("/meeting-availability"))||route.matches("/chats/[^/]+/messages")||route.matches("/meetings/[^/]+")||route.matches("/sessions/[^/]+/rating");
    }
    private static String hash(String value){
        try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static String scope(String origin,String token){return hash(origin+"\n"+hash(token));}
    private File cacheFile(String scope,String path){return new File(cacheDirectory,scope+"-"+hash(path)+".json");}
    // All disk operations run under this client's lock; credentials never enter cache files or names.
    private synchronized JSONObject readCache(String scope,String path){
        File file=cacheFile(scope,path);
        if(!file.isFile())return null;
        try{
            if(file.length()>MAX_RESPONSE)throw new IOException("Oversized cache");
            JSONObject envelope;try(InputStream input=new FileInputStream(file)){envelope=new JSONObject(readBounded(input,MAX_RESPONSE));}
            long age=System.currentTimeMillis()-envelope.getLong("saved_at");
            if(envelope.getInt("schema")!=CACHE_SCHEMA||!scope.equals(envelope.getString("scope"))||!path.equals(envelope.getString("path"))||age<0||age>MAX_CACHE_AGE)throw new IOException("Expired cache");
            return envelope.getJSONObject("response");
        }catch(Exception ignored){file.delete();return null;}
    }
    private synchronized void writeCache(String scope,String path,JSONObject result){
        File temporary=null;
        try{
            if(!cacheDirectory.isDirectory()&&!cacheDirectory.mkdirs())return;
            JSONObject envelope=new JSONObject().put("schema",CACHE_SCHEMA).put("scope",scope).put("path",path).put("saved_at",System.currentTimeMillis()).put("response",result);
            byte[] bytes=envelope.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_RESPONSE)return;
            // Reserve capacity before writing, including space for the atomic replacement.
            trimCache(bytes.length,1);
            temporary=File.createTempFile("pending-",".tmp",cacheDirectory);
            try(FileOutputStream output=new FileOutputStream(temporary)){output.write(bytes);output.getFD().sync();}
            if(!temporary.renameTo(cacheFile(scope,path)))temporary.delete();
            trimCache();
        }catch(Exception ignored){if(temporary!=null)temporary.delete();}
    }
    private synchronized void updateChatCache(String scope,String method,String path,JSONObject response){
        try{
            String route=MessageCache.route(path);
            if(route!=null){String key="@conversation:"+route;JSONObject previous=readCache(scope,key);
                JSONObject next="GET".equals(method)?MessageCache.merge(previous,response.getJSONArray("items"),MessageCache.parameter(path,"offset",0),MessageCache.parameter(path,"limit",50)):MessageCache.record(previous,response);
                if(next!=null)writeCache(scope,key,next);
            }
            if("GET".equals(method))return;
            File[] files=cacheDirectory.listFiles();if(files==null)return;
            for(File file:files){
                JSONObject envelope;try(InputStream input=new FileInputStream(file)){envelope=new JSONObject(readBounded(input,MAX_RESPONSE));}
                String cachedPath=envelope.optString("path");if(!scope.equals(envelope.optString("scope"))||!cachedPath.startsWith("/chats?"))continue;
                JSONObject saved=envelope.getJSONObject("response");org.json.JSONArray items=saved.getJSONArray("items");String chatId=route==null?response.optString("id"):route.split("/")[2];
                boolean found=false;
                for(int i=0;i<items.length();i++){JSONObject chat=items.getJSONObject(i);if(chatId.equals(chat.optString("id"))){found=true;if(path.endsWith("/state")){java.util.Iterator<String> keys=response.keys();while(keys.hasNext()){String key=keys.next();chat.put(key,response.get(key));}}if(route!=null)chat.put("last_message",response.optString("text")).put("updated_at",response.optString("created_at"));}}
                if(route==null&&!found&&MessageCache.parameter(cachedPath,"offset",0)==0&&items.length()<MessageCache.parameter(cachedPath,"limit",50))items.put(response);
                writeCache(scope,cachedPath,saved);
            }
        }catch(Exception ignored){/* Cache failures must never turn a confirmed send into a failed send. */}
    }
    private synchronized void invalidateFamily(String path){
        String family=path.split("\\?",2)[0].split("/",3)[1];
        File[] files=cacheDirectory.listFiles();if(files==null)return;
        for(File file:files){
            if(file.length()>MAX_RESPONSE){file.delete();continue;}
            try(InputStream input=new FileInputStream(file)){
                JSONObject envelope=new JSONObject(readBounded(input,MAX_RESPONSE));
                if(envelope.optString("path").startsWith("/"+family))file.delete();
            }catch(Exception ignored){file.delete();}
        }
    }
    private synchronized void clearCache(){File[] files=cacheDirectory.listFiles();if(files!=null)for(File file:files)file.delete();File[] photos=new File(cacheDirectory.getParentFile(),"private-chat-photos").listFiles();if(photos!=null)for(File photo:photos)photo.delete();}
    private synchronized void trimCache(){trimCache(0,0);}
    private synchronized void trimCache(long reserve,int entries){
        File[] files=cacheDirectory.listFiles();if(files==null)return;
        Arrays.sort(files,Comparator.comparingLong(File::lastModified));long total=reserve;int count=entries;
        for(File file:files){long age=System.currentTimeMillis()-file.lastModified();if(!file.getName().endsWith(".json")||file.length()>MAX_RESPONSE||age<0||age>MAX_CACHE_AGE){file.delete();}else{total+=file.length();count++;}}
        for(File file:files){if(total<=MAX_CACHE_BYTES&&count<=MAX_ENTRIES)break;if(file.isFile()){long size=file.length();if(file.delete()){total-=size;count--;}}}
    }
}

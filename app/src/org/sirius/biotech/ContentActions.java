package org.sirius.biotech;
import android.content.*;
import android.net.Uri;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import static org.sirius.biotech.OriginalUi.*;

final class ContentActions {
    static void favorite(MainActivity a,String kind,String id,boolean selected,Runnable finished){int owner=a.accountGeneration;a.network.execute(()->{Exception problem=null;JSONArray result=null;try{JSONArray current=a.api.request("GET","/favorites",null).getJSONArray("items"),next=new JSONArray();for(int n=0;n<current.length();n++){JSONObject item=current.getJSONObject(n);if(!(kind.equals(item.optString("kind"))&&id.equals(item.optString("item_id"))))next.put(item);}if(selected)next.put(new JSONObject().put("kind",kind).put("item_id",id));result=a.api.request("PUT","/favorites",new JSONObject().put("items",next)).getJSONArray("items");}catch(Exception error){problem=error;}JSONArray accepted=result;Exception failure=problem;a.runOnUiThread(()->{if(owner!=a.accountGeneration)return;if(accepted!=null)a.serverFavorites=accepted;else a.toast("Could not save changes. Please retry.");finished.run();});});}
    static void url(MainActivity a,String value){try{Uri uri=Uri.parse(value);if(!"https".equalsIgnoreCase(uri.getScheme())&&!"http".equalsIgnoreCase(uri.getScheme()))return;a.startActivity(new Intent(Intent.ACTION_VIEW,uri));}catch(ActivityNotFoundException e){a.toast("Unable to open link");}}
    static void photo(MainActivity a,String path,String title,Runnable back){try{android.content.Context c=theme(a);FrameLayout root=new FrameLayout(c);insets(root);inflate(c,"image_viewer",root);toolbar(a,view(root,"toolbar"),title,back,null,null);visible(root,"loadingView",true);visible(root,"saveProgressView",false);visible(root,"description",false);visible(root,"errorView",false);View target=view(root,"imageView");final int owner=a.accountGeneration;
        ImageView loader=new ImageView(c){@Override public void setImageBitmap(Bitmap image){super.setImageBitmap(image);if(image==null)return;a.network.execute(()->{try{File file=new File(a.getCacheDir(),"sbs-photo-viewer.png");try(FileOutputStream out=new FileOutputStream(file)){image.compress(Bitmap.CompressFormat.PNG,100,out);}a.runOnUiThread(()->{if(owner!=a.accountGeneration||!root.isAttachedToWindow())return;try{Object source=make("oa.a",new Class<?>[]{Uri.class},Uri.fromFile(file));call(target,"setImage",new Class<?>[]{cls("oa.a")},source);visible(root,"loadingView",false);}catch(Exception error){failed(a,error);}});}catch(Exception e){a.runOnUiThread(()->failed(a,e));}});}};
        a.sceneBack=back;a.setContentView(root);ProfilePhotos.load(a,loader,path,false);
    }catch(Exception e){failed(a,e);}}
}

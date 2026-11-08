package org.sirius.biotech;

import android.graphics.*;
import android.media.ExifInterface;
import java.io.*;

/** Decode up to 20 MiB locally, apply camera orientation and bound crop memory. */
final class ProfilePhotoInput {
    static final int MAX_SOURCE=20*1024*1024;
    static Bitmap decode(byte[] raw)throws IOException {
        if(raw.length>MAX_SOURCE)throw new IOException("Photo exceeds 20 MiB");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(raw,0,raw.length,options);
        if(options.outWidth<=0||options.outHeight<=0||(long)options.outWidth*options.outHeight>100000000)throw new IOException("Unsupported image dimensions");
        options.inJustDecodeBounds=false;options.inSampleSize=1;
        while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>2048)options.inSampleSize*=2;
        Bitmap bitmap=BitmapFactory.decodeByteArray(raw,0,raw.length,options);if(bitmap==null)throw new IOException("Invalid photo");
        int orientation=ExifInterface.ORIENTATION_NORMAL;
        try{orientation=new ExifInterface(new ByteArrayInputStream(raw)).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);}catch(IOException ignored){}
        Matrix matrix=new Matrix();
        switch(orientation){
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:matrix.setScale(-1,1);break;
            case ExifInterface.ORIENTATION_ROTATE_180:matrix.setRotate(180);break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:matrix.setScale(1,-1);break;
            case ExifInterface.ORIENTATION_TRANSPOSE:matrix.setRotate(90);matrix.postScale(-1,1);break;
            case ExifInterface.ORIENTATION_ROTATE_90:matrix.setRotate(90);break;
            case ExifInterface.ORIENTATION_TRANSVERSE:matrix.setRotate(270);matrix.postScale(-1,1);break;
            case ExifInterface.ORIENTATION_ROTATE_270:matrix.setRotate(270);break;
        }
        if(!matrix.isIdentity()){Bitmap oriented=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);if(oriented!=bitmap)bitmap.recycle();bitmap=oriented;}
        return bitmap;
    }
}

// Shared by the attendee PWA and organizer panel. All processing stays on device.
export const SOURCE_LIMIT = 20 * 1024 * 1024;
export const UPLOAD_LIMIT = 4 * 1024 * 1024;
export async function decodePhoto(file) {
  if (typeof createImageBitmap === 'function') return createImageBitmap(file, {imageOrientation:'from-image'});
  const url=URL.createObjectURL(file),image=new Image();
  try { image.src=url;await image.decode();return image; }
  finally { URL.revokeObjectURL(url); }
}
export async function preparePhoto(file, {language='ru'}={}) {
  const ru=language.startsWith('ru');
  if (!(file instanceof Blob) || !['image/jpeg','image/png','image/webp'].includes(file.type)) throw new Error(ru?'Выберите фото JPEG, PNG или WebP.':'Choose a JPEG, PNG or WebP photo.');
  if (file.size>SOURCE_LIMIT) throw new Error(ru?'Размер фото — не более 20 МБ.':'Choose a photo up to 20 MB.');
  const image=await decodePhoto(file);
  try {
    const width=image.width || image.naturalWidth,height=image.height || image.naturalHeight;
    if(!width||!height||width*height>100000000)throw new Error(ru?'Слишком большое разрешение фото.':'Photo dimensions are too large.');
    if(file.size<=UPLOAD_LIMIT && width*height<=16000000)return file;
    let side=Math.min(4000,Math.max(width,height));
    for(let pass=0;pass<5;pass++){
      const scale=Math.min(1,side/Math.max(width,height));
      const canvas=document.createElement('canvas');canvas.width=Math.max(1,Math.round(width*scale));canvas.height=Math.max(1,Math.round(height*scale));
      const context=canvas.getContext('2d');context.fillStyle='#fff';context.fillRect(0,0,canvas.width,canvas.height);context.drawImage(image,0,0,canvas.width,canvas.height);
      for(const quality of [.9,.8,.65,.5]){
        const blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/jpeg',quality));
        if(blob&&blob.size<=UPLOAD_LIMIT){canvas.width=canvas.height=1;return blob;}
      }
      canvas.width=canvas.height=1;side=Math.round(side*.7);
    }
    throw new Error(ru?'Не удалось сжать фото до 4 МБ.':'Could not compress the photo to 4 MB.');
  } finally { image.close?.(); }
}

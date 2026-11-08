// Shared touch dismissal for app dialogs. Navigator sheets own their gestures.
export function makeDismissible(dialog, {canDismiss=()=>true}={}) {
  let gesture=null;
  const outside=event=>{const r=dialog.getBoundingClientRect();return event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom;};
  const close=()=>{if(canDismiss()&&dialog.open)dialog.close();};
  const reset=()=>{dialog.style.removeProperty('transform');gesture=null;};
  function scrollAtTop(target){
    for(let el=target;el&&el!==dialog;el=el.parentElement)if(el.scrollHeight>el.clientHeight+2&&getComputedStyle(el).overflowY.match(/auto|scroll/)&&el.scrollTop>0)return false;
    return dialog.scrollTop<=0;
  }
  const start=event=>{
    if(event.isPrimary===false||event.button>0||!canDismiss())return;
    const backdrop=event.target===dialog&&outside(event);
    const control=event.target.closest?.('button,a,input,textarea,select,canvas,[contenteditable="true"],[role="slider"]');
    if(!backdrop&&(control||!scrollAtTop(event.target)))return;
    gesture={id:event.pointerId,x:event.clientX,y:event.clientY,backdrop,drag:false,dy:0};
  };
  const move=event=>{
    if(!gesture||gesture.id!==event.pointerId||gesture.backdrop)return;
    const dy=event.clientY-gesture.y,dx=event.clientX-gesture.x;
    if(!gesture.drag&&dy>12&&dy>Math.abs(dx)*1.4){gesture.drag=true;dialog.setPointerCapture?.(event.pointerId);}
    if(gesture.drag){gesture.dy=Math.max(0,dy);dialog.style.transform='translateY('+gesture.dy+'px)';if(event.cancelable)event.preventDefault();}
  };
  const end=event=>{
    if(!gesture||gesture.id!==event.pointerId)return;
    const dismiss=gesture.backdrop?event.target===dialog&&outside(event)&&Math.hypot(event.clientX-gesture.x,event.clientY-gesture.y)<20:gesture.drag&&gesture.dy>=Math.min(110,Math.max(60,dialog.clientHeight*.22));
    reset();if(dismiss)close();
  };
  dialog.addEventListener('pointerdown',start);
  dialog.addEventListener('pointermove',move,{passive:false});
  dialog.addEventListener('pointerup',end);
  dialog.addEventListener('pointercancel',reset);
  dialog.addEventListener('cancel',event=>{if(!canDismiss())event.preventDefault();});
  dialog.addEventListener('close',()=>{reset();dialog.removeEventListener('pointerdown',start);dialog.removeEventListener('pointermove',move);dialog.removeEventListener('pointerup',end);dialog.removeEventListener('pointercancel',reset);},{once:true});
}

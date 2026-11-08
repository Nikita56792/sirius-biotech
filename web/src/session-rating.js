import {STAR_PATH} from './rating-star.js';

// Paths from the retained MSS icon_todo_24 and icon_chevron_16 drawables.
const TODO_PATH = 'M20,2.667v-0.002a1.33,1.33 0,0 1,1.333 -1.332,1.33 1.33,0 0,1 1.334,1.332v0.002h2.665A2.665,2.665 0,0 1,28 5.333v21.334a2.664,2.664 0,0 1,-2.668 2.666L6.668,29.333A2.665,2.665 0,0 1,4 26.667L4,5.333a2.664,2.664 0,0 1,2.668 -2.666h2.665v-0.002a1.33,1.33 0,0 1,1.334 -1.332A1.33,1.33 0,0 1,12 2.665v0.002h2.667v-0.002A1.33,1.33 0,0 1,16 1.333a1.33,1.33 0,0 1,1.333 1.332v0.002L20,2.667zM20,5.333h-2.667v0.002A1.33,1.33 0,0 1,16 6.667a1.33,1.33 0,0 1,-1.333 -1.332v-0.003L12,5.332v0.003a1.33,1.33 0,0 1,-1.333 1.332,1.33 1.33,0 0,1 -1.334,-1.332v-0.003l-2.666,0.001s-0.002,21.334 0,21.334c0,0 18.666,0.002 18.666,0 0,0 0.002,-21.334 0,-21.334h-2.666v0.002a1.33,1.33 0,0 1,-1.334 1.332A1.33,1.33 0,0 1,20 5.335v-0.002zM16,12.667c0,-0.737 0.601,-1.334 1.339,-1.334h5.322a1.333,1.333 0,1 1,0 2.667h-5.322c-0.74,0 -1.339,-0.592 -1.339,-1.333zM16,20c0,-0.736 0.601,-1.333 1.339,-1.333h5.322a1.333,1.333 0,1 1,0 2.666h-5.322c-0.74,0 -1.339,-0.591 -1.339,-1.333zM9.724,13.61L8.39,12.275a1.333,1.333 0,0 1,1.885 -1.885l0.391,0.39 1.724,-1.724a1.333,1.333 0,0 1,1.885 1.886l-2.667,2.666c-0.52,0.521 -1.364,0.521 -1.885,0zM9.724,20.943L8.39,19.609a1.333,1.333 0,0 1,1.885 -1.885l0.393,0.393 1.722,-1.726a1.333,1.333 0,1 1,1.885 1.885l-2.667,2.667c-0.52,0.52 -1.364,0.52 -1.885,0z';
const CHEVRON_PATH='M8.586,26.586a2,2 0,0 0,2.828 2.828l12,-12a2,2 0,0 0,0 -2.828l-12,-12a2,2 0,0 0,-2.828 2.828L19.172,16 8.586,26.586z';
function svg(path,box='0 0 32 32') {const el=document.createElementNS('http://www.w3.org/2000/svg','svg');el.setAttribute('viewBox',box);el.setAttribute('aria-hidden','true');const p=document.createElementNS(el.namespaceURI,'path');p.setAttribute('d',path);el.append(p);return el;}

export function mountSessionRating(container,{read,write,t,isCurrent,report,openPoll}) {
  let confirmed=null,ready=false;
  const trigger=document.createElement('button');trigger.type='button';trigger.className='session-rating-trigger';trigger.disabled=true;
  const icon=svg(TODO_PATH), label=document.createElement('span'), chevron=svg(CHEVRON_PATH);
  icon.classList.add('rating-icon');chevron.classList.add('rating-chevron');trigger.append(icon,label,chevron);container.replaceChildren(trigger);
  function render(){trigger.disabled=!ready;trigger.classList.toggle('rated',confirmed!==null);label.textContent=confirmed===null?t('Оцените выступление','Rate this session'):t('Оценка отправлена','Rating sent');}
  async function load(){try{const result=await read();if(!isCurrent())return;if(result.value!==null&&(!Number.isInteger(result.value)||result.value<1||result.value>5))throw new Error(t('Не удалось прочитать оценку','Could not load rating'));confirmed=result.value;ready=true;render();}catch(error){if(isCurrent()){trigger.disabled=false;label.textContent=t('Повторить загрузку оценки','Retry loading rating');ready=false;report(error);}}}
  trigger.onclick=()=>{if(ready)openPoll({initial:confirmed||0,write,confirmed:value=>{confirmed=value;render();}});else load();};render();load();
}

// The MSS poll screen uses an original RatingView and explicit submit.
export function ratingStars(group,initial,onSelect) {
 let selected=initial||0;const stars=[];
 function select(value,focus=false){selected=value;stars.forEach((star,i)=>{star.classList.toggle('selected',i<value);star.setAttribute('aria-checked',String(i+1===value));star.tabIndex=(value?i+1===value:i===0)?0:-1;});onSelect(value);if(focus)stars[value-1].focus();}
 for(let i=1;i<=5;i++){
  const star=document.createElement('button');star.type='button';star.setAttribute('role','radio');star.setAttribute('aria-label',i+' / 5');star.append(svg(STAR_PATH,'0 0 40 40'));
  star.onclick=()=>select(i);star.onkeydown=event=>{let next=selected||1;if(['ArrowRight','ArrowUp'].includes(event.key))next=Math.min(5,next+1);else if(['ArrowLeft','ArrowDown'].includes(event.key))next=Math.max(1,next-1);else if(event.key==='Home')next=1;else if(event.key==='End')next=5;else return;event.preventDefault();select(next,true);};
  stars.push(star);group.append(star);
 }
 select(selected);return stars;
}

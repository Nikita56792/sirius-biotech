import {mountSessionRating,ratingStars} from './session-rating.js';

// The structure and dimensions follow session_view, session_header_view,
// session_info_view, session_questions_view and poll_view from MSS.
export function sessionScene(env) {
 const {item,main,n,b,t,api,title,action,back,toSession,isCurrent,report,notice,favoriteButton,avatar,people,personView,venue,language}=env;
 const path='/sessions/'+encodeURIComponent(item.id), card=n('div',{class:'mss-session-card'});
 const header=n('div',{class:'mss-session-heading'},n('h2',{},item.title||''));
 const tabs=n('div',{class:'mss-session-tabs',role:'tablist'}), content=n('div',{class:'mss-session-content'});
 const infoButton=b(t('Информация','Information'),()=>show('info'));
 const questionsButton=b(t('Вопросы','Questions'),()=>show('questions'));
 for(const [index,tab] of [infoButton,questionsButton].entries()){tab.setAttribute('role','tab');tab.id='mss-session-tab-'+index;tab.setAttribute('aria-controls','mss-session-panel');tabs.append(tab);}
 const panel=n('section',{id:'mss-session-panel',role:'tabpanel'});
 content.append(panel);card.append(header,tabs,content);main.className='native-main mss-session-main';main.replaceChildren(card);
 const favorite=favoriteButton('sessions',item.id);favorite.classList.add('mss-session-favorite');
 const calendar=action('calendar',t('Добавить в календарь','Add to calendar'),downloadCalendar);
 title(t('Сессия','Session'),back,[favorite,calendar]);
 const date=()=>{if(!item.date)return '';let d=new Date(item.date+'T12:00:00');return Number.isNaN(d.getTime())?item.date:d.toLocaleDateString(language==='ru'?'ru-RU':'en-GB',{day:'numeric',month:'long',year:'numeric'});};
 function row(icon,body,click){const child=n('span',{},body);const parts=[n('img',{src:'/app/assets/native-'+icon+'.svg',alt:''}),child];if(click)parts.push(n('span',{class:'mss-row-chevron','aria-hidden':'true'},'›'));return click?b(parts,click,'mss-session-metadata'):n('div',{class:'mss-session-metadata'},...parts);}
 const information=n('div',{class:'mss-session-information'});
 const period=[item.start_time,item.end_time].filter(Boolean).join('–');if(item.date||period)information.append(row('clock',[date(),period].filter(Boolean).join(' · ')));
 if(item.room)information.append(row('pin',item.room,venue));
 if(item.track)information.append(n('div',{class:'mss-session-tag'},item.track));
 if(item.description)information.append(n('p',{class:'mss-session-description'},item.description));
 const speakerNames=(Array.isArray(item.speakers)?item.speakers:[]).map(s=>typeof s==='string'?s:s?.name).filter(Boolean);
 const normal=value=>String(value||'').trim().replace(/\s+/g,' ').toLocaleLowerCase();
 let matched=0;
 for(const name of speakerNames){const person=people.find(p=>normal(p.name)===normal(name));if(!person)continue;matched++;const copy=n('span',{},n('strong',{},person.name),n('small',{},[person.position,person.company].filter(Boolean).join(' · ')));information.append(b([avatar(person),copy],()=>personView(person),'mss-session-speaker'));}
 if(speakerNames.length>matched)information.append(n('p',{class:'mss-session-speaker-fallback'},speakerNames.filter(name=>!people.some(p=>normal(p.name)===normal(name))).join(', ')));
 const ratingBox=n('section',{class:'mss-rating-box'});information.append(ratingBox);
 mountSessionRating(ratingBox,{read:()=>api(path+'/rating'),write:value=>api(path+'/rating','PUT',{value}),t,isCurrent,report,openPoll});
 const questionPanel=n('div',{class:'mss-session-questions'}), list=n('div',{class:'mss-question-list'}), empty=n('div',{class:'mss-question-empty'},n('p',{},t('Пока нет вопросов','No questions yet'))), refresh=b(t('Обновить','Refresh'),()=>loadQuestions(true),'mss-question-refresh'), more=b(t('Ещё вопросы','More questions'),()=>loadQuestions(false),'mss-question-refresh');
 empty.append(refresh);more.hidden=true;
 const field=n('textarea',{name:'question',maxlength:'10000',rows:'1',placeholder:t('Ваш вопрос спикеру','Your question to the speaker'),'aria-label':t('Ваш вопрос спикеру','Your question to the speaker')});
 const send=n('button',{type:'submit',class:'mss-question-send','aria-label':t('Отправить вопрос','Send question')},n('img',{src:'/app/assets/native-send.svg',alt:''}));send.disabled=true;
 const form=n('form',{class:'mss-question-composer'},field,send);questionPanel.append(list,empty,more,form);
 let loaded=false,loading=false,offset=0,sending=false;
 field.addEventListener('input',()=>{send.disabled=sending||!field.value.trim();});
 form.addEventListener('submit',async event=>{event.preventDefault();const text=field.value.trim();if(!text||sending)return;sending=true;send.disabled=true;try{await api(path+'/questions','POST',{text});if(!isCurrent())return;field.value='';notice(t('Вопрос отправлен организатору','Question sent to the organizer'));await loadQuestions(true);}catch(error){if(isCurrent())report(error);}finally{sending=false;if(isCurrent())send.disabled=!field.value.trim();}});
 async function loadQuestions(reset){if(loading)return;loading=true;refresh.disabled=more.disabled=true;try{const rows=await api(path+'/questions?limit=50&offset='+(reset?0:offset));if(!isCurrent())return;if(!Array.isArray(rows))throw new Error(t('Не удалось загрузить вопросы','Could not load questions'));if(reset){list.replaceChildren();offset=0;}offset+=rows.length;for(const q of rows){const text=n('p',{},q.text||'');const answer=q.response||q.answer;list.append(n('article',{class:'mss-question-item'},text,answer?n('p',{class:'mss-question-answer'},answer):null));}empty.hidden=offset>0;more.hidden=rows.length<50;loaded=true;}catch(error){if(isCurrent())report(error);}finally{loading=false;refresh.disabled=more.disabled=false;}}
 function show(name){const isInfo=name==='info';infoButton.classList.toggle('selected',isInfo);questionsButton.classList.toggle('selected',!isInfo);infoButton.setAttribute('aria-selected',String(isInfo));questionsButton.setAttribute('aria-selected',String(!isInfo));panel.setAttribute('aria-labelledby',isInfo?infoButton.id:questionsButton.id);panel.replaceChildren(isInfo?information:questionPanel);if(!isInfo&&!loaded)loadQuestions(true);}
 function downloadCalendar(){if(!item.date||!item.start_time){notice(t('Дата и время не указаны','Date and time are unavailable'));return;}const esc=x=>String(x||'').replace(/\\/g,'\\\\').replace(/\n/g,'\\n').replace(/,/g,'\\,').replace(/;/g,'\\;');const stamp=time=>item.date.replace(/-/g,'')+'T'+time.replace(/:/g,'').slice(0,4)+'00';const lines=['BEGIN:VCALENDAR','VERSION:2.0','PRODID:-//Sirius Biotech Summit//Programme//RU','BEGIN:VEVENT','UID:'+esc(item.id)+'@sirius-biotech','DTSTAMP:'+new Date().toISOString().replace(/[-:]/g,'').replace(/\.\d{3}/,'') ,'DTSTART;TZID=Europe/Moscow:'+stamp(item.start_time),'DTEND;TZID=Europe/Moscow:'+stamp(item.end_time||item.start_time),'SUMMARY:'+esc(item.title),'LOCATION:'+esc(item.room),'DESCRIPTION:'+esc(item.description),'END:VEVENT','END:VCALENDAR',''];const url=URL.createObjectURL(new Blob([lines.join('\r\n')],{type:'text/calendar;charset=utf-8'}));const link=document.createElement('a');link.href=url;link.download='sirius-session.ics';document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);}
 function openPoll({initial,write,confirmed}){
  if(!isCurrent())return;
  title(t('Оцените выступление','Rate this session'),toSession);main.className='native-main mss-poll-main';
  const body=n('div',{class:'mss-poll-card'}),group=n('div',{class:'mss-poll-stars',role:'radiogroup','aria-label':t('Оцените выступление','Rate this session')}),status=n('p',{class:'mss-poll-error',role:'alert'}),submit=n('button',{type:'button',class:'mss-poll-submit'},t('Отправить','Send'));
  let selected=initial,sending=false;const stars=ratingStars(group,initial,value=>{selected=value;submit.disabled=!value;});
  body.append(n('h2',{},t('Оцените выступление','Rate this session')),group,status,submit);main.replaceChildren(body);
  submit.onclick=async()=>{if(sending||!selected)return;sending=true;submit.disabled=true;stars.forEach(star=>star.disabled=true);status.textContent='';try{const result=await write(selected);if(!isCurrent())return;if(!Number.isInteger(result.value)||result.value<1||result.value>5)throw new Error(t('Оценка не подтверждена','Rating not confirmed'));confirmed(result.value);completion(body);}catch(error){if(isCurrent()){status.textContent=error.message;submit.disabled=false;stars.forEach(star=>star.disabled=false);}}finally{sending=false;}};
 }
 function completion(body){const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('viewBox','0 0 240 240');svg.setAttribute('aria-hidden','true');for(const [d,fill] of [['M120,220c-55.228,0 -100,-44.772 -100,-100S64.772,20 120,20s100,44.772 100,100 -44.772,100 -100,100zM120,200c44.183,0 80,-35.817 80,-80s-35.817,-80 -80,-80 -80,35.817 -80,80 35.817,80 80,80z','#e3ecf6'],['M97.929,157.071c3.905,3.905 10.237,3.905 14.142,0l55,-55c3.905,-3.905 3.905,-10.237 0,-14.142 -3.905,-3.905 -10.237,-3.905 -14.142,0L105,135.999l-22.929,-23.07c-3.905,-3.905 -10.237,-3.905 -14.142,0 -3.905,3.905 -3.905,10.237 0,14.142l30,30z','#4cd964']]){const p=document.createElementNS(svg.namespaceURI,'path');p.setAttribute('d',d);p.setAttribute('fill',fill);p.setAttribute('fill-rule','evenodd');svg.append(p);}const center=n('div',{class:'mss-poll-completion'},svg,n('h2',{},t('Спасибо!','Thank you!')));body.replaceChildren(center,b(t('Продолжить','Continue'),toSession,'mss-poll-submit'));}
 show('info');
}

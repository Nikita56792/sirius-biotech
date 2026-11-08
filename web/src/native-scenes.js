import {makeDismissible} from './dialog-dismiss.js';
// Android MSS scene hierarchy and measurements, bound to the same SBS API.
export function createNativeScenes(env) {
  const {node:n,button:b,t,api,savedRead,avatar,field,input,report,notice,render,shell,sessionView,favoriteButton,isFavorite,formatDate,profileView,schedulePoll,safeAction} = env;
  const state=()=>env.state(), locale=()=>state().language==='ru'?'ru-RU':'en-GB';
  const icon=(name)=>n('img',{class:'native-icon',src:'/app/assets/native-'+name+'.svg',alt:''});
  const action=(name,label,fn)=>{const el=b(icon(name),fn,'native-icon-button');el.setAttribute('aria-label',label);return el;};
  const date=(value,opts)=>new Date(value).toLocaleDateString(locale(),opts);
  const clock=value=>new Date(value).toLocaleTimeString(locale(),{hour:'2-digit',minute:'2-digit'});
  const dates=()=>[...new Set((state().data.sessions||[]).map(s=>s.date).filter(Boolean))].sort();
  const current=ticket=>ticket===state().generation && !!state().token;
  const title=(text,back,actions=[])=>{const header=document.querySelector('#app>header');header.className='native-header';header.replaceChildren(action('back',t('Назад','Back'),back),n('h1',{},text),...actions);env.refreshInstallButtons();};
  function screen(id,label,back,actions=[]) {const main=shell(id);main.className='native-main';title(label,back,actions);return main;}
  function dialog(label,children,cls='') {
    const d=n('dialog',{class:'native-dialog '+cls,'aria-label':label},n('h2',{},label),...children);
    makeDismissible(d);if(cls.includes('native-bottom-sheet')){const handle=n('div',{class:'dialog-drag-handle','aria-hidden':'true'});d.prepend(handle);}d.addEventListener('close',()=>d.remove(),{once:true});document.body.append(d);d.showModal();return d;
  }
  function toolbar(main,label,back,actions=[]) {title(label,back,actions);return main;}
  let day='',room='',tags=new Set();
  const trackTitle=s=>({biotech:t('Биотехнологические препараты','Biotechnology medicines'),delivery:t('Доставка лекарств','Drug delivery'),'small-molecules':t('Малые молекулы','Small molecules'),partners:t('Партнёрские сессии','Partner sessions')}[s.track_id]||s.track||t('Без темы','No topic'));
  function schedule(main,only=false,searching=false,initial='') {
    main.className='native-main native-schedule';const available=dates();if(!available.includes(day))day=available[0]||'';
    const rooms=[...new Set((state().data.sessions||[]).map(s=>s.room).filter(Boolean))].sort();if(!rooms.includes(room))room=rooms[0]||'';
    const back=()=>searching?render(only?'favorites':'schedule'):render('menu');
    title(only?t('Моя программа','My programme'):t('Программа','Programme'),back,searching?[]:[action('search',t('Поиск','Search'),()=>schedule(main,only,true))]);
    document.querySelector('#app>header').classList.toggle('native-schedule-header',!searching);
    const list=n('div',{class:'native-sessions'}),q=input('session-search',{type:'search',placeholder:t('Поиск','Search'),'aria-label':t('Поиск','Search')});q.value=initial;
    const location=n('div',{class:'native-locations'}),days=n('div',{class:'native-days'});
    const draw=()=>{
      let lastDate='';const rows=[];
      const sessions=(state().data.sessions||[]).filter(s=>(!only||isFavorite('sessions',s.id))&&(only||searching||s.date===day)&&(only||searching||!room||s.room===room)&&(!tags.size||tags.has(trackTitle(s)))&&[s.title,s.description,s.room,...(s.speakers||[]).map(p=>typeof p==='string'?p:p.name)].join(' ').toLocaleLowerCase().includes(q.value.toLocaleLowerCase())).sort((a,b)=>(a.date+a.start_time).localeCompare(b.date+b.start_time));
      for(const s of sessions){
        if((only||searching)&&lastDate!==s.date){rows.push(n('div',{class:'native-date-heading'},date(s.date+'T12:00:00',{weekday:'long',day:'numeric',month:'long'}).toLocaleUpperCase()));lastDate=s.date;}
        const star=favoriteButton('sessions',s.id);star.addEventListener('favorite-changed',()=>{if(only&&main.isConnected)draw();});
        const detail=b(n('span',{},n('strong',{},s.title),...(s.speakers?.length?[n('span',{class:'native-speakers'},s.speakers.map(p=>typeof p==='string'?p:p.name).join(', '))]:[])),()=>sessionView(s),'native-session-open');
        const card=n('article',{class:'native-session-card'},n('div',{class:'native-session-body'},detail,star),n('div',{class:'native-session-room'},s.room||''));
        rows.push(n('div',{class:'native-session-row'},n('div',{class:'native-session-time'},n('strong',{},s.start_time||''),n('span',{},s.end_time||'')),card));
      }
      list.replaceChildren(...(rows.length?rows:[n('p',{class:'native-empty'},t('Здесь пока ничего нет','Nothing here yet'))]));
      location.replaceChildren(...rooms.map(value=>{const el=b(value,()=>{room=value;draw();},value===room?'selected':'');el.setAttribute('aria-pressed',String(value===room));return el;}));
      days.replaceChildren(...available.map(value=>{const el=b(n('span',{},n('strong',{},new Date(value+'T12:00:00').getDate()),n('small',{},date(value+'T12:00:00',{weekday:'long'}).toLocaleUpperCase())),()=>{day=value;draw();},value===day?'selected':'');el.setAttribute('aria-label',date(value+'T12:00:00',{day:'numeric',month:'long'}));el.setAttribute('aria-pressed',String(value===day));return el;}));
    };
    q.addEventListener('input',draw);
    const filter=b(n('span',{},t(tags.size?'Выбранные темы':'Все темы',tags.size?'Selected topics':'All topics'),n('span',{class:'native-filter-dots','aria-hidden':'true'},'●●\n●●')),()=>{
      const choices=new Set(tags),box=n('div',{class:'native-filter-options'});
      box.append(n('p',{class:'muted'},t('Выберите интересующие вас темы','Choose topics you are interested in')));
      for(const tag of [...new Set((state().data.sessions||[]).map(trackTitle))]){const check=input('topic',{type:'checkbox'});check.checked=choices.has(tag);check.addEventListener('change',()=>check.checked?choices.add(tag):choices.delete(tag));box.append(n('label',{},n('span',{},tag),check));}
      const d=dialog(t('Фильтры','Filters'),[box,b(t('Применить','Apply'),()=>{tags=choices;d.close();schedule(main,only,searching,q.value);},'primary wide'),b(t('Сбросить','Reset'),()=>{tags=new Set();d.close();schedule(main,only,searching,q.value);},'link')],'native-bottom-sheet');
    },'native-filter-button');
    main.replaceChildren(...(searching?[n('div',{class:'native-search'},q)]:!only&&rooms.length>1?[location]:[]),list,...(!only&&!searching?[filter,...(available.length>1?[days]:[])]:[]));draw();if(searching)q.focus();
  }
  async function picker(meeting=false) {
    env.enter(meeting?'meetings':'inbox');const main=screen(meeting?'meetings':'inbox',meeting?t('Назначить встречу','Arrange a meeting'):t('Новый разговор','New conversation'),()=>render(meeting?'meetings':'inbox'));
    const q=input('person-search',{type:'search',placeholder:t('Поиск','Search'),'aria-label':t('Поиск','Search')}),list=n('div',{class:'native-people-list'});
    const draw=()=>list.replaceChildren(...(state().data.people||[]).filter(p=>!p.archive&&p.id!==state().profile.id&&[p.name,p.company,p.position].join(' ').toLocaleLowerCase().includes(q.value.toLocaleLowerCase())).map(p=>b(n('span',{class:'native-person-row'},avatar(p),n('span',{},n('strong',{},p.name),n('small',{},[p.company,p.position].filter(Boolean).join(' · ')))),safeAction(async()=>{if(meeting)meetingEditor(p);else await env.openChat(await api('/chats','POST',{person_id:p.id}));}),'native-person')));
    q.addEventListener('input',draw);main.append(n('div',{class:'native-search'},q),list);draw();
  }
  let showHidden=false;
  async function inbox(main,ticket) {
    main.className='native-main native-inbox';title(t('Сообщения','Messages'),()=>render('menu'),[action('add',t('Новый разговор','New conversation'),()=>picker(false))]);
    const list=n('div',{class:'native-list-panel'});main.replaceChildren(list);let snapshot='';
    document.querySelector('#app>header h1').addEventListener('contextmenu',e=>{e.preventDefault();showHidden=!showHidden;refresh().catch(report);});
    const draw=rows=>{const key=JSON.stringify(rows)+showHidden;if(key===snapshot)return;snapshot=key;
      list.replaceChildren(...rows.filter(c=>showHidden||!c.hidden).map(chat=>{
        const row=b(n('span',{class:'native-chat-identity'},avatar({name:chat.title,avatar_url:chat.avatar_url}),n('span',{class:'native-chat-copy'},n('span',{class:'native-chat-title'},n('strong',{},chat.title),n('time',{},chat.updated_at?clock(chat.updated_at):'')),n('span',{class:'native-chat-preview'},chat.last_message||t('Начать разговор','Start a conversation')),chat.muted?n('small',{},t('Без уведомлений','Muted')):null),chat.unread?n('span',{class:'native-unread','aria-label':t('Непрочитанные сообщения','Unread messages')}):null),()=>env.openChat(chat),'native-chat-row');
        const patch=async values=>{await api('/chats/'+encodeURIComponent(chat.id)+'/state','PUT',values);await refresh();};
        row.addEventListener('contextmenu',e=>{e.preventDefault();const d=dialog(chat.title,[b(chat.hidden?t('Показать чат','Show chat'):t('Скрыть чат','Hide chat'),safeAction(async()=>{await patch({hidden:!chat.hidden});d.close();})),b(chat.muted?t('Включить уведомления','Enable notifications'):t('Выключить уведомления','Mute notifications'),safeAction(async()=>{await patch({muted:!chat.muted});d.close();}))]);});
        let start,swiped=false;row.addEventListener('click',e=>{if(swiped){e.preventDefault();e.stopImmediatePropagation();swiped=false;}},true);row.addEventListener('pointerdown',e=>{start={x:e.clientX,y:e.clientY};});row.addEventListener('pointerup',safeAction(async e=>{if(!start)return;const dx=e.clientX-start.x,dy=e.clientY-start.y;start=null;if(Math.abs(dx)>row.clientWidth*.35&&Math.abs(dx)>Math.abs(dy)*2){swiped=true;await patch({hidden:true});snackbar(t('Чат скрыт','Chat hidden'),()=>patch({hidden:false}));}}));return row;
      }));
      if(!list.childElementCount)list.append(n('div',{class:'native-empty'},n('p',{},t('У вас пока нет сообщений','You have no messages yet')),b(t('Начать разговор','Start a conversation'),()=>picker(false),'primary')));
    };
    const refresh=async()=>{const rows=[];for(let offset=0;offset<10000;offset+=100){const batch=await api('/chats?limit=100&offset='+offset);rows.push(...batch);if(batch.length<100)break;}if(current(ticket))draw(rows);};
    const saved=await savedRead('/chats?limit=100&offset=0').catch(()=>undefined);if(!current(ticket))return;if(saved)draw(saved);
    try{await refresh();}catch(e){if(!saved)throw e;}
    let startY;main.addEventListener('touchstart',e=>{startY=window.scrollY<=0?e.touches[0].clientY:null;},{passive:true});main.addEventListener('touchend',e=>{if(startY!=null&&e.changedTouches[0].clientY-startY>90)refresh().catch(report);},{passive:true});schedulePoll(refresh,ticket);
  }
  function snackbar(message,undo) {document.querySelector('.native-snackbar')?.remove();let timer;const bar=n('div',{class:'native-snackbar',role:'status'},n('span',{},message),b(t('Отменить','Undo'),safeAction(async()=>{clearTimeout(timer);await undo();bar.remove();}),'link'));document.body.append(bar);timer=setTimeout(()=>bar.remove(),8000);}
  const peer=m=>(state().data.people||[]).find(p=>p.id===(m.owner_id===state().profile.id?m.person_id:m.owner_id))||{id:m.owner_id===state().profile.id?m.person_id:m.owner_id,name:m.participant_name||m.title};
  async function meetings(main,ticket) {
    main.className='native-main native-meetings';title(t('Встречи','Meetings'),()=>render('menu'),[action('add',t('Назначить встречу','Arrange a meeting'),()=>picker(true))]);
    const panel=n('div',{class:'native-list-panel'});main.replaceChildren(panel);let snapshot='';
    const draw=rows=>{const key=JSON.stringify(rows);if(key===snapshot)return;snapshot=key;panel.replaceChildren(b(n('span',{},icon('time'),t('Время для встреч','Meeting availability')),()=>availability(),'native-availability-button'),...rows.map(m=>b(n('span',{class:'native-meeting-row'},avatar(peer(m)),n('span',{class:'native-meeting-copy'},n('strong',{},peer(m).name),n('span',{},m.title),n('small',{},icon('calendar'),date(m.starts_at,{day:'numeric',month:'short'}),icon('clock'),clock(m.starts_at))),n('span',{class:'native-meeting-status','aria-label':statusLabel(m.status)},icon(m.status==='accepted'?'complete':m.status==='pending'?'clock':'close'))),()=>meetingEditor(peer(m),m),'native-meeting-item')));if(!rows.length)panel.append(n('div',{class:'native-empty'},n('p',{},t('У вас пока нет встреч','You have no meetings yet')),b(t('Назначить встречу','Arrange a meeting'),()=>picker(true),'primary')));};
    const refresh=async()=>{const rows=await api('/meetings');if(current(ticket))draw(rows);};
    const saved=await savedRead('/meetings').catch(()=>undefined);if(!current(ticket))return;if(saved)draw(saved);try{await refresh();}catch(e){if(!saved)throw e;}schedulePoll(refresh,ticket);
  }
  function statusLabel(status){return ({pending:t('Ожидает ответа','Pending'),accepted:t('Подтверждена','Accepted'),declined:t('Отклонена','Declined'),cancelled:t('Отменена','Cancelled')})[status]||status;}
  function meetingEditor(person,meeting=null,draft={title:'',time:'',location:''}) {
    env.enter('meetings');const main=screen('meetings',t('Детали встречи','Meeting details'),()=>render('meetings')),panel=n('div',{class:'native-meeting-editor'});
    const subject=input('title',{required:true,maxlength:200,placeholder:t('Тема встречи','Meeting subject')});subject.value=meeting?.title||draft.title;subject.disabled=!!meeting;subject.addEventListener('input',()=>draft.title=subject.value);
    if(meeting){draft.time=meeting.starts_at;draft.location=meeting.location;}
    const reopen=()=>meetingEditor(person,meeting,draft);
    const dateButton=b(n('span',{},icon('clock'),draft.time?date(draft.time,{weekday:'long',day:'numeric',month:'long'})+' · '+clock(draft.time):t('Выбрать дату и время','Select date and time'),n('span',{},'›')),()=>datePicker(draft.time,person.id,value=>{draft.time=value;reopen();},reopen),'native-detail-choice');dateButton.disabled=!!meeting;
    const locationButton=b(n('span',{},icon('pin'),draft.location||t('Выбрать место','Select location'),n('span',{},'›')),()=>locationPicker(draft.location,value=>{draft.location=value;reopen();},reopen),'native-detail-choice');locationButton.disabled=!!meeting;
    const own=meeting?.owner_id===state().profile.id,closed=['cancelled','declined'].includes(meeting?.status);
    const act=b(meeting?closed?statusLabel(meeting.status):own?t('Отменить встречу','Cancel meeting'):meeting.status==='accepted'?t('Отклонить','Decline'):t('Подтвердить','Confirm'):t('Пригласить','Invite'),safeAction(async()=>{
      if(act.disabled)return;if(!meeting&&(!subject.value.trim()||!draft.time||!draft.location)){notice(t('Укажите тему, дату и место встречи','Enter subject, date and location'));return;}act.disabled=true;
      try{if(meeting){if(!confirm(t('Продолжить?','Continue?')))return;await api('/meetings/'+encodeURIComponent(meeting.id),'PATCH',{status:own?'cancelled':meeting.status==='accepted'?'declined':'accepted'});}else await api('/meetings','POST',{person_id:person.id,title:subject.value.trim(),starts_at:new Date(draft.time).toISOString(),location:draft.location});await render('meetings');}finally{act.disabled=closed;}
    }),'primary native-editor-submit');act.disabled=closed;
    panel.append(n('div',{class:'native-meeting-person'},avatar(person,true),n('strong',{},person.name)),field(t('Тема встречи','Meeting subject'),subject),field(t('Дата и время','Date and time'),dateButton),field(t('Место','Location'),locationButton),act);
    if(meeting&&!closed&&!own&&meeting.status==='pending')panel.append(b(t('Дополнительно','More'),()=>{const d=dialog(t('Дополнительно','More'),[b(t('Отклонить','Decline'),safeAction(async()=>{await api('/meetings/'+encodeURIComponent(meeting.id),'PATCH',{status:'declined'});d.close();await render('meetings');})),b(t('Написать','Message'),safeAction(async()=>{d.close();await env.openChat(await api('/chats','POST',{person_id:person.id}));}))]);},'link'));
    main.replaceChildren(panel);
  }
  // A meeting location is always selected from the venue map.  This keeps the
  // saved label tied to an actual searchable point instead of a free-form
  // string that cannot be used for navigation later.
  function locationPicker(selected,done,cancel) { env.selectMeetingPlace(done,cancel); }
  async function datePicker(selected,personId,done,cancel) {
    const ticket=env.enter('meetings');const main=screen('meetings',t('Дата встречи','Meeting date'),cancel),panel=n('div',{class:'native-date-picker'});
    let options={busy:[],available:{}},hide=false;
    const today=localDate(new Date()),available=dates().filter(d=>d>=today);if(!available.length)for(let i=0;i<30;i++){const d=new Date();d.setDate(d.getDate()+i);available.push(localDate(d));}
    let value=selected?new Date(selected):new Date(available[0]+'T09:00:00');if(selected&&!available.includes(localDate(value)))available.push(localDate(value));available.sort();let chosenDay=localDate(value),chosenTime=clock24(value);
    const days=n('div',{class:'native-wheel','aria-label':t('День','Day'),role:'listbox',tabindex:0}),times=n('div',{class:'native-wheel','aria-label':t('Время','Time'),role:'listbox',tabindex:0}),hint=n('p',{class:'native-date-hint',role:'status'});
    const status=iso=>{const time=new Date(iso),end=new Date(+time+1800000);if(time<new Date())return 'busy';if(options.busy.some(x=>time<new Date(x.end)&&end>new Date(x.start)))return 'busy';for(const who of ['self','peer']){const slots=options.available?.[who]||[];if(slots.length&&!slots.some(x=>time>=new Date(x.start)&&end<=new Date(x.end)))return who;}return '';};
    const updateHint=()=>{const s=status(chosenDay+'T'+chosenTime);hint.textContent=s==='busy'?t('На это время уже назначена встреча','A meeting is already scheduled for this time'):s==='self'?t('Это время отмечено вами как недоступное. Вы можете предложить встречу.','You marked this time unavailable. You can still propose a meeting.'):s==='peer'?t('Участник отметил это время как недоступное. Вы можете предложить встречу.','The attendee marked this time unavailable. You can still propose a meeting.'):'';};
    const fillTimes=()=>{const rows=[];for(let min=480;min<1260;min+=30){const time=String(Math.floor(min/60)).padStart(2,'0')+':'+String(min%60).padStart(2,'0'),s=status(chosenDay+'T'+time);if(hide&&s)continue;const el=b(time,()=>{chosenTime=time;select(times,time);updateHint();},s?'unavailable':'');el.dataset.value=time;el.setAttribute('role','option');el.setAttribute('aria-selected',String(time===chosenTime));rows.push(el);}times.replaceChildren(...rows);select(times,chosenTime,false);updateHint();};
    const select=(wheel,value,scroll=true)=>{for(const el of wheel.children){const selected=el.dataset.value===value;el.classList.toggle('selected',selected);el.setAttribute('aria-selected',String(selected));if(selected&&scroll)el.scrollIntoView({block:'center',behavior:'smooth'});}};
    days.append(...available.map(day=>{const el=b(date(day+'T12:00:00',{weekday:'short',day:'numeric',month:'long'}),()=>{chosenDay=day;select(days,day);fillTimes();},'');el.dataset.value=day;el.setAttribute('role','option');return el;}));select(days,chosenDay,false);fillTimes();
    for(const wheel of [days,times]){let timer;wheel.addEventListener('scroll',()=>{clearTimeout(timer);timer=setTimeout(()=>{if(!wheel.isConnected)return;const rect=wheel.getBoundingClientRect(),middle=rect.top+rect.height/2;const nearest=[...wheel.children].reduce((best,el)=>Math.abs(el.getBoundingClientRect().top+el.offsetHeight/2-middle)<Math.abs(best.getBoundingClientRect().top+best.offsetHeight/2-middle)?el:best,wheel.firstElementChild);if(nearest&&nearest.dataset.value!==(wheel===days?chosenDay:chosenTime)){if(wheel===days){chosenDay=nearest.dataset.value;select(days,chosenDay,false);fillTimes();}else{chosenTime=nearest.dataset.value;select(times,chosenTime,false);updateHint();}}},140);},{passive:true});wheel.addEventListener('keydown',e=>{if(!['ArrowUp','ArrowDown'].includes(e.key))return;e.preventDefault();const rows=[...wheel.children],index=rows.findIndex(el=>el.classList.contains('selected')),next=rows[Math.max(0,Math.min(rows.length-1,index+(e.key==='ArrowDown'?1:-1)))];next?.click();});}
    const toggle=b(t('Скрыть недоступное время','Hide unavailable times'),()=>{hide=!hide;toggle.textContent=hide?t('Показать недоступное время','Show unavailable times'):t('Скрыть недоступное время','Hide unavailable times');fillTimes();},'link');
    panel.append(n('div',{class:'native-wheels'},days,times),hint,toggle,b(t('Выбрать','Select'),()=>{const iso=chosenDay+'T'+chosenTime;if(status(iso)==='busy'){notice(t('Выбранное время недоступно','Selected time is unavailable'));return;}done(new Date(iso).toISOString());},'primary native-date-save'));main.replaceChildren(panel);
    requestAnimationFrame(()=>{select(days,chosenDay);select(times,chosenTime);});
    if(personId)try{options=await api('/meeting-options?person_id='+encodeURIComponent(personId));if(current(ticket)){fillTimes();select(times,chosenTime);}}catch(e){if(current(ticket))notice(e.message);}
  }
  const localDate=d=>d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0');
  const clock24=d=>String(d.getHours()).padStart(2,'0')+':'+String(d.getMinutes()).padStart(2,'0');
  async function availability() {
    const ticket=env.enter('meetings'),main=screen('meetings',t('Время для встреч','Meeting availability'),()=>render('meetings'));let intervals=[],day=dates()[0]||localDate(new Date()),loaded=false,pending=false;
    const panel=n('div',{class:'native-availability'}),days=n('div',{class:'native-availability-days'}),list=n('div');
    const save=b(t('Сохранить','Save'),safeAction(async()=>{if(!loaded||pending)return;pending=true;save.disabled=true;try{await api('/meeting-availability','PUT',{items:intervals});await render('meetings');}finally{pending=false;save.disabled=false;}}),'primary native-date-save');save.disabled=true;
    const show=()=>{main.replaceChildren(panel);title(t('Время для встреч','Meeting availability'),()=>render('meetings'));};
    const draw=()=>{days.replaceChildren(...(dates().length?dates():[day]).map(value=>b(n('span',{},date(value+'T12:00:00',{weekday:'short'}),n('strong',{},new Date(value+'T12:00:00').getDate())),()=>{day=value;draw();},value===day?'selected':'')));list.replaceChildren(n('h3',{},t('Время для встреч','Meeting availability')),...intervals.map((item,index)=>localDate(new Date(item.start))===day?b(clock(item.start)+' — '+clock(item.end),()=>{const d=dialog(t('Время для встреч','Meeting availability'),[b(t('Удалить','Delete'),()=>{intervals.splice(index,1);d.close();draw();})]);},'native-interval'):null).filter(Boolean),b(t('Добавить интервал','Add interval'),()=>{if(!loaded)return;datePicker(day+'T09:00:00','',start=>datePicker(new Date(+new Date(start)+1800000).toISOString(),'',end=>{if(new Date(end)<=new Date(start)){notice(t('Время окончания должно быть позже начала','End time must follow start time'));show();return;}intervals.push({start,end});draw();show();},show),show);},'link'));};
    panel.append(n('h3',{},t('Дни события','Event days')),days,list,save);draw();show();
    try{const result=await api('/meeting-availability');if(current(ticket)){intervals=result.items||[];loaded=true;save.disabled=false;draw();}}catch(e){if(current(ticket))report(e);}
  }
  function settings(main) {
    main.className='native-main native-settings';title(t('Настройки','Settings'),()=>render('menu'));
    const legal=id=>{env.legal(()=>render('settings'),id);};
    main.replaceChildren(b(n('span',{},t('О приложении','About the app'),n('span',{},'›')),()=>dialog(t('Sirius Biotech Summit 2027','Sirius Biotech Summit 2027'),[n('p',{},t('Ваш персональный гид по событию','Your personal event guide'))]),'native-settings-row'),b(n('span',{},t('Язык','Language'),n('span',{},state().language==='ru'?'Русский':'English')),()=>{dialog(t('Язык','Language'),[env.languageControl()]);},'native-settings-row'),b(n('span',{},t('Уведомления','Notifications'),n('span',{},'›')),()=>dialog(t('Уведомления','Notifications'),[n('p',{},t('Разрешения уведомлений управляются в настройках браузера или установленного приложения.','Notification permissions are managed in your browser or installed app settings.'))]),'native-settings-row'),b(n('span',{},t('Согласие на обработку персональных данных','Consent to personal data processing'),n('span',{},'›')),()=>legal('privacy'),'native-settings-row'),b(n('span',{},t('Политика конфиденциальности','Privacy Policy'),n('span',{},'›')),()=>legal('privacy'),'native-settings-row'),b(t('Выйти','Sign out'),safeAction(env.logout),'native-settings-row danger'));
  }
  return {schedule,inbox,meetings,meetingEditor,picker,settings,title,icon,action,availability,profile:profileView};
}

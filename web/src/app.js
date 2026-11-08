import {makeDismissible} from './dialog-dismiss.js';
import {createNativeScenes} from './native-scenes.js';
import {sessionScene} from './session-scenes.js';
import {preparePhoto, SOURCE_LIMIT} from './photo-upload.js';
import {messageRoute, allMessages, mergeMessagePage, recordMessage, messagePage} from './chat-cache.js';
const root = document.querySelector('#app');
const languageKey = 'sbs-language';
let language = localStorage.getItem(languageKey) || 'ru';
let token = localStorage.getItem('sbs-token') || '';
let profile = null, data = {}, favorites = [], config = {}, page = 'menu', activeChat = null, resumeChat = null;
let generation = 0, pollTimer, cacheScope = '', offlineRead = false, venueDispose = null;
const consentVersion = 'sbs-draft-2026-11-06-v1';
let nativeDetail = false;
let currentRoute = () => entryView('events'), legalReturn = null, onboardingStep = 0;
const agreements = [false, false];
const imagePool = new Map();
const privatePhotoUrls = new Set();
const publicArtwork = ['sirius_logo.png', 'sirius_icon.jpg', 'sirius_theme.webp', 'back.svg', 'calendar.svg', 'pin.svg', 'search.svg', 'auth-pin.svg', 'attendee-fallback.svg', 'auth-welcome.svg', 'onboarding-1.svg', 'onboarding-2.svg', 'onboarding-3.svg', ...['schedule', 'people', 'inbox', 'meetings', 'news', 'partners', 'venue', 'profile', 'favorites'].map(id => id + '.svg')];
async function warmArtwork() {
  const urls = new Set([...publicArtwork.map(name => '/app/assets/' + name), ...(config.sections || []).map(s => s.icon).filter(url => typeof url === 'string' && url.startsWith('/app/assets/'))]);
  await Promise.all([...urls].map(async url => {
    const image = new Image(); image.src = url; imagePool.set(url, image);
    try { await image.decode(); } catch { /* Optional artwork must not block entry. */ }
  }));
}
let screenAnimation, outgoingScreen;
function replaceScreen(...children) {
  for (const url of privatePhotoUrls) URL.revokeObjectURL(url); privatePhotoUrls.clear();
  screenAnimation?.cancel(); outgoingScreen?.remove();
  const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
  let snapshot;
  if (!reduced && root.childElementCount && root.animate) {
    const rect = root.getBoundingClientRect();
    snapshot = root.cloneNode(true); snapshot.removeAttribute('id');
    snapshot.querySelectorAll('[id]').forEach(el => el.removeAttribute('id'));
    snapshot.className = 'screen-snapshot'; snapshot.inert = true; snapshot.setAttribute('aria-hidden', 'true');
    Object.assign(snapshot.style, {left: rect.left + 'px', top: rect.top + 'px', width: rect.width + 'px', height: rect.height + 'px'});
    document.body.append(snapshot); outgoingScreen = snapshot;
  }
  root.replaceChildren(...children);
  refreshInstallButtons();
  if (!reduced && root.animate) {
    screenAnimation = root.animate([{opacity: 0}, {opacity: 1}], {duration: 300, easing: 'ease-in-out'});
    if (snapshot) { const fade = snapshot.animate([{opacity: 1}, {opacity: 0}], {duration: 300, easing: 'ease-in-out'}); fade.onfinish = () => snapshot.remove(); }
  }
}
const t = (ru, en) => language === 'ru' ? ru : en;
const text = value => value == null ? '' : String(value);
const enc = encodeURIComponent;
const labels = {
  menu: ['Меню', 'Menu'], schedule: ['Программа', 'Schedule'], people: ['Участники', 'People'],
  inbox: ['Сообщения', 'Inbox'], meetings: ['Встречи', 'Meetings'], news: ['Новости', 'News'],
  partners: ['Партнеры', 'Partners'], venue: ['Площадка', 'Venue'], profile: ['Профиль', 'Profile'],
  favorites: ['Моя программа', 'My programme'], settings: ['Настройки','Settings'], event: ['О событии', 'Event'], help: ['На главный экран', 'Add to Home Screen']
};
const icons = {schedule: '▦', people: '♧', inbox: '✉', meetings: '◷', news: '≡', partners: '◇', venue: '⌖', profile: '○', favorites: '☆', event: 'ⓘ', help: '↗'};
function node(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (key.startsWith('on')) el.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key === 'class') el.className = value;
    else if (key === 'text') el.textContent = value;
    else if (value != null && value !== false) el.setAttribute(key, value === true ? '' : value);
  }
  for (const child of children.flat()) if (child != null) el.append(child instanceof Node ? child : document.createTextNode(text(child)));
  return el;
}
const button = (label, fn, cls = '') => node('button', {type: 'button', class: cls, onclick: fn}, label);
const heading = value => node('h2', {}, value);
const empty = () => node('p', {class: 'empty'}, t('Пока ничего нет', 'Nothing here yet'));
const field = (label, input) => node('label', {class: 'field'}, node('span', {}, label), input);
const input = (name, attrs = {}) => node('input', {name, ...attrs});
function notice(message) {
  const el = document.querySelector('#notice'); el.textContent = message; el.hidden = false;
  clearTimeout(notice.timer); notice.timer = setTimeout(() => { el.hidden = true; }, 6000);
}
function report(error) { notice(error.message || t('Не удалось выполнить запрос', 'Request failed')); }
function safeAction(fn) { return async event => { try { await fn(event); } catch (error) { report(error); } }; }
async function database() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open('sbs-private-v1', 1);
    req.onupgradeneeded = () => req.result.createObjectStore('reads');
    req.onsuccess = () => resolve(req.result); req.onerror = () => reject(req.error);
  });
}
async function cached(key, value, scope = cacheScope) {
  const db = await database();
  try { return await new Promise((resolve, reject) => {
    if (value !== undefined && scope !== cacheScope) { resolve(undefined); return; }
    const tx = db.transaction('reads', value === undefined ? 'readonly' : 'readwrite');
    const store = tx.objectStore('reads');
    const req = value === undefined ? store.get(scope + key) : store.put({value, savedAt: Date.now(), bytes: value instanceof Blob ? value.size : new TextEncoder().encode(JSON.stringify(value)).length}, scope + key);
    if (value !== undefined) {
      const all = store.getAll(), keys = store.getAllKeys();
      all.onsuccess = () => { keys.onsuccess = () => {
        const entries = all.result.map((entry, i) => ({...entry, key: keys.result[i]})).sort((a, b) => (b.savedAt || 0) - (a.savedAt || 0));
        let count = 0, bytes = 0;
        for (const entry of entries) {
          if (!entry.savedAt || (!(entry.value instanceof Blob) && Date.now() - entry.savedAt > 7 * 86400000) || (count >= 256 && !(entry.value instanceof Blob)) || bytes + (entry.bytes || 0) > 64 * 1024 * 1024) store.delete(entry.key);
          else { count++; bytes += entry.bytes || 0; }
        }
      }; };
    }
    tx.oncomplete = () => { const entry = req.result; resolve(value === undefined ? (entry?.savedAt && (entry.value instanceof Blob || Date.now() - entry.savedAt <= 7 * 86400000) ? entry.value : undefined) : undefined); }; tx.onerror = () => reject(tx.error);
  }); } finally { db.close(); }
}
async function wipeCache() {
  try { const db = await database(); await new Promise((resolve, reject) => { const tx = db.transaction('reads', 'readwrite'); tx.objectStore('reads').clear(); tx.oncomplete = resolve; tx.onerror = reject; }); db.close(); } catch { /* Storage may be unavailable in private browsing. */ }
}
async function scopeFor(value) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(location.origin + ':' + value));
  return Array.from(new Uint8Array(digest), x => x.toString(16).padStart(2, '0')).join('') + ':';
}
async function savedRead(path, scope = cacheScope) {
  const route = messageRoute(path);
  if (route) {
    const saved = await cached(route.key, undefined, scope);
    const rows = messagePage(saved, route.offset, route.limit);
    if (rows !== undefined) return rows;
  }
  return cached(path, undefined, scope);
}
let chatCacheWrites = Promise.resolve();
function updateChatCache(path, method, value, scope) {
  const task = chatCacheWrites.then(() => scope === cacheScope ? writeChatCache(path, method, value, scope) : undefined);
  chatCacheWrites = task.catch(() => {}); return task;
}
async function writeChatCache(path, method, value, scope) {
  const route = messageRoute(path);
  if (route) {
    const saved = await cached(route.key, undefined, scope);
    const next = method === 'GET' ? mergeMessagePage(saved, value, route.offset, route.limit) : recordMessage(saved, value);
    if (next) await cached(route.key, next, scope);
  }
  if (method === 'GET' || scope !== cacheScope) return;
  const db = await database();
  try { await new Promise((resolve,reject) => {
    const tx = db.transaction('reads','readwrite'), store = tx.objectStore('reads'), req = store.openCursor();
    req.onsuccess = () => {
      const cursor = req.result; if (!cursor) return;
      const key = String(cursor.key), entry = cursor.value;
      if (scope !== cacheScope) { tx.abort(); return; }
      if (key.startsWith(scope + '/chats?') && Array.isArray(entry.value)) {
        const chatId = route?.path.split('/')[2] || (/^\/chats\/[^/]+\/state$/.test(path) ? path.split('/')[2] : null);
        entry.value = entry.value.map(chat => chat.id === chatId ? (path.endsWith('/state') ? {...chat,...value} : {...chat,last_message:value.text,updated_at:value.created_at}) : chat);
        if (path === '/chats' && !/[?&]offset=[1-9]/.test(key) && entry.value.length < Number(new URL(key.slice(scope.length),location.origin).searchParams.get('limit') || 50) && !entry.value.some(chat => chat.id === value.id)) entry.value.push(value);
        cursor.update(entry);
      } else if (!path.startsWith('/chats') && key.startsWith(scope + '/' + path.split('/')[1])) cursor.delete();
      cursor.continue();
    };
    tx.oncomplete = resolve; tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error);
  }); } finally { db.close(); }
}
async function api(path, method = 'GET', body) {
  const authRequest = path.startsWith('/auth/');
  const usedToken = authRequest ? '' : token, scope = authRequest ? '' : cacheScope;
  let response;
  try {
    const multipart = body instanceof FormData;
    response = await fetch('/api/v1' + path, {method, cache: 'no-store', headers: {...(!multipart ? {'Content-Type': 'application/json'} : {}), ...(usedToken ? {Authorization: 'Bearer ' + usedToken} : {})}, body: body === undefined ? undefined : multipart ? body : JSON.stringify(body), signal: AbortSignal.timeout(18000)});
  } catch (error) {
    if (method === 'GET' && scope && token === usedToken) {
      const value = await savedRead(path, scope).catch(() => undefined);
      if (value !== undefined) { offlineRead = true; updateConnection(); return value; }
    }
    throw new Error(t('Отсутствует подключение к интернету. Пожалуйста, повторите попытку позже', 'No internet connection. Please try again later.'));
  }
  if (response.status === 401 && usedToken && usedToken === token) { await logout(); throw new Error(t('Войдите снова: сессия истекла', 'Session expired. Please sign in again.')); }
  const value = await response.json().catch(() => ({}));
  if (!response.ok) {
    const message = response.status === 429 ? t('Достигнут лимит попыток. Пожалуйста, попробуйте позже.','Too many attempts. Please try again later.')
      : response.status === 401 && path === '/auth/login' ? t('Некорректный ID','Incorrect ID')
      : path === '/auth/verify-pin' && [400,401,422].includes(response.status) ? t('Некорректный код подтверждения.','Incorrect verification code.')
      : authRequest && [404,410].includes(response.status) ? t('Время сессии истекло. Начните регистрацию сначала.','Session is expired. Please start registration over.')
      : response.status === 422 ? t('Проверьте введенные данные','Check the information you entered')
      : t('Произошла ошибка. Повторите попытку позже.','An error occurred. Please try again later.');
    const error = new Error(message); error.status = response.status; throw error;
  }
  offlineRead = false; updateConnection();
  if (scope && token === usedToken) {
    if (method === 'GET') await cached(path, value, scope).catch(() => {});
    if (!authRequest) await updateChatCache(path, method, value, scope).catch(() => {});
    if(method==='PUT' && /^\/sessions\/[^/]+\/rating$/.test(path)) await cached(path,value,scope).catch(()=>{});
  }
  return value;
}
function updateConnection() {
  const el = document.querySelector('#connection');
  if (el) { el.textContent = ''; el.hidden = true; }
}
async function logout() {
  document.querySelectorAll('.native-snackbar,dialog.native-dialog').forEach(el=>el.remove());
  resetRecovery();
  if (venueDispose) { venueDispose(); venueDispose = null; }
  token = ''; cacheScope = ''; profile = null; data = {}; favorites = []; activeChat = null;
  localStorage.removeItem('sbs-token'); clearTimeout(pollTimer); generation++;
  await wipeCache(); loginView();
}
function languageControl() {
  const select = node('select', {'aria-label': 'Language / Язык'}, node('option', {value: 'ru'}, 'RU'), node('option', {value: 'en'}, 'EN'));
  select.value = language;
  select.addEventListener('change', safeAction(async () => { language = select.value; localStorage.setItem(languageKey, language); document.documentElement.lang = language; await currentRoute(); }));
  return select;
}
let entryPage='events';
const recoveryDraft={surname:'',items:[],challengeId:'',contact:'',pending:false};
function resetRecovery(){Object.assign(recoveryDraft,{surname:'',items:[],challengeId:'',contact:'',pending:false});}
const recoveryNotFound=()=>t('Мы не нашли вас в списке участников. Пожалуйста, обратитесь к организатору.','We cannot find you in the attendee list. Please consult an event manager to gain access.');
const eventTitle=()=>t('Сириус Биотех 2027','Sirius Biotech 2027');
const eventDate=()=>t('20–22 мая 2027','20–22 May 2027');
const eventPlace=()=>t('Федеральная территория «Сириус», Олимпийский пр. 1','Sirius Federal Territory, 1 Olimpiysky Avenue');
function assetImage(name,cls,alt=''){return node('img',{src:'/app/assets/'+name,class:cls,alt,decoding:'sync',loading:'eager'});}
function entryHeader(title,back){const control=button(assetImage('back.svg','toolbar-icon'),back,'back');control.setAttribute('aria-label',t('Назад','Back'));return node('header',{class:'android-toolbar'},control,node('h1',{},title));}
function loginView(){entryView('events');}
function entryView(id){
 if(!id.startsWith('recovery-'))resetRecovery();
 recoveryDraft.pending=false;
 currentRoute=()=>entryView(id);
 if(venueDispose){venueDispose();venueDispose=null;}
 activeChat=null;
 entryPage=id;clearTimeout(pollTimer);generation++;
 const view=node('section',{class:'android-entry '+id});
 if(id==='events'){
  const search=input('eventSearch',{type:'search',placeholder:t('Введите название события или ID','Search by event name or ID'),'aria-label':t('Поиск мероприятия','Search events')});
  const card=button(node('div',{class:'event-card'},node('div',{class:'event-card-header'},assetImage('sirius_icon.jpg','event-icon'),node('strong',{},eventTitle())),node('div',{class:'event-fact'},assetImage('calendar.svg','fact-icon'),node('span',{},eventDate())),node('div',{class:'event-fact'},assetImage('pin.svg','fact-icon'),node('span',{},eventPlace()))),()=>entryView('event'),'event-card-button');
  search.addEventListener('input',()=>{card.hidden=![eventTitle(),'local2027','сириус biotech'].join(' ').toLowerCase().includes(search.value.toLowerCase());});
  view.append(node('div',{class:'events-brand'},assetImage('menu-logo-2027.png','events-wordmark')),node('div',{class:'events-search'},search),node('div',{class:'events-section'},t('ПРЕДСТОЯЩИЕ СОБЫТИЯ','UPCOMING EVENTS')),card);
 }else if(id==='event'){
  view.append(entryHeader('',()=>entryView('events')),node('div',{class:'event-expanded-title'},assetImage('sirius_icon.jpg','event-icon'),node('strong',{},eventTitle())));
  const card=node('div',{class:'android-detail-card'},node('div',{class:'event-fact'},assetImage('calendar.svg','fact-icon'),node('span',{},eventDate())),node('div',{class:'event-fact'},assetImage('pin.svg','fact-icon'),node('span',{},eventPlace())),node('p',{class:'android-description'},t('Приложение «Сириус Биотех 2027» — ваш персональный гид по событию. Следите за программой, ориентируйтесь на площадке, находите исследователей и партнёров, назначайте встречи и общайтесь прямо в приложении.','Sirius Biotech 2027 is your personal event guide. Browse the programme, find your way around the venue, discover researchers and partners, arrange meetings and chat in the app.')),button(t('Открыть','Open'),()=>token?safeAction(load)():entryView('registration'),'primary android-bottom'));
  view.append(card);
 }else if(id==='language'){
  const select=languageControl();
  view.append(node('div',{class:'android-auth-card language-card'},assetImage('menu-logo-2027.png','events-wordmark'),heading('Язык / Language'),select,button(t('Продолжить','Continue'),()=>{localStorage.setItem(languageKey,language);entryView('onboarding');},'primary')));
 }else if(id==='onboarding'){
  const slides=[
   [t('Общайтесь с участниками','Connect with other attendees'),t('Найдите потенциальных партнеров и коллег, общайтесь и обменивайтесь контактами.','Interact and exchange contacts with potential partners and colleagues.')],
   [t('Будьте активны','Make the most out of it'),t('Делитесь впечатлениями, задавайте вопросы выступающим и участвуйте в голосованиях.','Share impressions, post questions for speakers and participate in polls.')],
   [t('Следите за новостями','Stay on top of the news'),t('Будьте в курсе всех новостей мероприятия и получайте важные уведомления от организаторов.','Receive important notifications from event organizers.')]
  ];
  const next=button(t('Продолжить','Continue'),()=>{if(onboardingStep<2){onboardingStep++;entryView('onboarding');}else if(agreements.every(Boolean)){localStorage.setItem('sbs-consent',JSON.stringify({version:consentVersion,acceptedAt:new Date().toISOString()}));entryView('events');}},'primary');
  const card=node('div',{class:'android-auth-card onboarding-card'},assetImage('onboarding-'+(onboardingStep+1)+'.svg','android-auth-art'),heading(slides[onboardingStep][0]),node('p',{class:'muted'},slides[onboardingStep][1]));
  card.append(node('div',{class:'onboarding-dots'},...[0,1,2].map(i=>{const dot=button(String(i+1),()=>{onboardingStep=i;entryView('onboarding');},i===onboardingStep?'selected':'');dot.setAttribute('aria-label',t('Страница ','Page ')+(i+1));dot.setAttribute('aria-current',i===onboardingStep?'step':'false');return dot;})));
  if(onboardingStep===2){
   const rows=[t('Я принимаю условия использования и политику конфиденциальности','I accept the Terms & Conditions and Privacy Policy'),t('Я даю согласие на передачу персональных данных организаторам события','I consent to sharing personal data with the event organizers')];
   rows.forEach((label,i)=>{const check=input('agreement'+i,{type:'checkbox'});check.checked=agreements[i];check.addEventListener('change',()=>{agreements[i]=check.checked;next.disabled=!agreements.every(Boolean);});card.append(node('label',{class:'agreement'},check,node('span',{},label)),button(t('Читать','Read'),()=>{legalReturn=()=>entryView('onboarding');entryView(i===0?'terms':'privacy');},'link'));});
   next.disabled=!agreements.every(Boolean);
  }
  card.append(next);view.append(card);
 }else if(id==='terms'||id==='privacy'){
  const back=legalReturn||(()=>entryView('onboarding'));
  const title=id==='terms'?t('Условия использования','Terms & Conditions'):t('Политика конфиденциальности и согласие','Privacy Policy and Consent');
  const body=id==='terms'?t('Приложение предназначено для просмотра программы, общения и организации встреч. Персональный ID выдает организатор. Не передавайте свой ID другим лицам. Для отправки сообщений и изменения данных требуется интернет.','This app provides the programme, conversations and meeting requests. Attendee IDs are issued by the organizer. Keep your ID private. Messaging and changes require internet access.'):t('Данные профиля и выбранная фотография видны участникам. Сообщения доступны только собеседникам. На устройстве сохраняются язык, согласия, данные входа и ранее загруженная информация для просмотра без интернета. При выходе сохраненные данные учетной записи удаляются с устройства. Для удаления профиля и связанных данных обратитесь к организатору.','Profile information and your chosen photo are visible to attendees. Messages are available only to conversation members. Language, consent, sign-in information and previously loaded content are saved on your device for offline reading. Signing out removes saved account data from the device. Contact the organizer to request deletion of your profile and related data.');
  view.append(entryHeader(title,back),node('article',{class:'legal-document'},heading(title),node('p',{},body),button(t('Назад','Back'),back)));
 }else if(id==='recovery-surname'||id==='recovery-attendees'||id==='recovery-pin'){
  if(id==='recovery-pin'&&!recoveryDraft.challengeId)return entryView('recovery-surname');
  const back=()=>entryView(id==='recovery-pin'?'recovery-attendees':id==='recovery-attendees'?'recovery-surname':'registration');
  view.append(entryHeader(t('Регистрация','Registration'),back),id==='recovery-pin'?loginForm(true):id==='recovery-attendees'?recoveryAttendees():recoveryForm());
 }else if(id==='registration'||id==='welcome'){
  // Original auth_by_pin_hint / auth_by_pin_signin resources; this is not a delivery acknowledgement.
  const exit=button(assetImage('exit.svg','toolbar-icon'),()=>entryView('event'),'auth-exit');exit.setAttribute('aria-label',t('Закрыть','Close'));
  view.append(node('header',{class:'android-toolbar registration-toolbar'},exit),node('div',{class:'android-auth-card registration-card'},node('div',{class:'registration-content'},assetImage('auth-pin.svg','android-auth-art'),heading(t('Регистрация','Registration')),node('p',{class:'muted'},t('Мы высылаем приглашение с регистрационным ID по e-mail и СМС','We send registration ID via SMS and e-mail'))),button(t('Войти','Sign In'),()=>entryView('pin'),'primary android-bottom')));
 }else{
  view.append(entryHeader(t('Регистрация','Registration'),()=>entryView('registration')),loginForm());
 }
 replaceScreen(view);window.scrollTo(0,0);
}
function recoveryForm(){
 const ticket=generation;
 const card=node('form',{class:'android-auth-card recovery-card'});
 const value=input('last_name',{type:'text',placeholder:' ',autocomplete:'family-name',required:true,maxlength:100});
 value.value=recoveryDraft.surname;
 const submit=node('button',{type:'submit',class:'primary android-bottom'},t('Продолжить','Continue'));
 const update=()=>{submit.disabled=recoveryDraft.pending||!value.value.trim();};
 value.addEventListener('input',()=>{recoveryDraft.surname=value.value;update();});update();
 card.append(field(t('Фамилия','Surname'),value),submit);
 card.addEventListener('submit',async event=>{
  event.preventDefault();
  if(recoveryDraft.pending||!value.value.trim()||!card.reportValidity())return;
  recoveryDraft.surname=value.value.trim();recoveryDraft.pending=true;value.disabled=true;update();
  try{
   const result=await api('/auth/search','POST',{last_name:recoveryDraft.surname});
   if(ticket!==generation)return;
   if(!Array.isArray(result.items)||!result.items.length)throw new Error(recoveryNotFound());
   recoveryDraft.items=result.items;recoveryDraft.challengeId='';recoveryDraft.contact='';
   entryView('recovery-attendees');
  }catch(error){if(ticket===generation)report(error);}
  finally{if(ticket===generation){recoveryDraft.pending=false;value.disabled=false;update();}}
 });
 return card;
}
function recoveryAttendees(){
 const ticket=generation;
 const card=node('div',{class:'android-auth-card recovery-attendees-card'});
 const rows=[];
 for(const item of recoveryDraft.items){
  const identity=node('span',{class:'recovery-identity'},node('span',{class:'recovery-name'},item.name),item.contact?node('span',{class:'recovery-contact'},item.contact):null);
  const row=button([assetImage('attendee-fallback.svg','recovery-avatar'),identity],async()=>{
   if(recoveryDraft.pending)return;
   recoveryDraft.pending=true;rows.forEach(el=>{el.disabled=true;});card.setAttribute('aria-busy','true');
   try{
    const result=await api('/auth/send-pin','POST',{selection_id:item.selection_id});
    if(ticket!==generation)return;
    if(result.status!=='success'||!result.challenge_id||!result.contact)throw new Error(t('Произошла ошибка. Повторите попытку позже.','An error occurred. Please try again later.'));
    recoveryDraft.challengeId=result.challenge_id;recoveryDraft.contact=result.contact;
    entryView('recovery-pin');
   }catch(error){if(ticket===generation){report(error);if([404,410].includes(error.status))entryView('recovery-surname');}}
   finally{if(ticket===generation){recoveryDraft.pending=false;rows.forEach(el=>{el.disabled=false;});card.removeAttribute('aria-busy');}}
  },'recovery-attendee');
  rows.push(row);card.append(row);
 }
 return card;
}
function loginForm(challengeMode=false){
 const ticket=generation,challengeId=challengeMode?recoveryDraft.challengeId:'';
 const code=input('code',{type:'password',inputmode:'numeric',pattern:'[0-9]{6}',minlength:'6',maxlength:'6',autocomplete:'one-time-code',required:true,'aria-label':t('Введите ID','Enter ID'),class:'android-pin',placeholder:'••••••'});
 const submit=node('button',{type:'submit',class:'primary android-pin-submit'},t('Войти','Sign in'));
 const form=node('form',{class:'android-auth-card pin-card'},assetImage('auth-pin.svg','android-auth-art'),heading(t('Введите ID','Enter ID')));
 if(challengeMode)form.append(node('p',{class:'muted pin-delivery'},t('Код был отправлен на','Your code has been sent to')+' '+recoveryDraft.contact));
 form.append(code,submit);
 if(!challengeMode)form.append(button(t('Не знаете свой ID?','Forgot ID?'),()=>{resetRecovery();entryView('recovery-surname');},'android-bottom forgot-id'));
 let pending=false,last='';
 const send=async()=>{
  if(pending||ticket!==generation||!/^\d{6}$/.test(code.value))return;
  pending=true;submit.disabled=true;code.disabled=true;
  try{
   const result=await api(challengeMode?'/auth/verify-pin':'/auth/login','POST',challengeMode?{challenge_id:challengeId,code:code.value}:{code:code.value});
   if(ticket!==generation)return;
   const nextScope=await scopeFor(result.access_token);
   if(ticket!==generation)return;
   token=result.access_token;profile=result.profile;cacheScope=nextScope;localStorage.setItem('sbs-token',token);resetRecovery();await load(ticket);
  }catch(error){if(ticket===generation){report(error);if(challengeMode&&[404,410].includes(error.status))entryView('recovery-surname');}}
  finally{if(ticket===generation){pending=false;submit.disabled=false;code.disabled=false;code.focus();}}
 };
 form.addEventListener('submit',e=>{e.preventDefault();send();});
 code.addEventListener('input',()=>{code.value=code.value.replace(/[^0-9]/g,'').slice(0,6);if(code.value.length===6&&code.value!==last){last=code.value;send();}else if(code.value.length<6)last='';});
 return form;
}
async function load(entryTicket) {
  if (!cacheScope) cacheScope = await scopeFor(token);
  const usedToken = token;
  const ticket=typeof entryTicket==='number'?entryTicket:generation;
  const saved=await Promise.all(['/profile','/bootstrap','/favorites'].map(path=>savedRead(path).catch(()=>undefined)));
  if (!usedToken || usedToken!==token || ticket!==generation) return;
  let displayed=false,menuTicket;
  if(saved.every(Boolean)){profile=saved[0];data=saved[1];favorites=saved[2].items||[];await render('menu');displayed=true;menuTicket=generation;}
  try{
    const [loadedProfile,loadedData,loadedFavorites]=await Promise.all([api('/profile'),api('/bootstrap'),api('/favorites')]);
    if(!usedToken||usedToken!==token||(!displayed&&ticket!==generation)||(displayed&&menuTicket!==generation))return;
    profile=loadedProfile;data=loadedData;favorites=loadedFavorites.items;
    if(!displayed || (page==='menu' && menuTicket===generation))await render('menu');
  }catch(error){if(!displayed)throw error;}
}
function titleFor(id) { return labels[id] ? t(...labels[id]) : id; }
function shell(id) {
  const header = node('header', {class: id === 'menu' ? 'native-menu-header' : 'native-header'}, ...(id === 'menu' ? [native.action('settings',t('Настройки','Settings'),()=>render('settings'))] : [native.action('back',t('Назад','Back'),()=>render('menu')),node('h1',{},titleFor(id))]));
  const main = node('main', {id: 'content'});
  replaceScreen(header,node('div',{id:'connection',class:'connection',role:'status',hidden:true}),main);updateConnection();return main;
}
async function render(id) {
  currentRoute = () => render(id);
  if (!token) return loginView();
  if (venueDispose) { venueDispose(); venueDispose = null; }
  nativeDetail = false; page = id; activeChat = null; const ticket = ++generation; clearTimeout(pollTimer);
  const main = shell(id); main.append(node('p', {class: 'loading'}, t('Загрузка…', 'Loading…')));
  try {
    if (id === 'menu') menu(main);
    else if (id === 'schedule') native.schedule(main);
    else if (id === 'people') peopleView(main);
    else if (id === 'profile') profileView(main);
    else if (id === 'settings') native.settings(main);
    else if (id === 'news') editorialNews(main);
    else if (id === 'partners') editorialPartners(main);
    else if (id === 'favorites') native.schedule(main,true);
    else if (id === 'inbox') await native.inbox(main, ticket);
    else if (id === 'meetings') await native.meetings(main, ticket);
    else if (id === 'venue') { main.replaceChildren(); const {renderVenue} = await import('./venue.js'); if (ticket === generation) { const cleanup = await renderVenue(main, {language, onBack: () => render('menu')}); if (ticket === generation) venueDispose = typeof cleanup === 'function' ? cleanup : cleanup?.dispose?.bind(cleanup); else if (typeof cleanup === 'function') cleanup(); else cleanup?.dispose?.(); } }
    else if (id === 'help') { main.replaceChildren(); installHelp(main); }
    else { main.replaceChildren(heading(config.title || config.name || 'Сириус.Биотех'), node('p', {class: 'prose'}, t('Саммит разработчиков лекарственных препаратов. Программа, участники и общение — в одном приложении.', 'Drug development summit. Programme, attendees and conversations in one app.'))); }
  } catch (error) { if (ticket === generation && token) main.replaceChildren(node('p', {class: 'error'}, error.message), button(t('Повторить', 'Retry'), () => render(id))); }
}
function menu(main) {
  const defaults = ['schedule', 'people', 'inbox', 'meetings', 'news', 'partners', 'venue', 'favorites', 'profile'];
  const aliases = {programme: 'schedule', program: 'schedule', sessions: 'schedule', participants: 'people', messages: 'inbox', chats: 'inbox', posts: 'news', map: 'venue', about: 'event'};
  const sections = config.sections?.length ? config.sections : defaults.map(id => ({id}));
  const brand = node('img', {class: 'brand-logo', decoding: 'sync', loading: 'eager', src: '/app/assets/menu-logo-2027.png', alt: config.name || 'Сириус.Биотех'});
  brand.addEventListener('error', () => brand.remove(), {once: true});
  const grid = node('div', {class: 'menu-grid'});
  for (const section of sections) {
    const id = aliases[section.id] || section.id;
    const icon = typeof section.icon === 'string' && section.icon.startsWith('/app/assets/') ? node('img', {src: section.icon, alt: '', decoding: 'sync', loading: 'eager'}) : publicArtwork.includes(id + '.svg') ? assetImage(id + '.svg', 'section-icon') : node('span', {}, icons[id] || '◇');
    grid.append(button(node('span', {class: 'menu-item'}, node('span', {class: 'menu-icon', 'aria-hidden': 'true'}, icon), node('span', {}, (language === 'ru' ? section.labelRu : section.labelEn) || titleFor(id))), () => render(id)));
  }
  const events=button(node('span',{class:'menu-item'},node('span',{class:'menu-icon','aria-hidden':'true'},assetImage('exit.svg','section-icon')),node('span',{},t('Мероприятия','Events'))),()=>entryView('events'),'android-menu-events');
  main.replaceChildren(node('div',{class:'android-menu-brand'},brand),grid,events);
}
function isFavorite(kind, id) { return favorites.some(f => f.kind === kind && f.item_id === id); }
function favoriteButton(kind, id) {
  const b = button(isFavorite(kind, id) ? '★' : '☆', safeAction(async () => {
    b.disabled = true;
    try { const items = isFavorite(kind, id) ? favorites.filter(f => !(f.kind === kind && f.item_id === id)) : [...favorites, {kind, item_id: id}]; const result = await api('/favorites', 'PUT', {items}); favorites = result.items; b.textContent = isFavorite(kind, id) ? '★' : '☆'; b.setAttribute('aria-pressed', String(isFavorite(kind, id))); b.dispatchEvent(new Event('favorite-changed')); }
    finally { b.disabled = false; }
  }), 'favorite');
  b.setAttribute('aria-label', t('Избранное', 'Favorite')); b.setAttribute('aria-pressed', String(isFavorite(kind, id))); return b;
}
function contentCard(item, kind) {
  const card = node('article', {class: 'card'}, node('div', {class: 'row'}, node('div', {}, node('p', {class: 'meta'}, [item.date, [item.start_time, item.end_time].filter(Boolean).join('–'), item.room].filter(Boolean).join(' · ')), node('h3', {}, item.title)), favoriteButton(kind, item.id)));
  if (kind === 'sessions') card.append(button(t('Подробнее', 'Details'), () => sessionView(item), 'link'));
  else { card.append(node('p', {class: 'prose'}, item.description || '')); const url = safeUrl(item.url); if (url) card.append(node('a', {href: url, target: '_blank', rel: 'noopener noreferrer'}, t('Открыть сайт ↗', 'Open website ↗'))); }
  return card;
}
function safeUrl(value) { try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) ? url.href : null; } catch { return null; } }
function listContent(main, kind, onlyFavorites = false) {
  const search = input('search', {type: 'search', placeholder: t('Поиск', 'Search'), 'aria-label': t('Поиск', 'Search')});
  const dates = node('select', {'aria-label': t('Дата', 'Date')}, node('option', {value: ''}, t('Все даты', 'All dates')), ...[...new Set((data[kind] || []).map(s => s.date).filter(Boolean))].sort().map(date => node('option', {value: date}, date)));
  const list = node('div', {class: 'cards'});
  const refresh = () => {
    const items = (data[kind] || []).filter(item => (!onlyFavorites || isFavorite(kind, item.id)) && (!dates.value || item.date === dates.value) && [item.title, item.description, item.room].join(' ').toLocaleLowerCase().includes(search.value.toLocaleLowerCase())).sort((a, b) => (text(a.date) + text(a.start_time)).localeCompare(text(b.date) + text(b.start_time)));
    list.replaceChildren(...(items.length ? items.map(item => contentCard(item, kind)) : [empty()]));
  };
  search.addEventListener('input', refresh); dates.addEventListener('change', refresh);
  main.replaceChildren(node('div', {class: 'filters'}, search, dates), list); refresh();
}
function editorialNews(main) {
  main.classList.add('mss-news');native.title(t('Новости','News'),()=>render('menu'));
  const rows=(data.posts||[]).map(post=>{const body=node('p',{class:'mss-news-text collapsed'},[post.title,post.description].filter(Boolean).join('\n\n'));const more=button(t('Читать далее','Read more'),()=>{body.classList.remove('collapsed');more.remove();},'mss-news-more');const url=safeUrl(post.url),link=url?node('a',{href:url,target:'_blank',rel:'noopener noreferrer'},t('Открыть сайт ↗','Open website ↗')):null;const wrapper=node('article',{class:'mss-news-post'},node('div',{class:'mss-news-author'},node('span',{class:'avatar mss-news-brand'},assetImage('news-avatar.png')), node('span',{},node('strong',{},'Сириус Биотех 2027'),post.date?node('small',{},post.date):null)),body,more,link);requestAnimationFrame(()=>{if(body.scrollHeight>body.clientHeight+2)body.classList.add('collapsed');else more.remove();});return wrapper;});
  main.replaceChildren(...(rows.length?rows:[empty()]));
}
function editorialPartners(main) {
  main.classList.add('mss-partners');native.title(t('Партнёры','Partners'),()=>render('menu'));
  const rows=(data.partners||[]).map(item=>{const url=safeUrl(item.url);const content=node('span',{},node('strong',{},item.title),item.description?node('span',{},item.description):null);return url?node('a',{class:'mss-partner-row',href:url,target:'_blank',rel:'noopener noreferrer'},content,node('span',{'aria-hidden':'true'},'›')):node('div',{class:'mss-partner-row'},content);});main.replaceChildren(...(rows.length?rows:[empty()]));
}
async function sessionView(item,fromPage) {
  const returnPage=fromPage||(page==='favorites'?'favorites':'schedule');
  nativeDetail=true; currentRoute=()=>sessionView(item,returnPage); page='schedule'; const ticket=++generation; clearTimeout(pollTimer); const main=shell('schedule');
  sessionScene({item,main,n:node,b:button,t,api,title:native.title,action:native.action,back:()=>render(returnPage),toSession:()=>sessionView(item,returnPage),isCurrent:()=>ticket===generation,report,notice,favoriteButton,avatar,people:data.people||[],personView,venue:()=>render('venue'),language});
}
function avatar(person, large = false) {
  const initials = (person.name || '?').trim().split(/\s+/).slice(0, 2).map(word => word[0]).join('').toUpperCase();
  const holder = node('span', {class: 'avatar' + (large ? ' avatar-large' : ''), role: 'img', 'aria-label': person.name || t('Фото профиля', 'Profile photo')}, initials);
  const url = safeUrl(person.avatar_url);
  if (url) {
    const photo = node('img', {src: url, alt: '', decoding: 'async', loading: large ? 'eager' : 'lazy', referrerpolicy: 'no-referrer'});
    photo.addEventListener('error', () => photo.remove(), {once: true}); holder.append(photo);
  } else if (typeof person.avatar_url === 'string' && person.avatar_url.startsWith('/') && !person.avatar_url.startsWith('//')) {
    const photo = node('img', {src: person.avatar_url, alt: '', decoding: 'async', loading: large ? 'eager' : 'lazy'});
    photo.addEventListener('error', () => photo.remove(), {once: true}); holder.append(photo);
  }
  return holder;
}
function syncProfile(value) {
  profile = value;
  const index = (data.people || []).findIndex(person => person.id === value.id);
  if (index >= 0) data.people[index] = value;
}
function peopleView(main, onlyFavorites = false) {
  main.classList.add('mss-attendees');
  let active=onlyFavorites?'favorites':'all';
  const search=input('people',{type:'search',placeholder:t('Поиск участников','Search attendees'),'aria-label':t('Поиск участников','Search attendees'),hidden:true});
  const results=node('div',{class:'mss-attendees-list'}),tabs=node('div',{class:'mss-attendee-tabs',role:'tablist'});
  const tab=(key,ru,en)=>button(t(ru,en),()=>{active=key;draw();},'mss-attendee-tab');
  const all=tab('all','Участники','Attendees'),saved=tab('favorites','Избранные','Favorites');tabs.append(all,saved);
  native.title(t('Участники','Attendees'),()=>render('menu'),[native.action('search',t('Поиск','Search'),()=>{search.hidden=!search.hidden;if(!search.hidden)search.focus();else{search.value='';draw();}})]);
  function draw(){
    for(const [control,key] of [[all,'all'],[saved,'favorites']]){control.setAttribute('role','tab');control.setAttribute('aria-selected',String(active===key));}
    const q=search.value.toLocaleLowerCase();const rows=(data.people||[]).filter(p=>(active!=='favorites'||isFavorite('people',p.id))&&[p.name,p.company,p.position].join(' ').toLocaleLowerCase().includes(q));
    results.replaceChildren(...(rows.length?rows.map(p=>{const star=p.id===profile.id?null:favoriteButton('people',p.id);if(star)star.addEventListener('favorite-changed',()=>{if(active==='favorites')draw();});return node('article',{class:'mss-attendee-item'},button(node('span',{class:'mss-attendee-identity'},avatar(p),node('span',{class:'mss-attendee-copy'},node('strong',{},p.name),node('small',{},[p.position,p.company].filter(Boolean).join(' · ')))),()=>personView(p),'mss-attendee-open'),star);}):[node('p',{class:'mss-attendee-empty'},active==='favorites'?t('Нет избранных участников','No favorite attendees'):t('Участники не найдены','No attendees found'))]));
  }
  search.addEventListener('input',draw);main.replaceChildren(tabs,search,results);draw();
}
function personView(person) {
  nativeDetail=true;currentRoute=()=>personView(person);page='people';activeChat=null;const ticket=++generation;clearTimeout(pollTimer);const main=shell('people');
  const isSelf=person.id===profile.id,canTalk=!person.archive&&!isSelf;
  const speakerSessions=(data.sessions||[]).filter(s=>{const words=(person.name||'').toLocaleLowerCase().replaceAll('ё','е').split(/\s+/).filter(x=>x.length>1),speaker=(s.speaker||'').toLocaleLowerCase().replaceAll('ё','е');return words.length>=2&&words.every(x=>speaker.includes(x));});
  const tabs=[['info',t('Информация','Information')],...(speakerSessions.length?[['sessions',t('Сессии','Sessions')]]:[]),...(canTalk?[['messages',t('Сообщения','Messages')],['meetings',t('Встречи','Meetings')]]:[])];
  let active='info';const tabbar=node('div',{class:'mss-person-tabs',role:'tablist'}),content=node('div',{class:'mss-person-content'});
  const header=node('div',{class:'mss-person-header'},avatar(person,true),node('h2',{},person.name));
  if(!isSelf){const menu=button('⋮',()=>{const d=node('dialog',{class:'mss-person-menu'});const save=button(isFavorite('people',person.id)?t('Убрать из избранного','Remove favorite'):t('В избранное','Favorite'),()=>{const star=favoriteButton('people',person.id);star.addEventListener('favorite-changed',()=>d.close());star.click();},'link');d.append(save);document.body.append(d);makeDismissible(d);d.addEventListener('close',()=>d.remove(),{once:true});d.showModal();},'mss-person-more');menu.setAttribute('aria-label',t('Дополнительно','More'));header.append(menu);}
  native.title(t('Участник','Attendee'),()=>render('people'));main.classList.add('mss-person');main.replaceChildren(node('div',{class:'mss-person-card'},header,tabbar,content));
  const section=(label,value)=>value?node('div',{class:'mss-person-field'},node('small',{},label),node('span',{},value)):null;
  async function draw(){
    tabbar.replaceChildren(...tabs.map(([id,label])=>{const b=button(label,()=>{active=id;draw();},'mss-person-tab');b.setAttribute('role','tab');b.setAttribute('aria-selected',String(id===active));return b;}));
    if(active==='info'){
      const url=safeUrl(person.source_url||person.url),parts=[node('h3',{class:'mss-person-group'},t('Информация','Information')),section(t('Организация','Organization'),person.company),section(t('Должность','Position'),person.position),section(t('О себе','About'),person.bio&&person.bio!==person.position?person.bio:''),url?node('a',{class:'mss-person-field',href:url,target:'_blank',rel:'noopener noreferrer'},url):null];
      if(!isSelf){const contact=button(t('Сохранить контакт','Save contact'),()=>{const escape=v=>String(v||'').replaceAll('\\','\\\\').replaceAll('\n','\\n').replaceAll(',','\\,').replaceAll(';','\\;');const card=['BEGIN:VCARD','VERSION:3.0','FN:'+escape(person.name),'ORG:'+escape(person.company),'TITLE:'+escape(person.position),'END:VCARD',''].join('\r\n');const url=URL.createObjectURL(new Blob([card],{type:'text/vcard;charset=utf-8'}));const link=node('a',{href:url,download:'contact.vcf'});document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);},'mss-contact');parts.push(contact);}
      content.replaceChildren(...parts.filter(Boolean));
    } else if(active==='sessions') content.replaceChildren(...speakerSessions.map(item=>button(node('span',{},node('small',{},[item.date,item.start_time,item.room].filter(Boolean).join(' · ')),node('strong',{},item.title)),()=>sessionView(item),'mss-person-session')));
    else if(active==='messages'){content.replaceChildren(node('p',{class:'mss-attendee-empty'},t('Откройте переписку с участником','Open conversation with attendee')),button(t('Написать','Message'),safeAction(async()=>{const chat=await api('/chats','POST',{person_id:person.id});if(ticket===generation)await openChat(chat);}), 'primary'));}
    else if(active==='meetings'){
      content.replaceChildren(node('p',{class:'mss-attendee-empty'},t('Загрузка…','Loading…')));
      try{const rows=(await api('/meetings')).filter(m=>m.person_id===person.id||m.owner_id===person.id);if(ticket!==generation||active!=='meetings')return;content.replaceChildren(...rows.map(m=>node('div',{class:'mss-person-session'},node('strong',{},m.title),node('small',{},[formatDate(m.starts_at),m.location].filter(Boolean).join(' · ')))),button(t('Назначить встречу','Request meeting'),()=>native.meetingEditor(person),'primary'));}catch(error){if(ticket===generation)content.replaceChildren(node('p',{class:'error'},error.message),button(t('Повторить','Retry'),draw));}
    }
  }
  draw();
}
function profileView(main) {
  main.classList.add('profile-screen');
  const label = t('Изменить фото', 'Change photo');
  const file = input('avatar', {type: 'file', accept: 'image/jpeg,image/png,image/webp', hidden: true, 'aria-label': label});
  const portrait = button(avatar(profile, true), () => file.click(), 'profile-portrait');
  portrait.setAttribute('aria-label', label);
  const change = button(label, () => file.click(), 'primary');
  const status = node('p', {class: 'profile-upload-status', role: 'status', 'aria-live': 'polite'});
  const upload = node('section', {class: 'avatar-upload'}, file, status);
  native.title(t('Профиль','Profile'),()=>render('menu'),[portrait]);
  let busy = false;
  file.addEventListener('change', safeAction(async () => {
    const selected = file.files[0];
    file.value = '';
    if (!selected || busy) return;
    if (!['image/jpeg', 'image/png', 'image/webp'].includes(selected.type) || selected.size > SOURCE_LIMIT) throw new Error(t('Выберите JPEG, PNG или WebP размером до 20 МБ', 'Choose a JPEG, PNG or WebP image up to 20 MB'));
    busy = true; change.disabled = portrait.disabled = file.disabled = true;
    upload.setAttribute('aria-busy', 'true');
    const usedToken = token, ticket = generation;
    try {
      const {cropAvatar} = await import('./avatar-crop.js');
      const compressed = await preparePhoto(selected, {language});
      const cropped = await cropAvatar(compressed, {language});
      if (!cropped || token !== usedToken || ticket !== generation) return;
      status.textContent = t('Сохранение фото…', 'Saving photo…');
      const body = new FormData(); body.append('file', cropped, 'avatar.jpg');
      const updated = await api('/profile/avatar', 'POST', body);
      if (token !== usedToken) return;
      syncProfile(updated);
      portrait.replaceChildren(avatar(profile, true));updateCompletion();
      if (ticket === generation) notice(t('Фото сохранено', 'Photo saved'));
    } catch (error) {
      if (token === usedToken && ticket === generation) report(error);
    } finally {
      busy = false; change.disabled = portrait.disabled = file.disabled = false;
      upload.setAttribute('aria-busy', 'false'); status.textContent = '';
    }
  }));
  const form = node('form');
  for (const [key, ru, en, max] of [['name', 'Имя', 'Name', 160], ['company', 'Организация', 'Organization', 200], ['position', 'Должность', 'Position', 200], ['bio', 'О себе', 'About', 4000]]) {
    const el = key === 'bio' ? node('textarea', {name: key, maxlength: max, rows: '2'}) : input(key, {maxlength: max, required: key === 'name'}); el.value = profile[key] || ''; el.placeholder=t(ru,en);form.append(field(t(ru, en), el));
  }
  const submit = node('button', {type: 'submit', class: 'primary'}, t('Сохранить', 'Save')); form.append(submit);
  form.addEventListener('submit', safeAction(async event => { event.preventDefault(); if (submit.disabled) return; submit.disabled = true; const usedToken = token; try { const updated = await api('/profile', 'PATCH', Object.fromEntries(new FormData(form))); if (token !== usedToken) return; syncProfile(updated); notice(t('Профиль сохранен', 'Profile saved')); } finally { submit.disabled = false; } }));
  const completion=node('div',{class:'native-profile-completion'});const updateCompletion=()=>{const values=['name','company','position','bio'].map(k=>form.elements[k].value.trim());const percent=Math.round((values.filter(Boolean).length+(profile.avatar_url?1:0))*20);completion.textContent=t(`Профиль заполнен на ${percent}%`,`The profile is ${percent}% complete`);completion.style.setProperty('--completion',percent+'%');};form.addEventListener('input',updateCompletion);updateCompletion();
  main.replaceChildren(completion,upload,node('div',{class:'native-profile-group'},assetImage('profile.svg','native-profile-symbol'),node('p',{},t('Профиль','Profile'))),form);
}
function schedulePoll(fn, ticket) {
  clearTimeout(pollTimer);
  if (document.visibilityState !== 'visible' || !token || ticket !== generation) return;
  pollTimer = setTimeout(async () => { if (document.visibilityState !== 'visible' || ticket !== generation || !token) return; try { await fn(); } catch { /* Keep the visible conversation intact during transient failures. */ } schedulePoll(fn, ticket); }, 12000);
}
async function openChat(chat) {
  nativeDetail = true; currentRoute = () => openChat(chat); page = 'inbox'; activeChat = chat; const ticket = ++generation; clearTimeout(pollTimer); const main = shell('inbox');
  main.classList.add('native-chat');native.title(chat.title,()=>render('inbox'));
  const list = node('div', {class: 'messages', 'aria-live': 'polite'}), composer = node('textarea', {rows: '2', maxlength: '10000', required: true, placeholder: t('Сообщение', 'Message'), 'aria-label': t('Сообщение', 'Message')});
  let loaded = 0, pending = null, selectedPhoto = null, messageSnapshot = '', markedRead = 0, markingRead = false; const seen = new Map();
  const photoCache = new Map(), messageNodes = new Map(), dayNodes = new Map();
  async function showPhoto(img, attachmentId) {
    const usedToken=token, scope=cacheScope, key='@chat-photo:'+chat.id+':'+attachmentId;
    let value=photoCache.get(key);
    if(!value) {
      value=await cached(key,undefined,scope).catch(()=>undefined);
      if(!(value instanceof Blob)) {
        const response=await fetch('/api/v1/chats/'+enc(chat.id)+'/attachments/'+enc(attachmentId),{headers:{Authorization:'Bearer '+usedToken},cache:'no-store'});
        if(!response.ok) throw new Error(t('Не удалось загрузить фото','Unable to load photo'));
        value=await response.blob();
        if(value.type!=='image/jpeg'||value.size>4*1024*1024) throw new Error(t('Некорректное фото','Invalid image'));
        if(scope===cacheScope && usedToken===token) await cached(key,value,scope).catch(()=>{});
      }
      photoCache.set(key,value);
    }
    if(ticket!==generation || usedToken!==token) return;
    const url=URL.createObjectURL(value); privatePhotoUrls.add(url);img.src=url;
  }
  const add = rows => {
    for (const message of rows) seen.set(String(message.id), message);
    const ordered=[...seen.values()].sort((a,b)=>Number(a.id)-Number(b.id)),snapshot=JSON.stringify(ordered);if(snapshot===messageSnapshot)return;messageSnapshot=snapshot;
    const oldHeight=list.scrollHeight,oldScroll=window.scrollY,nearBottom=window.innerHeight+window.scrollY>=document.documentElement.scrollHeight-120;let day='';const children=[];
    for(const message of ordered){
      const date=new Date(message.created_at),dayKey=date.toLocaleDateString(),id=String(message.id);
      if(day!==dayKey){day=dayKey;if(!dayNodes.has(dayKey))dayNodes.set(dayKey,node('div',{class:'native-chat-day'},date.toLocaleDateString(language==='ru'?'ru-RU':'en-GB',{day:'numeric',month:'long'})));children.push(dayNodes.get(dayKey));}
      const fingerprint=JSON.stringify(message);let previous=messageNodes.get(id);
      if(!previous||previous.fingerprint!==fingerprint){
        const image=message.attachment_id?node('img',{class:'chat-photo',alt:t('Фото из сообщения','Message photo'),tabindex:'0'}):null;
        if(image){showPhoto(image,message.attachment_id).catch(()=>{image.alt=t('Фото недоступно','Photo unavailable');});image.addEventListener('click',()=>{if(!image.src)return;const dialog=node('dialog',{class:'chat-photo-viewer'},node('img',{src:image.src,alt:image.alt}));document.body.append(dialog);makeDismissible(dialog);dialog.addEventListener('close',()=>dialog.remove(),{once:true});dialog.showModal();});}
        previous={fingerprint,element:node('article',{class:'bubble'+(message.sender_id===profile.id?' mine':'')},image,message.text?node('p',{},message.text):null,node('time',{datetime:message.created_at},date.toLocaleTimeString(language==='ru'?'ru-RU':'en-GB',{hour:'2-digit',minute:'2-digit'})))};messageNodes.set(id,previous);
      }
      children.push(previous.element);
    }
    children.forEach((child,index)=>{if(list.children[index]!==child)list.insertBefore(child,list.children[index]||null);});while(list.children.length>children.length)list.lastElementChild.remove();
    if(nearBottom)requestAnimationFrame(()=>list.lastElementChild?.scrollIntoView({block:'nearest'}));else window.scrollTo(0,oldScroll);

  };
  const more = button(t('Загрузить следующие сообщения', 'Load next messages'), safeAction(async () => { const rows = await api('/chats/' + enc(chat.id) + '/messages?limit=50&offset=' + loaded); if (ticket !== generation) return; loaded += rows.length; add(rows); more.hidden = rows.length < 50; }));
  const refresh = async () => { let offset = 0, rows; do { rows = await api('/chats/' + enc(chat.id) + '/messages?limit=100&offset=' + offset); if (ticket !== generation) return; add(rows); offset += rows.length; } while (rows.length === 100 && offset < loaded + 100); loaded = Math.max(loaded, offset); more.hidden = rows.length < 100; if(seen.size&&!offlineRead&&!markingRead&&document.visibilityState==='visible'){const last=Math.max(...[...seen.keys()].map(Number));if(last>markedRead){markingRead=true;api('/chats/'+enc(chat.id)+'/state','PUT',{last_read:last}).then(()=>{markedRead=last;}).catch(()=>{}).finally(()=>{markingRead=false;});}} };
  const submit = node('button', {type: 'submit', class: 'primary'}, t('Отправить', 'Send'));
  composer.required = false;
  const file=input('chatPhoto',{type:'file',accept:'image/jpeg,image/png,image/webp',hidden:true,'aria-label':t('Прикрепить фото','Attach photo')});
  const attach=button('📎',()=>file.click(),'chat-attach');attach.setAttribute('aria-label',t('Прикрепить фото','Attach photo'));
  const attachmentStatus=node('span',{class:'chat-attachment-status',role:'status'});
  file.addEventListener('change',safeAction(async()=>{const chosen=file.files[0];file.value='';if(!chosen)return;attach.disabled=submit.disabled=true;try{selectedPhoto=await preparePhoto(chosen,{language});pending=null;attachmentStatus.textContent=t('Фото прикреплено','Photo attached');}finally{attach.disabled=submit.disabled=false;}}));
  const form = node('form', {class: 'composer'}, file, attach, composer, submit, attachmentStatus);
  form.addEventListener('submit', safeAction(async event => { event.preventDefault(); if (!composer.value.trim() && !selectedPhoto) return; submit.disabled = attach.disabled = true;
    if (!pending || pending.text !== composer.value || pending.file !== selectedPhoto) pending = {text: composer.value, file:selectedPhoto, client_message_id: crypto.randomUUID()};
    try {
      if(pending.file && !pending.attachment_id){const body=new FormData();body.append('file',pending.file,'photo');const upload=await api('/chats/'+enc(chat.id)+'/attachments','POST',body);pending.attachment_id=upload.attachment_id;}
      const sent=await api('/chats/'+enc(chat.id)+'/messages','POST',{text:pending.text,client_message_id:pending.client_message_id,...(pending.attachment_id?{attachment_id:pending.attachment_id}:{})});
      if (ticket !== generation) return; if (composer.value === pending.text) composer.value = ''; if(selectedPhoto===pending.file)selectedPhoto=null;pending = null;attachmentStatus.textContent=''; add([sent]); await refresh(); list.lastElementChild?.scrollIntoView({block: 'nearest'});
    } finally { submit.disabled = attach.disabled = false; }
  }));
  main.replaceChildren(list, more, form);
  submit.replaceChildren(native.icon('send'));submit.setAttribute('aria-label',t('Отправить','Send'));
  const saved = await cached(messageRoute('/chats/' + enc(chat.id) + '/messages').key).catch(() => undefined);
  if (ticket !== generation) return;
  if (saved) { add(allMessages(saved)); loaded = saved.items.length; more.hidden = saved.complete; }
  try { await refresh(); } catch (error) { if (ticket === generation) report(error); }
  resumeChat = async () => { if (ticket !== generation) return; try { await refresh(); } finally { schedulePoll(refresh, ticket); } };
  schedulePoll(refresh, ticket);
}
function formatDate(value) { const date = new Date(value); return Number.isNaN(date.getTime()) ? text(value) : date.toLocaleString(language === 'ru' ? 'ru-RU' : 'en-GB', {dateStyle: 'medium', timeStyle: 'short'}); }
let appInstalled = false;
const standaloneQuery = matchMedia('(display-mode: standalone)');
function isStandalone() { return appInstalled || navigator.standalone === true || standaloneQuery.matches; }
function isAppleMobile() {
  if (/Android/i.test(navigator.userAgent)) return false;
  if (/iPhone|iPad|iPod/i.test(navigator.userAgent)) return true;
  // Desktop-mode iPadOS exposes Safari's standalone property; touch alone is not enough.
  return /Macintosh/i.test(navigator.userAgent) && navigator.platform === 'MacIntel'
    && navigator.maxTouchPoints > 1 && typeof navigator.standalone === 'boolean';
}
function refreshInstallButtons() {
  root.querySelectorAll('.install-button').forEach(el => el.remove());
  if (!isAppleMobile() || isStandalone()) return;
  const toolbar = root.querySelector('header');
  if (!toolbar) return;
  const control = button(t('Установить', 'Install'), () => {
    if (!isAppleMobile() || isStandalone()) return;
    showInstallInstructions();
  }, 'install-button');
  control.setAttribute('aria-label', t('Добавить на главный экран', 'Add to Home Screen'));
  if(toolbar.querySelector('.auth-exit'))toolbar.insertBefore(control,toolbar.querySelector('.auth-exit'));
  else toolbar.append(control);
}
function installHelp(container) {
  const apple = isAppleMobile();
  container.append(node('section', {class: 'install-instructions'}, heading(t('На главный экран', 'Add to Home Screen')),
    node('p', {}, apple ? t('В Safari нажмите «Поделиться»', 'In Safari tap Share') : t('В меню браузера выберите «Установить приложение» или «Добавить на главный экран».', 'In the browser menu choose Install app or Add to Home Screen.')),
    ...(apple ? [node('span', {class: 'share-icon', 'aria-hidden': 'true'}, '↑'), node('p', {}, t('Затем «На экран Домой» → «Добавить».', 'Then Add to Home Screen → Add.'))] : [])));
}
function showInstallInstructions() {
  if (document.querySelector('.install-dialog')) return;
  const dialog = node('dialog', {class: 'install-dialog', 'aria-label': t('Установка приложения', 'Install app')});
  installHelp(dialog);
  dialog.append(button(t('Понятно', 'Got it'), () => dialog.close(), 'primary wide'));
  dialog.addEventListener('close', () => dialog.remove(), {once: true});
  makeDismissible(dialog);document.body.append(dialog); dialog.showModal();
}
window.addEventListener('appinstalled', () => { appInstalled = true; document.querySelector('.install-dialog')?.close(); refreshInstallButtons(); });
standaloneQuery.addEventListener('change', () => {
  if (isStandalone()) document.querySelector('.install-dialog')?.close();
  refreshInstallButtons();
});
document.addEventListener('visibilitychange', () => { clearTimeout(pollTimer); if (document.visibilityState === 'visible' && token && (!nativeDetail || activeChat) && document.querySelector('#content') && ['inbox', 'meetings'].includes(page)) { if (activeChat && resumeChat) resumeChat().catch(report); else render(page); } });
window.addEventListener('storage', event => { if (event.key === 'sbs-token' && event.newValue !== token) logout().catch(report); });
window.addEventListener('offline', () => notice(t('Отсутствует подключение к интернету. Пожалуйста, повторите попытку позже', 'No internet connection. Please try again later.')));
let reconnectTimer, reconnectPending = false;
async function reconnect() {
  if (!token || reconnectPending || document.visibilityState !== 'visible') return;
  reconnectPending = true; const owner = token, route = page, detail = nativeDetail;
  try {
    const [p, d, f] = await Promise.all([api('/profile'), api('/bootstrap'), api('/favorites')]);
    if (owner !== token || offlineRead) return;
    profile = p; data = d; favorites = f.items;
    if (!detail && route === page && ['menu','schedule','people','news','partners','favorites'].includes(route)) await render(route);
    else if (route === page && activeChat && resumeChat) await resumeChat();
    else if (!detail && route === page && ['inbox','meetings'].includes(route)) await render(route);
  } catch { /* Retry while offline without interrupting drafts. */ }
  finally { reconnectPending = false; }
}
window.addEventListener('online', () => { reconnect().catch(report); });
reconnectTimer = setInterval(() => { if (offlineRead) reconnect().catch(report); }, 15000);
async function start() {
  document.documentElement.lang = language;
  try { config=JSON.parse(localStorage.getItem('sbs-public-config')||'{}'); } catch { config={}; }
  const updateConfig=fetch('/app/shared.json',{cache:'no-cache'}).then(async response=>{if(response.ok){const fresh=await response.json();config=fresh;try{localStorage.setItem('sbs-public-config',JSON.stringify(fresh));}catch{}}}).catch(()=>{});
  await Promise.race([updateConfig,new Promise(resolve=>setTimeout(resolve,250))]);
  document.title = config.title || config.name || 'Sirius.Biotech';
  const colorNames = {background: '--background', surface: '--surface', text: '--text', muted: '--muted', accent: '--accent', line: '--line'};
  const declarations = Object.entries(config.colors || {}).filter(([key, value]) => colorNames[key] && /^#[0-9a-f]{3,8}$/i.test(value)).map(([key, value]) => colorNames[key] + ':' + value).join(';');
  if (declarations) { const sheet = [...document.styleSheets].find(s => s.href && new URL(s.href).origin === location.origin); if (sheet) sheet.insertRule(':root{' + declarations + '}', sheet.cssRules.length); }
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('/app/sw.js', {scope: '/app/'}).catch(() => {});
  warmArtwork().catch(()=>{});
  let accepted = false;
  try { accepted = JSON.parse(localStorage.getItem('sbs-consent') || 'null')?.version === consentVersion; } catch { /* Old or damaged consent requires review. */ }
  if (!localStorage.getItem(languageKey)) return entryView('language');
  if (!accepted) return entryView('onboarding');
  if (token) { try { await load(); } catch (error) { if (token) { const main = shell('menu'); main.replaceChildren(node('p', {class: 'error'}, error.message), button(t('Повторить', 'Retry'), () => load().catch(report)), button(t('Выйти', 'Sign out'), safeAction(logout))); } } }
  else loginView();
}
async function finishLaunchSplash() {
  const splash = document.getElementById('launch-splash');
  if (!splash) return;
  try { await splash.querySelector('img').decode(); } catch { /* Continue if artwork is unavailable. */ }
  await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  if(!matchMedia('(prefers-reduced-motion: reduce)').matches && splash.animate) await new Promise(resolve=>{const fade=splash.animate([{opacity:1},{opacity:0}],{duration:300,easing:'ease-in-out'});fade.onfinish=resolve;fade.oncancel=resolve;});
  splash.remove();
}
const native=createNativeScenes({node,button,t,api,savedRead,avatar,field,input,report,notice,render,shell,sessionView,favoriteButton,isFavorite,formatDate,profileView,schedulePoll,safeAction,languageControl,logout,openChat,refreshInstallButtons,
  state:()=>({language,token,profile,data,generation}),
  enter:id=>{nativeDetail=true;page=id;activeChat=null;clearTimeout(pollTimer);return ++generation;},
  selectMeetingPlace:async(done,cancel)=>{nativeDetail=true;page='venue';const ticket=++generation;clearTimeout(pollTimer);const main=shell('venue');const finish=place=>{if(ticket!==generation)return;venueDispose?.();venueDispose=null;const value=[place.title,place.sub].filter(Boolean).join(' · ').slice(0,300);if(value)done(value);else cancel();};const back=()=>{if(ticket!==generation)return;venueDispose?.();venueDispose=null;cancel();};const {renderVenue}=await import('./venue.js');if(ticket!==generation)return;const dispose=await renderVenue(main,{language,onBack:back,onPickPlace:finish});if(ticket!==generation)dispose?.();else venueDispose=dispose;},
  legal:(back,id)=>{legalReturn=back;entryView(id);}
});
start().catch(report).finally(finishLaunchSplash);

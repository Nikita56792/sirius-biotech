'use strict';
const $ = id => document.getElementById(id);
let csrf = sessionStorage.getItem('sbs_csrf') || '';
let kind = 'sessions', editing = null, editorKind = '', feedbackOffset = 0, generation = 0, profile = null, records = [], sessions = [];
const sectionCache=new Map();let latestRevision=null,revisionTimer,loadingSections=0;
const cacheChannel=typeof BroadcastChannel==='function'?new BroadcastChannel('sbs-admin-updates'):null;
const sections = {
  sessions: ['Программа', 'Сессии, время и залы вашего события.', 'Добавить сессию'],
  people: ['Участники', 'Регистрация участников', 'Зарегистрировать участника'],
  posts: ['Новости', 'Важные сообщения для участников.', 'Добавить новость'],
  partners: ['Партнёры', 'Организации, которые поддерживают событие.', 'Добавить партнёра'],
  questions: ['Вопросы', 'Обращения участников к сессиям программы.'],
  ratings: ['Оценки', 'Отзывы участников о сессиях программы.'],
  recovery: ['Участники', 'Помощь со входом']
};
const labels = {DRAFT:'Черновик',PUBLISHED:'Опубликовано',ARCHIVE2026:'Архив · 2026',ADAPTED2027:'Программа · 2027',pending:'Ожидает ответа',answered:'Отвечен',hidden:'Скрыт',attendee:'Участник',organizer:'Организатор',admin:'Администратор'};
const feedback = () => ['questions','ratings'].includes(kind);
const endpoint = (section = kind) => '/admin/api/' + (section === 'people' ? 'participants' : section);
const pathId = value => encodeURIComponent(value);
function el(tag, text, className) { const node = document.createElement(tag); if (text !== undefined) node.textContent = text; if (className) node.className = className; return node; }
function status(text, error = false) { $('status').textContent = text; $('status').classList.toggle('error', error); }
function hideSecret() { $('secret').textContent = ''; $('secret-panel').hidden = true; }
function signedOut() {
  sectionCache.clear();latestRevision=null;clearTimeout(revisionTimer);
  csrf = ''; profile = null; sessionStorage.removeItem('sbs_csrf'); generation++;
  hideSecret(); records = []; sessions = []; $('records').replaceChildren(); $('editor').close(); $('fields').replaceChildren();
  $('workspace').hidden = true; $('logout').hidden = true; $('login').hidden = false; $('read-only-indicator').hidden=true;
}
async function api(path, method = 'GET', body, snapshot = false) {
  const usedCsrf=csrf;
  let res;
  try { res = await fetch(path, {method, cache:'no-store', credentials:'same-origin', headers:{...(body instanceof FormData ? {} : {'Content-Type':'application/json'}),'X-CSRF-Token':csrf}, body:body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body)}); }
  catch { throw new Error('Проверьте интернет-соединение и повторите попытку.'); }
  let data;
  try { data = await res.json(); } catch { throw new Error('Не удалось получить ответ. Попробуйте ещё раз.'); }
  if (!res.ok) {
    if (res.status === 401 && path !== '/admin/login') { signedOut(); $('login-error').textContent = 'Сессия завершена. Войдите снова.'; }
    const messages = {401:'Код не подошёл. Проверьте его и попробуйте снова.',403:'Недостаточно прав для этого действия. Проверьте доступ организатора.',404:'Запись больше не доступна. Обновите раздел.',409: typeof data.detail === 'string' ? data.detail : 'Действие недоступно для этой записи.',422: typeof data.detail === 'string' && /[А-Яа-я]/.test(data.detail) ? data.detail : 'Проверьте заполнение и длину полей.',429:'Слишком много попыток. Подождите и попробуйте позже.'};
    throw new Error(messages[res.status] || 'Не удалось выполнить действие. Повторите попытку.');
  }
  if(usedCsrf!==csrf)throw new Error('Сессия изменилась.');
  if(method!=='GET' && path.startsWith('/admin/api/') && !path.endsWith('/code/view') && !path.endsWith('/participant-avatar')){sectionCache.clear();latestRevision=null;cacheChannel?.postMessage({type:'changed'});}
  return snapshot?{data,revision:res.headers.get('X-SBS-Revision')}:data;
}
function showWorkspace(user) { profile = user; $('login').hidden = true; $('workspace').hidden = false; $('logout').hidden = false; $('read-only-indicator').hidden=user.role!=='viewer'; document.body.classList.toggle('read-only',user.role==='viewer'); }
async function action(target, work) { target.disabled = true; try { await work(); } catch (error) { status(error.message, true); } finally { target.disabled = false; } }
function button(text, work, className = 'quiet') { const node = el('button', text, className); node.type = 'button'; node.onclick = () => { if(profile?.role==='viewer' && !['Изменить','Создать копию','Повторить'].includes(text)){status('Доступен только просмотр.',true);return;} return action(node, work); }; return node; }
$('login-form').onsubmit = async event => {
  event.preventDefault(); const submit = event.target.querySelector('button'); submit.disabled = true; $('login-error').textContent = '';
  try { const data = await api('/admin/login','POST',{code:$('code').value}); csrf = data.csrf_token; sessionStorage.setItem('sbs_csrf',csrf); $('code').value = ''; showWorkspace(data.profile); await load();scheduleRevisionCheck(); }
  catch (error) { $('login-error').textContent = error.message; } finally { submit.disabled = false; }
};
$('logout').onclick = () => action($('logout'), async () => { await api('/admin/logout','POST',{}); signedOut();cacheChannel?.postMessage({type:'logout'});status(''); $('code').focus(); });
$('hide-secret').onclick = hideSecret;
function oneTime(code) { $('secret').textContent = code; $('secret-panel').hidden = false; $('secret-panel').scrollIntoView({block:'nearest',behavior:'smooth'}); }
function dateText(value) { if (!value) return ''; const date = new Date(value); return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('ru-RU',{dateStyle:'medium',timeStyle:'short'}).format(date); }
function empty(text) { const node = el('div',undefined,'empty'); node.append(el('h2','Здесь пока ничего нет'),el('p',text)); return node; }
const recordFingerprints=new WeakMap();
function render() {
  const query = $('search').value.trim().toLocaleLowerCase('ru');
  const visible = records.filter(item => [item.title,item.name,item.company,item.position,item.text,item.person_name,item.surname,item.contact,item.recovery_email,sessions.find(s => s.id === item.session_id)?.title].filter(Boolean).join(' ').toLocaleLowerCase('ru').includes(query));
  const container=$('records'), existing=new Map([...container.children].filter(row=>row.dataset.recordKey).map(row=>[row.dataset.recordKey,row]));let index=0;
  if (!visible.length) { container.replaceChildren(empty(query ? 'Попробуйте другое слово или очистите поиск.' : kind === 'recovery' ? 'Новые заявки на помощь со входом появятся здесь.' : feedback() ? 'Обращений по выбранным условиям пока нет.' : 'Добавьте первую запись с помощью кнопки вверху.')); return; }
  visible.forEach(item => {
    const key=kind+':'+(item.id || [item.session_id,item.person_id].join(':')), fingerprint=JSON.stringify([item,sessions.find(s=>s.id===item.session_id)?.title,profile?.role]);
    const previous=existing.get(key);if(previous && recordFingerprints.get(previous)===fingerprint){if(container.children[index]!==previous)container.insertBefore(previous,container.children[index]||null);index++;return;}
    const row = el('article',undefined,'record'), text = el('div',undefined,'record-body'), actions = el('div',undefined,'actions');
    if (kind === 'recovery') {
      text.append(el('h2',item.surname || 'Заявка участника'),el('p',item.contact,'contact'),el('p',dateText(item.created_at),'meta'),el('span',item.status === 'pending' ? 'Ожидает помощи' : 'Заявка получена','badge'));

    } else if (feedback()) {
      text.append(el('h2',kind === 'questions' ? item.text : 'Оценка: ' + item.value + ' из 5'));
      text.append(el('p',[sessions.find(s => s.id === item.session_id)?.title || '',item.person_name,dateText(item.created_at || item.updated_at)].filter(Boolean).join(' · '),'meta'));
      if (kind === 'questions') { text.append(el('span',labels[item.status] || 'Вопрос получен','badge')); text.append(questionForm(item)); }
      else actions.append(button('Удалить оценку', async () => { if (!confirm('Удалить оценку? Участник сможет отправить новую.')) return; await api('/admin/api/ratings/' + pathId(item.session_id) + '/' + pathId(item.person_id),'DELETE'); await load(); },'quiet danger'));
    } else {
      text.append(el('h2',item.title || item.name));
      if (kind === 'people') {
        text.append(el('p',[item.company,item.position].filter(Boolean).join(' · '),'meta'));
        text.append(el('span',[labels[item.role] || 'Участник',item.active ? 'Доступ открыт' : 'Доступ закрыт'].join(' · '),'badge'));
        if (item.recovery_email) text.append(el('p',item.recovery_email,'contact'));
        if (item.avatar_url) { const image=el('img');image.src=item.avatar_url;image.alt='Фото участника';image.className='participant-photo';text.prepend(image); }
        const mailStates={queued:'Письмо в очереди',sending:'Отправляется',sent:'Принято почтовым сервером',failed:'Ошибка отправки',unknown:'Результат отправки неизвестен',cancelled:'Отправка отменена'};
        if(item.mail_status)text.append(el('p',(mailStates[item.mail_status.status] || item.mail_status.status)+(item.mail_status.last_error?' · '+item.mail_status.last_error:''),'meta'));
        const manageable = item.id !== 'admin' && (item.role !== 'organizer' || ['admin','viewer'].includes(profile?.role));
        if (manageable) {
          actions.append(button('Изменить', () => openEditor(item)));
          const more=el('details',undefined,'participant-actions'),summary=el('summary','Действия ⋮'),menu=el('div',undefined,'participant-actions-menu');more.append(summary,menu);actions.append(more);menu.addEventListener('click',event=>{if(event.target.closest('button'))more.open=false;});
          more.addEventListener('toggle',()=>{if(more.open)document.querySelectorAll('.participant-actions[open]').forEach(other=>{if(other!==more)other.open=false;});});
          if(item.code_available)menu.append(button('Показать код',async()=>{const ticket=generation;const result=await api(endpoint('people')+'/'+pathId(item.id)+'/code/view','POST',{});if(ticket===generation&&csrf)oneTime(result.code);}));
          if(item.recovery_email && item.active)menu.append(button('Отправить код по email',async()=>{
            if(!item.code_available){if(!confirm('Старый код восстановить нельзя. Создать новый и отправить участнику? Прежний код и сеансы перестанут работать.'))return;const result=await api(endpoint('people')+'/'+pathId(item.id)+'/code','POST',{});oneTime(result.code);}
            else if(!confirm((item.mail_status?.status==='unknown'?'Результат предыдущей отправки неизвестен. Сначала проверьте получение письма. ':'')+'Отправить письмо с действующим кодом на '+item.recovery_email+'?'))return;
            await api(endpoint('people')+'/'+pathId(item.id)+'/invitation','POST',{});await load();status('Письмо добавлено в очередь отправки.');
          }));
          menu.append(button('Новый код', async () => { if (!confirm('Создать новый код? Предыдущий код и все сеансы участника перестанут работать.')) return; const ticket = generation; const result = await api(endpoint('people') + '/' + pathId(item.id) + '/code','POST',{}); if (ticket === generation && kind === 'people' && csrf) oneTime(result.code); }));
          menu.append(button(item.active ? 'Закрыть доступ' : 'Открыть доступ', async () => { if (!confirm(item.active ? 'Закрыть участнику доступ и завершить его сеансы?' : 'Открыть участнику доступ к событию?')) return; await api(endpoint('people') + '/' + pathId(item.id),'PATCH',{active:!item.active}); await load(); },item.active ? 'quiet danger' : 'quiet'));
          menu.append(button('Удалить',async()=>{if(!confirm('Удалить участника «'+item.name+'» из списка? Код и доступ перестанут действовать.'))return;await api(endpoint('people')+'/'+pathId(item.id),'DELETE');await load();status('Участник удалён.');},'quiet danger'));
        }
      } else {
        text.append(el('p',[item.date,[item.start_time,item.end_time].filter(Boolean).join('–'),item.room].filter(Boolean).join(' · '),'meta'));
        text.append(el('span',labels[item.label] || 'Не опубликовано','badge ' + (item.label === 'PUBLISHED' ? 'published' : '')));
        if (item.description) text.append(el('p',item.description,'excerpt'));
        if (item.label !== 'ARCHIVE2026') actions.append(button('Изменить', () => openEditor(item)));
        else actions.append(button('Создать копию', () => openEditor({...item,id:null,label:'DRAFT'})));
        const section = kind;
        actions.append(button('Удалить', async () => { if (!confirm('Удалить «' + item.title + '»? Действие нельзя отменить.')) return; await api(endpoint(section) + '/' + pathId(item.id),'DELETE'); await load(); },'quiet danger'));
      }
    }
    row.dataset.recordKey=key;recordFingerprints.set(row,fingerprint);row.append(text);if(actions.childElementCount)row.append(actions);if(previous)previous.remove();container.insertBefore(row,container.children[index]||null);index++;
  });
  while(container.children.length>index)container.lastElementChild.remove();
  if(loadingSections || !$('freshness').hidden)lockRecords(true);
}
function questionForm(item) {
  const form = el('form',undefined,'question-form'), label = el('label','Ответ участнику'), response = el('textarea'), stateLabel = el('label','Статус вопроса'), state = el('select'), save = el('button','Сохранить ответ','primary'), error = el('p',undefined,'error');
  response.value = item.response || ''; response.maxLength = 10000; label.append(response);
  ['pending','answered','hidden'].forEach(value => state.add(new Option(labels[value],value))); state.value = item.status; stateLabel.append(state); save.type = 'submit'; error.setAttribute('role','alert'); form.append(label,stateLabel,error,save);
  form.onsubmit = async event => { event.preventDefault(); error.textContent = ''; if (state.value === 'answered' && !response.value.trim()) { error.textContent = 'Добавьте ответ участнику.'; response.focus(); return; } save.disabled = true;
    try { await api('/admin/api/questions/' + pathId(item.id),'PATCH',{status:state.value,response:response.value}); await load(); status('Ответ сохранён.'); } catch (e) { error.textContent = e.message; } finally { save.disabled = false; }
  }; if(profile?.role==='viewer'){response.readOnly=true;state.disabled=true;save.disabled=true;save.title='Только просмотр';} return form;
}
function cacheKey(section=kind){return JSON.stringify([section,feedbackOffset,$('feedback-session').value,$('question-status').value]);}
function freshness(message){$('freshness').textContent=message;$('freshness').hidden=!message;}
function lockRecords(locked){$('records').querySelectorAll('button,summary').forEach(node=>{if(node.tagName==='BUTTON')node.disabled=locked;else{node.setAttribute('aria-disabled',String(locked));node.style.pointerEvents=locked?'none':'';}});}
async function load({background=false}={}) {
  const ticket=++generation,section=kind,isFeedback=feedback(),key=cacheKey(),owner=csrf;
  loadingSections++;
  if(!background){hideSecret();status('');}
  $('section-title').textContent=sections[section][0];$('section-description').hidden=true;
  $('new').textContent=sections[section][2]||'';$('new').hidden=!sections[section][2];
  $('participant-tabs').hidden=!['people','recovery'].includes(section);
  document.querySelectorAll('[data-participant-tab]').forEach(tab=>{const active=tab.dataset.participantTab===section;tab.classList.toggle('selected',active);tab.setAttribute('aria-current',active?'page':'false');});
  $('mail-readiness').hidden=true;$('privacy-note').hidden=true;
  $('feedback-filters').hidden=!isFeedback;$('question-status-label').hidden=section!=='questions';$('feedback-pages').hidden=!isFeedback;
  $('search').placeholder=section==='people'?'Имя, организация или email':section==='recovery'?'Фамилия или контакт':'Поиск';
  const cached=sectionCache.get(key);
  if(cached){records=cached.records;sessions=cached.sessions;if(!background)render();freshness('');lockRecords(true);}
  else if(!background){records=[];$('records').replaceChildren(el('p','Загружаем…','loading'));}
  lockRecords(true);$('records').setAttribute('aria-busy','true');
  const query=new URLSearchParams({limit:'50',offset:String(feedbackOffset)});
  if(isFeedback&&$('feedback-session').value)query.set('session_id',$('feedback-session').value);
  if(section==='questions'&&$('question-status').value)query.set('status',$('question-status').value);
  const path=endpoint(section)+(isFeedback?'?'+query:'');
  try {
    let result,sessionList;
    for(let attempt=0;attempt<3;attempt++){
      [result,sessionList]=await Promise.all([api(path,'GET',undefined,true),isFeedback?api('/admin/api/sessions','GET',undefined,true):Promise.resolve(null)]);
      if(ticket!==generation||owner!==csrf)return;
      if(!sessionList || result.revision===sessionList.revision)break;
      if(attempt===2)throw new Error('Данные изменяются. Повторяем обновление.');
    }
    records=result.data;sessions=sessionList?.data||[];
    if(isFeedback){const selected=$('feedback-session').value;$('feedback-session').replaceChildren(new Option('Все сессии',''));sessions.forEach(item=>$('feedback-session').add(new Option(item.title,item.id)));$('feedback-session').value=selected;}
    sectionCache.set(key,{records,sessions,revision:result.revision});if(sectionCache.size>32)sectionCache.delete(sectionCache.keys().next().value);
    latestRevision=result.revision;if(!cached || JSON.stringify(cached.records)!==JSON.stringify(records) || JSON.stringify(cached.sessions)!==JSON.stringify(sessions))render();freshness('');lockRecords(false);
    $('previous').disabled=feedbackOffset===0;$('next').disabled=records.length<50;$('page-label').textContent='Страница '+(feedbackOffset/50+1);
  } catch(error){
    if(ticket!==generation||owner!==csrf)return;
    if(cached || background){freshness('Показана сохранённая копия. Не удалось проверить обновления.');lockRecords(true);}
    else{const box=empty('Проверьте соединение.');box.firstChild.textContent='Не удалось загрузить раздел';box.append(button('Повторить',()=>load()));$('records').replaceChildren(box);status(error.message,true);}
  }finally{loadingSections--;if(ticket===generation)$('records').setAttribute('aria-busy','false');}
}
async function checkRevision(){
  if(!csrf||document.visibilityState!=='visible'||loadingSections)return;
  try{
    const owner=csrf,result=await api('/admin/api/revision');if(owner!==csrf)return;
    const entry=sectionCache.get(cacheKey());
    if(!entry||entry.revision!==result.revision)await load({background:true});
    else{freshness('');lockRecords(false);latestRevision=result.revision;}
  }catch{if(csrf){freshness('Не удалось проверить обновления.');lockRecords(true);}}
}
function scheduleRevisionCheck(){clearTimeout(revisionTimer);if(!csrf)return;revisionTimer=setTimeout(async()=>{await checkRevision();scheduleRevisionCheck();},3000);}
window.addEventListener('focus',()=>{checkRevision();scheduleRevisionCheck();});
window.addEventListener('online',()=>{checkRevision();scheduleRevisionCheck();});
document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible'){checkRevision();scheduleRevisionCheck();}else clearTimeout(revisionTimer);});
if(cacheChannel)cacheChannel.onmessage=event=>{if(event.data?.type==='logout')signedOut();else if(event.data?.type==='changed')checkRevision();};
document.addEventListener('pointerdown',event=>document.querySelectorAll('.participant-actions[open]').forEach(menu=>{if(!menu.contains(event.target))menu.open=false;}));
document.addEventListener('keydown',event=>{if(event.key==='Escape')document.querySelectorAll('.participant-actions[open]').forEach(menu=>{menu.open=false;menu.querySelector('summary').focus();});});
document.querySelectorAll('[data-kind]').forEach(node => node.onclick = () => {
  kind = node.dataset.kind; feedbackOffset = 0; $('search').value = ''; $('feedback-session').value = ''; $('question-status').value = '';
  document.querySelectorAll('[data-kind]').forEach(button => { const active = button === node; button.classList.toggle('selected',active); if (active) button.setAttribute('aria-current','page'); else button.removeAttribute('aria-current'); }); load();
});
document.querySelectorAll('[data-participant-tab]').forEach(tab=>tab.onclick=()=>{kind=tab.dataset.participantTab;feedbackOffset=0;$('search').value='';hideSecret();load();});
$('search').oninput = render; $('refresh').onclick = () => load();
$('feedback-session').onchange = $('question-status').onchange = () => { feedbackOffset = 0; load(); };
$('previous').onclick = () => { feedbackOffset = Math.max(0,feedbackOffset - 50); load(); };
$('next').onclick = () => { feedbackOffset += 50; load(); };
function field(key, title, type, max, item, required = false) {
  const label = el('label',title), input = el(type === 'textarea' ? 'textarea' : 'input'); input.name = key;
  if (type !== 'textarea') input.type = type; input.value = item?.[key] || ''; if (max) input.maxLength = max; input.required = required; label.append(input); $('fields').append(label); return input;
}
function openEditor(item = null) {
  editorKind = kind; editing = item?.id ? item : null; $('fields').replaceChildren(); $('edit-error').textContent = ''; $('edit-title').textContent = editing ? 'Изменить запись' : sections[kind][2];
  if (kind === 'people') {
    const parts=(item?.name || '').trim().split(/\s+/);
    const last=field('last_name','Фамилия','text',100,{last_name:item?.last_name || (parts.length>1?parts[0]:'')},true);
    const first=field('first_name','Имя','text',100,{first_name:item?.first_name || (parts.length>1?parts[1]:parts[0])},true);
    const patronymic=field('patronymic','Отчество','text',100,{patronymic:item?.patronymic || (parts.length>2?parts.slice(2).join(' '):'')});
    const full=field('name','ФИО в приложении','text',160,item,true);
    const sync=()=>{full.value=[last.value,first.value,patronymic.value].filter(Boolean).join(' ');};[last,first,patronymic].forEach(input=>input.addEventListener('input',sync));if(!item)sync();
    field('recovery_email','Email участника','email',254,item);
    field('company','Организация','text',200,item);field('position','Должность','text',200,item);field('bio','О себе','textarea',4000,item);
    const avatar=field('avatar_url','','hidden',128,item);avatar.parentElement.hidden=true;
    const photoLabel=el('div',undefined,'photo-picker'),photo=el('input'),preview=el('img'),choose=button(item?.avatar_url?'Заменить фото':'Загрузить фото',async()=>photo.click()),photoStatus=el('span',item?.avatar_url?'Фото загружено':'Фото не выбрано','hint');
    photo.type='file';photo.accept='image/jpeg,image/png,image/webp';photo.hidden=true;
    preview.className='participant-photo';preview.alt='Фото участника';preview.hidden=!item?.avatar_url;if(item?.avatar_url)preview.src=item.avatar_url;
    photoLabel.append(el('span','Фото участника'),choose,photo,photoStatus,preview);$('fields').append(photoLabel);
    photo.onchange=async()=>{if(!photo.files[0])return;const selected=photo.files[0];$('save').disabled=true;choose.disabled=true;$('edit-error').textContent='';photoStatus.textContent='Обработка фото…';
      try{const {preparePhoto}=await import('/static/photo-upload.js');const compressed=await preparePhoto(selected);const {cropAvatar}=await import('/static/avatar-crop.js');const cropped=await cropAvatar(compressed,{language:'ru'});if(!cropped){photoStatus.textContent=avatar.value?'Фото загружено':'Фото не выбрано';return;}photoStatus.textContent='Загрузка фото…';const body=new FormData();body.append('file',cropped,'avatar.jpg');const result=await api('/admin/api/participant-avatar','POST',body);avatar.value=result.avatar_url;preview.src=result.avatar_url;preview.hidden=false;choose.textContent='Заменить фото';photoStatus.textContent='Фото загружено';}
      catch(error){$('edit-error').textContent=error.message;photoStatus.textContent=avatar.value?'Фото загружено':'Фото не выбрано';}finally{$('save').disabled=false;choose.disabled=false;photo.value='';}};
    if(!editing){const key=field('registration_key','','hidden',36,{registration_key:crypto.randomUUID()});key.parentElement.hidden=true;const label=el('label',undefined,'checkbox-field'),check=el('input');check.type='checkbox';check.name='send_email';label.append(check,document.createTextNode('Отправить письмо с кодом входа'));$('fields').append(label);}
    if (['admin','viewer'].includes(profile?.role)) { const label = el('label','Доступ'), select = el('select'); select.name = 'role'; ['attendee','organizer'].forEach(role => select.add(new Option(labels[role],role))); select.value = item?.role || 'attendee'; label.append(select); $('fields').append(label);  }
  } else {
    field('title',kind === 'partners' ? 'Название организации' : 'Название','text',300,item,true); field('description','Описание','textarea',20000,item);
    field('date','Дата','date',10,item);
    // Entry replaces the whole record. Keep existing values even in sections where they are seldom used.
    field('start_time','Время начала','time',5,item); field('end_time','Время окончания','time',5,item); field('room','Зал','text',200,item); field('url','Ссылка','url',2000,item);
    const label = el('label','Публикация'), select = el('select'); select.name = 'label';
    const options = {DRAFT:'Черновик',PUBLISHED:'Опубликовано'};
    if (item?.label === 'ADAPTED2027') options.ADAPTED2027 = labels.ADAPTED2027;
    if (item?.label && !options[item.label]) options[item.label] = 'Не опубликовано (сохранить текущий статус)';
    Object.entries(options).forEach(([value,text]) => select.add(new Option(text,value))); select.value = item?.label || 'DRAFT'; label.append(select); $('fields').append(label);
    
  }
  const readonly=profile?.role==='viewer';$('fields').querySelectorAll('input,textarea,select,button').forEach(control=>{if(control.tagName==='SELECT'||control.tagName==='BUTTON'||['checkbox','file'].includes(control.type))control.disabled=readonly;else control.readOnly=readonly;});$('save').disabled=readonly;$('save').title=readonly?'Только просмотр':'';
  $('editor').showModal();
}
$('new').onclick = () => openEditor(); $('close').onclick = () => $('editor').close();
$('editor').addEventListener('close', () => { $('fields').replaceChildren(); editing = null; });
$('edit-form').onsubmit = async event => {
  event.preventDefault(); if(profile?.role==='viewer')return; const data = Object.fromEntries(new FormData(event.target)), section = editorKind; $('edit-error').textContent = '';
  if (data.start_time && data.end_time && data.end_time <= data.start_time) { $('edit-error').textContent = 'Время окончания должно быть позже времени начала.'; return; }
  if(section==='people'&&!editing)data.send_email=data.send_email==='on';
  if(data.send_email&&!data.recovery_email){$('edit-error').textContent='Укажите email для отправки письма.';return;}
  $('save').disabled = true;
  try { const result = await api(endpoint(section) + (editing ? '/' + pathId(editing.id) : ''), editing ? (section === 'people' ? 'PATCH' : 'PUT') : 'POST',data); $('editor').close(); await load(); status(result.mail_id?'Участник зарегистрирован. Письмо добавлено в очередь.':'Изменения сохранены.'); if (result.code) oneTime(result.code); }
  catch (error) { $('edit-error').textContent = error.message; } finally { $('save').disabled = false; }
};
(async () => { if (csrf) { try { const data = await api('/admin/api/session'); showWorkspace(data.profile); await load();scheduleRevisionCheck(); } catch (error) { if (csrf) $('login-error').textContent = error.message; } } })();

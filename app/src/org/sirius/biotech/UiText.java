package org.sirius.biotech;

import android.content.Context;
import java.util.HashMap;
import java.util.Map;

/** Local fixture labels; original UI strings use the retained Android translations. */
final class UiText {
    private static final Map<String,String> RU=new HashMap<>();
    static {
        String[][] words={
            {"Send invitation","Отправить приглашение"},{"Meeting date","Дата и время встречи"},{"Meeting availability","Время для встреч"},
            {"Meeting cancelled","Встреча отменена"},{"Select a time","Выберите время"},{"Selected time is unavailable","Выбранное время недоступно"},
            {"Clear filters","Сбросить"},{"End time must follow start time","Время окончания должно быть позже начала"},
            {"Cancel this meeting?","Отменить встречу?"},{"Decline this meeting?","Отказаться от встречи?"},{"Accept this meeting? ","Подтвердить встречу?"},
            {"Could not save changes. Please retry.","Не удалось сохранить изменения. Повторите попытку."},
            {"Unable to check meeting availability. Please retry.","Не удалось проверить доступное время. Повторите попытку."},
            {"Unable to load availability. Please retry.","Не удалось загрузить доступное время. Повторите попытку."},
            {"Unable to refresh messages. Please retry.","Не удалось обновить сообщения. Повторите попытку."},
            {"Unable to refresh meetings. Please retry.","Не удалось обновить встречи. Повторите попытку."},
            {"Terms and Privacy","Условия и конфиденциальность"},
            {"Unable to sign in","Не удалось войти"},{"Check your ID and try again.","Проверьте ID и повторите попытку."},{"Retry","Повторить"},
            {"Enter an email or phone number (5-254 characters).","Введите e-mail или телефон (от 5 до 254 символов)."},
            {"Last name must be 100 characters or fewer.","Фамилия должна содержать не более 100 символов."},
            {"Privacy Policy","Политика конфиденциальности"},{"Language","Язык"},{"Sign in","Войти"},
            {"Your profile, favorites, meetings and messages are used to provide event services. Your photo is accessible by direct link. Contact the organizer to request deletion of your data.","Ваш профиль, избранное, встречи и сообщения используются для работы сервисов мероприятия. Фотография доступна по прямой ссылке. Для удаления данных обратитесь к организатору."},
            {"Unable to complete request","Не удалось выполнить запрос"},{"Please try again.","Повторите попытку."},
            {"Unable to sign in. Check your ID and try again.","Не удалось войти. Проверьте ID и повторите попытку."},
            {"Unable to open messages. Please try again.","Не удалось открыть сообщения. Повторите попытку."},
            {"Unable to open this page. Please try again.","Не удалось открыть страницу. Повторите попытку."},
            {"Profile saved","Профиль сохранён"},{"Rating saved","Оценка сохранена"},{"No saved sessions.","Нет сохранённых сессий."},
            {"Recover attendee ID","Восстановление ID"},{"Email or phone used for registration","E-mail или телефон, указанный при регистрации"},
            {"Your request has been sent to the organizer. Your ID is not displayed publicly.","Запрос отправлен организатору. Ваш ID не публикуется."},
            {"Meeting","Встреча"},
            {"Сириус, Олимпийский проспект 1, Университет Сириус","Сириус, Олимпийский проспект 1, Университет Сириус"},
            {"Your personal guide to Sirius Biotech 2027. Explore the programme, discover biotechnology projects, find researchers and partners, arrange meetings and stay up to date with the event.","Приложение Сириус.Биотех 2027 — ваш персональный гид по событию. Следите за программой, ориентируйтесь на площадке, находите исследователей и партнёров, назначайте встречи и общайтесь прямо в приложении."},
            {"Profile photo","Фото профиля"},{"Change profile photo","Изменить фото профиля"},{"Your photo will be accessible by its direct link.","Фотография будет доступна по прямой ссылке."},
            {"Server connection","Подключение к серверу"},{"Server address","Адрес сервера"},
            {"Organizer-issued ID","Персональный ID участника"},{"Entry information","Информация о входе"},{"Participant ID","ID участника"},
            {"No participant information is available. Sign in with your organizer-issued ID.","Данные участника недоступны. Войдите с персональным ID, выданным организатором."},
            {"Enter the personal six-digit ID sent by the event organizer. If you did not receive it or cannot find it, contact the organizer to request access or recover your ID. Surname lookup is not available. No public demo ID is provided. Server address is configured separately in Settings.","Введите персональный шестизначный ID от организатора. Если код не получен или потерян, обратитесь к организатору для получения доступа или восстановления ID. Поиск по фамилии пока недоступен. Общего демо-кода нет. Адрес сервера настраивается отдельно в настройках."},
            {"Consent to personal data processing","Согласие на обработку персональных данных"},{"Offline mode","Офлайн-режим"},{"Offline mode · Saved information","Офлайн-режим · Сохранённые данные"},
            {"The server is unavailable. Saved information remains available. Changes were not sent.","Отсутствует подключение к интернету. Пожалуйста, повторите попытку позже"},
            {"Too many sign-in attempts. Retry in seconds:","Слишком много попыток входа. Повторите через указанное число секунд:"},
            {"My questions and rating","Мои вопросы и оценка"},{"Your rating","Ваша оценка"},{"Not rated","Не оценено"},{"answered","Ответ получен"},{"hidden","Скрыт модератором"},
            {"Your session has expired. Please sign in again.","Срок действия сессии истёк. Войдите повторно."},
            {"Organizer server · Programme details may change","Сервер организатора · Программа может изменяться"},
            {"No conversations yet","Переписок пока нет"},{"New conversation","Начать переписку"},
            {"Privacy and server data","Конфиденциальность и данные на сервере"},
            {"Your personal guide to Sirius Biotech 2027. Account data and messages are stored on the organizer server.","Ваш путеводитель по Сириус.Биотех 2027. Данные учётной записи и сообщения хранятся на сервере организатора."},
            {"Profiles, favorites, meeting invitations and messages are sent to the configured organizer server. Contact the organizer to request data deletion. No original MSS services are used.","Профили, избранное, приглашения на встречи и сообщения передаются на настроенный сервер организатора. Для удаления данных обратитесь к организатору. Исходные сервисы MSS не используются."},
            {"Push notifications are not configured yet.","Push-уведомления пока не настроены."},
            {"Use your personal organizer-issued ID","Используйте персональный ID от организатора"},
            {"Enter the six-digit ID provided by the organizer.","Введите шестизначный ID, выданный организатором."},
            {"Authentication failed. Check your personal code and server connection.","Не удалось войти. Проверьте персональный код и подключение к серверу."},
            {"Request failed. Check the server connection and sign in again if needed.","Запрос не выполнен. Проверьте подключение к серверу и при необходимости войдите повторно."},
            {"Profile saved on server","Профиль сохранён на сервере"},{"Question sent","Вопрос отправлен"},{"Rating saved on server","Оценка сохранена на сервере"},
            {"Request a meeting","Назначить встречу"},{"Meeting topic","Тема встречи"},{"Meeting location","Место встречи"},{"Room or meeting point","Зал или точка встречи"},
            {"Meeting invitation sent","Приглашение на встречу отправлено"},{"No meetings yet","Встреч пока нет"},{"Cancel meeting","Отменить встречу"},{"Accept","Принять"},{"Decline","Отклонить"},
            {"pending","Ожидает ответа"},{"accepted","Подтверждена"},{"declined","Отклонена"},{"cancelled","Отменена"},
            {"Programme","Программа"},{"Profile","Профиль"},{"Participants","Участники"},
            {"Messages","Сообщения"},{"Meetings","Встречи"},{"My schedule","Моя программа"},
            {"Venue","Карта площадки"},{"News","Новости"},{"Partners","Партнёры"},
            {"Events","Мероприятия"},{"Settings","Настройки"},{"Home","Главное меню"},
            {"Session","Сессия"},{"Participant","Участник"},{"Search","Поиск"},
            {"All dates","Все даты"},{"No matching items","Ничего не найдено"},{"Favorite","Избранное"},
            {"Open","Открыть"},{"Continue","Продолжить"},{"Registration","Регистрация"},
            {"Enter ID","Введите ID"},{"Save locally","Сохранить на устройстве"},
            {"Cancel","Отмена"},{"OK","ОК"},{"Back","Назад"},{"Menu","Меню"},
            {"Search by event name or ID","Введите название события или ID"},
            {"UPCOMING EVENTS","ПРЕДСТОЯЩИЕ СОБЫТИЯ"},
            {"2027 · Dates to be announced","2027 · Даты будут объявлены"},
            {"Sirius, Russia · Venue to be confirmed","Сириус, Россия · Площадка уточняется"},
            {"OFFLINE PREVIEW · Sample content · Not the final programme","ОФЛАЙН-ДЕМО · Шаблонные данные · Не окончательная программа"},
            {"Demo day 1","Демо-день 1"},{"Demo day 2","Демо-день 2"},
            {"Save to my schedule","Добавить в мою программу"},{"Remove from my schedule","Убрать из моей программы"},
            {"Ask a question","Задать вопрос"},{"Rate session","Оценить сессию"},
            {"Message","Написать"},{"Arrange a meeting","Назначить встречу"},
            {"Save contact","Сохранить контакт"},{"Contact saved","Контакт сохранён"},
            {"Write message","Написать сообщение"},{"New meeting draft","Новый черновик встречи"},
            {"Clear local drafts","Удалить локальные черновики"},{"Local draft","Локальный черновик"},
            {"No matching participants","Участники не найдены"},{"No matching sessions.","Сессии не найдены."},
            {"No saved sessions for this demo day.","В этот демо-день нет избранных сессий."},
            {"Demo participant","Демо-участник"},{"Name","Имя"},{"Organisation / role","Организация / должность"},
            {"Connect with participants","Общайтесь с участниками"},
            {"Find potential partners and colleagues, start conversations and exchange contacts.","Находите партнёров и коллег, общайтесь и обменивайтесь контактами."},
            {"Don't know your ID?","Не знаете свой ID?"},{"Test access","Тестовый вход"},
            {"Sign out","Выйти"},{"Reset all local demo data","Удалить все локальные демо-данные"},
            {"Local draft conversations. Delivery and live messaging will be connected later.","Локальные черновики переписки. Доставка сообщений будет подключена позже."},
            {"Demo chat. Messages stay on this device and are not delivered.","Демо-чат. Сообщения остаются на устройстве и не доставляются."}
        };
        for(String[] pair:words)RU.put(pair[0],pair[1]);
        String[][] screens={
            {"Your personal guide to Sirius Biotech 2027. This offline preview stores data only on this device.","Ваш путеводитель по Sirius Biotech 2027. Данные этого офлайн-демо хранятся только на устройстве."},
            {"Sirius Biotech 2027","Сириус Биотех 2027"},
            {"Connect and explore","Общайтесь и открывайте новое"},
            {"Find participants, plan sessions and discover biotechnology projects. Demo access ID: 123456.","Находите участников, планируйте сессии и знакомьтесь с биотехнологическими проектами. Демо-ID: 123456."},
            {"Local demo privacy","Конфиденциальность демо"},
            {"Reset local demo data","Удалить локальные демо-данные"},
            {"Demo access ID: 123456","Демо-ID: 123456"},
            {"Enter the six-digit demo ID. No SMS or email is sent.","Введите шестизначный демо-ID. SMS и электронные письма не отправляются."},
            {"Local demo profile","Локальный демо-профиль"},
            {"Your organisation / role","Ваша организация / должность"},
            {"Offline preview. Demo ID: 123456. No production services or network access.","Офлайн-демо. Демо-ID: 123456. Нет доступа к рабочим сервисам и сети."},
            {"Notifications","Уведомления"},
            {"Push notifications are disabled in this offline preview.","В этом офлайн-демо push-уведомления отключены."},
            {"Profile, favourites and drafts remain on this device. Reset local demo data to erase them. No data is sent to MSS or Eventicious.","Профиль, избранное и черновики остаются на устройстве. Удалите локальные демо-данные, чтобы очистить их. Данные не отправляются в MSS или Eventicious."},
            {"Reset local data?","Удалить локальные данные?"},
            {"This removes your local profile, favourites and drafts.","Будут удалены локальный профиль, избранное и черновики."},
            {"Reset","Удалить"},
            {"Use the six-digit demo ID: 123456. No SMS or email is sent.","Используйте шестизначный демо-ID: 123456. SMS и электронные письма не отправляются."},
            {"Incorrect ID. Use the demo ID 123456.","Неверный ID. Используйте демо-ID 123456."},
            {"Offline preview","Офлайн-демо"},
            {"This action is not connected to production services.","Это действие не подключено к рабочим сервисам."},
            {"Profile saved locally","Профиль сохранён на устройстве"}
        };
        for(String[] pair:screens)RU.put(pair[0],pair[1]);
        String[][] fixtures={
            {"Demo conversation - Not connected","Демо-переписка — нет подключения"},
            {"You","Вы"},
            {"Not sent","Не отправлено"},
            {"Local meeting draft","Локальный черновик встречи"},
            {"Local draft, not delivered","Локальный черновик, не доставлено"},
            {"Local draft, no invitation sent","Локальный черновик, приглашение не отправлено"},
            {"Offline preview - Sample news","Офлайн-демо — пример новости"},
            {"Sample news","Пример новости"},
            {"2027 - Dates to be announced","2027 · Даты будут объявлены"},
            {"Sirius, Russia - Venue to be confirmed","Сириус, Россия · Площадка уточняется"},
            {"Search local events","Поиск локальных мероприятий"},
            {"Event name or ID (local2027)","Название мероприятия или ID (local2027)"},
            {"No matching local event. Search by name or ID: local2027","Локальное мероприятие не найдено. Поиск по названию или ID: local2027"},
            {"Plan your event","Планируйте участие"},
            {"Explore Sirius Biotech","Откройте для себя Sirius Biotech"},
            {"Browse sessions, save favourites and arrange meetings.","Изучайте программу, сохраняйте избранное и назначайте встречи."},
            {"Discover projects, research and the event venue.","Знакомьтесь с проектами, исследованиями и площадкой мероприятия."},
            {"Next","Далее"},
            {"I accept the Terms of Use and Privacy Policy","Я принимаю условия использования и политику конфиденциальности"},
            {"I consent to processing my data for this local demo","Я соглашаюсь на обработку данных в этом локальном демо"},
            {"Read demo terms and privacy notice","Условия демо и конфиденциальность"},
            {"Demo terms and privacy","Условия демо и конфиденциальность"},
            {"This is a local testing build. Profiles, messages, favourites and meetings are stored only on this device. No data is sent to MSS or Eventicious. These are not final event legal documents.","Это локальная тестовая версия. Профиль, сообщения, избранное и встречи хранятся только на устройстве. Данные не отправляются в MSS или Eventicious. Это не окончательные юридические документы мероприятия."},
            {"Please accept both notices to continue","Примите оба уведомления, чтобы продолжить"},
            {"Incorrect test ID","Неверный тестовый ID"},
            {"Original menu could not be loaded in this build.","Не удалось загрузить оригинальное меню в этой версии."},
            {"★ Saved","★ Сохранено"},
            {"☆ Save session","☆ Сохранить сессию"},
            {"Session question","Вопрос по сессии"},
            {"Your question","Ваш вопрос"},
            {"Question saved locally. Not sent to a speaker.","Вопрос сохранён на устройстве. Спикеру не отправлен."},
            {"Demo rating","Демо-оценка"},
            {"Rating saved locally","Оценка сохранена на устройстве"},
            {"Request a meeting","Запрос встречи"},
            {"Proposed time / topic","Предлагаемое время / тема"},
            {"Meeting draft saved; no invitation sent","Черновик встречи сохранён; приглашение не отправлено"},
            {"Delete meeting drafts?","Удалить черновики встреч?"},
            {"Delete","Удалить"},
            {"Edit name","Изменить имя"},
            {"Profile name","Имя в профиле"},
            {"Edit organisation / role","Изменить организацию / должность"},
            {"Details","Сведения"},
            {"My saved contacts","Мои сохранённые контакты"},
            {"Saved contacts","Сохранённые контакты"},
            {"No saved contacts yet.","Пока нет сохранённых контактов."},
            {"Please enter text","Введите текст"},
            {"This profile is a fictional demo participant, not a real registered attendee.","Это вымышленный демо-профиль, а не реальный зарегистрированный участник."},
            {"ARCHIVE 2026 / Архив 2026","Архив 2026"},
            {"Speakers: see archival programme / Спикеры: см. программу 2026","Спикеры: см. программу 2026"},
            {"Genomics researcher (Demo)","Исследователь геномики (демо)"},
            {"Cell engineer (Demo)","Клеточный инженер (демо)"},
            {"Bioprocess scientist (Demo)","Исследователь биопроцессов (демо)"},
            {"Computational biologist (Demo)","Вычислительный биолог (демо)"},
            {"Translational researcher (Demo)","Исследователь трансляционной медицины (демо)"},
            {"Biosafety specialist (Demo)","Специалист по биобезопасности (демо)"},
            {"Demo Genome Lab","Демо-лаборатория геномики"},
            {"Demo Cell Studio","Демо-студия клеточной инженерии"},
            {"Demo Biofoundry","Демо-биофабрика"},
            {"Demo Data Lab","Демо-лаборатория данных"},
            {"Demo Therapy Lab","Демо-лаборатория терапии"},
            {"Demo Safety Studio","Демо-студия биобезопасности"},
            {"2027 event: dates TBD","Мероприятие 2027 года: даты уточняются"},
            {"Event year: 2027. Dates TBD. This offline demo does not announce confirmed dates or a timetable.","Год мероприятия: 2027. Даты уточняются. Это офлайн-демо не содержит подтверждённых дат или расписания."},
            {"Explore the demo programme","Изучите демонстрационную программу"},
            {"Programme uses verified archival sessions from 20-22 May 2026, not confirmed 2027 sessions. Participant profiles and partner cards remain fictional fixtures.","В программе представлены проверенные архивные сессии 20–22 мая 2026 года, а не подтверждённые сессии 2027 года. Профили участников и карточки партнёров — вымышленные примеры."},
            {"Offline demo access","Доступ к офлайн-демо"},
            {"Use demo code 123456 to try local authentication. No account is created and no data is sent to a server.","Используйте демо-код 123456 для локального входа. Учётная запись не создаётся, данные на сервер не отправляются."},
            {"Fictional demo partner for genomics research; not an actual event partner.","Вымышленный демо-партнёр в области геномики; не является реальным партнёром мероприятия."},
            {"Fictional demo partner for biomanufacturing; not an actual event partner.","Вымышленный демо-партнёр в области биопроизводства; не является реальным партнёром мероприятия."},
            {"Fictional demo partner for computational biology; not an actual event partner.","Вымышленный демо-партнёр в области вычислительной биологии; не является реальным партнёром мероприятия."},
            {"Offline demo: no backend connected. Event year: 2027; dates TBD.","Офлайн-демо: сервер не подключён. Год мероприятия: 2027; даты уточняются."},
            {"Mira Levin (Demo)","Mira Levin (демо)"},
            {"Anton Valev (Demo)","Anton Valev (демо)"},
            {"Nina Belova (Demo)","Nina Belova (демо)"},
            {"Lev Orlov (Demo)","Lev Orlov (демо)"},
            {"Sofia Marin (Demo)","Sofia Marin (демо)"},
            {"Denis Rovin (Demo)","Denis Rovin (демо)"}
        };
        for(String[] pair:fixtures)RU.put(pair[0],pair[1]);
        RU.put("Long press to clear","Удерживайте, чтобы очистить");
        RU.put("Your personal guide to Sirius Biotech 2027. Explore the programme, discover biotechnology projects, find researchers and partners, arrange meetings and stay up to date with the event.\n\nConnected event application. Archival 2026 sessions are not confirmed 2027 sessions. The final programme, speakers, venue and event dates will be added later.\n\nEvent ID: local2027. Sign in with your personal six-digit organizer-issued ID. If you do not know your ID, contact the event organizer. No public demo ID is provided.","Ваш путеводитель по Sirius Biotech 2027. Изучайте программу, знакомьтесь с биотехнологическими проектами, находите исследователей и партнёров, назначайте встречи и следите за новостями мероприятия.\n\nПриложение подключено к серверу организатора. Архивные сессии 2026 года не являются утверждённой программой 2027 года. Окончательная программа, спикеры, площадка и даты мероприятия будут добавлены позже.\n\nID мероприятия: local2027. Для входа используйте персональный шестизначный ID. Если код неизвестен, обратитесь к организатору. Общего демо-кода нет.");

    }
    static String translate(Context context,String value){
        if(value==null||!"ru".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage()))return value;
        String translated=RU.get(value);
        if(translated!=null)return translated;
        String archive="ARCHIVE 2026 / Архив 2026";
        if(value.startsWith(archive+" · ") && value.substring(archive.length()+3).matches("2026-05-(20|21|22) · [0-9: -]+"))
            return "Архив 2026"+value.substring(archive.length());
        String source=archive+". Preliminary content for 2027; not a confirmed 2027 session. Source: ";
        String pdf="https://siriusuniversity.ru/upload/medialibrary/464/kq5utg49e3g5fv2q15klf5drjzh3veva/Программа_от%2020%20мая.pdf";
        if(value.startsWith(source+pdf+" (PDF p. ") && value.endsWith(").")) {
            String page=value.substring((source+pdf+" (PDF p. ").length(),value.length()-2);
            if(page.matches("[1-9][0-9]?"))return "Архив 2026. Предварительные материалы для 2027 года; сессия не подтверждена на 2027 год. Источник: "+pdf+" (PDF, стр. "+page+").";
        }
        // Only known fixture components may be combined; never replace words inside user text.
        for(String separator:new String[]{" · ","\n"}) {
            String[] parts=value.split(java.util.regex.Pattern.quote(separator),-1);
            if(parts.length<2)continue;
            StringBuilder result=new StringBuilder();boolean known=true;
            for(int index=0;index<parts.length;index++) {
                String part=parts[index];
                if(!part.isEmpty()&&!RU.containsKey(part)){known=false;break;}
                if(index>0)result.append(separator);
                result.append(part.isEmpty()?"":RU.get(part));
            }
            if(known)return result.toString();
        }
        if(value.startsWith("Speaker: ")) {
            String speaker=value.substring(9);
            if(speaker.equals("Rudolf Valenta")||RU.containsKey(speaker))
                return "Спикер: "+translate(context,speaker);
        }
        if(value.startsWith("Message to ")) {
            String person=value.substring(11);
            if(RU.containsKey(person))return "Сообщение для "+RU.get(person);
        }
        return value;
    }
}

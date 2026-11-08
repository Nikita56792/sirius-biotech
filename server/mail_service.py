"""Private invitation outbox; sending worker runs in its own container."""
import base64
import hashlib
import hmac
import html
import json
import os
import secrets
import smtplib
import socket
import ssl
import time
from email.message import EmailMessage
from email.utils import formataddr, formatdate
from pathlib import Path
from cryptography.fernet import Fernet

ROOT = Path(__file__).parent
SENDER_NAME = 'Sirius Biotech Summit 2027 // Аккредитационный центр'
APP_URL = 'https://nikita56792.github.io/SBS/'
PUBLIC_URL = 'https://158.160.205.107'


def cipher():
    secret = os.environ.get('PIN_LOOKUP_SECRET', os.environ.get('ADMIN_CODE',''))
    if len(secret) < 16:
        raise RuntimeError('Mail encryption secret is not configured')
    return Fernet(base64.urlsafe_b64encode(hashlib.sha256(('sbs-mail-v1:' + secret).encode()).digest()))


def seal(value):
    return cipher().encrypt(value.encode()).decode()


def unseal(value):
    return cipher().decrypt(value.encode()).decode()


def initialize(c):
    c.executescript('''
    CREATE TABLE IF NOT EXISTS mail_subscriptions(token TEXT PRIMARY KEY,person_id TEXT NOT NULL,email TEXT NOT NULL,unsubscribed INTEGER NOT NULL DEFAULT 0,UNIQUE(person_id,email));
    CREATE TABLE IF NOT EXISTS mail_outbox(id TEXT PRIMARY KEY,person_id TEXT NOT NULL,recipient TEXT NOT NULL,payload TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'queued',attempts INTEGER NOT NULL DEFAULT 0,next_attempt REAL NOT NULL,created_at REAL NOT NULL,updated_at REAL NOT NULL,last_error TEXT NOT NULL DEFAULT '',message_id TEXT NOT NULL);
    CREATE INDEX IF NOT EXISTS mail_queue ON mail_outbox(status,next_attempt);
    ''')


def queue_invitation(c, person, code):
    if not person['active'] or not person['recovery_email']:
        raise ValueError('Укажите email активного участника.')
    email = person['recovery_email']
    subscription = c.execute('SELECT * FROM mail_subscriptions WHERE person_id=? AND email=?', (person['id'],email)).fetchone()
    if subscription and subscription['unsubscribed']:
        raise ValueError('Участник отказался от писем на этот адрес.')
    if not subscription:
        token = secrets.token_urlsafe(32)
        c.execute('INSERT INTO mail_subscriptions(token,person_id,email) VALUES(?,?,?)',(token,person['id'],email))
    else:
        token = subscription['token']
    # Repeated button clicks must not enqueue duplicate copies while a send is pending.
    previous = c.execute("SELECT id FROM mail_outbox WHERE person_id=? AND recipient=? AND status IN ('queued','sending') ORDER BY created_at DESC LIMIT 1",(person['id'],email)).fetchone()
    if previous:
        return previous['id']
    now = time.time()
    identifier = secrets.token_hex(16)
    domain = os.environ.get('SBS_SMTP_FROM','').rsplit('@',1)[-1] or 'sbs.invalid'
    greeting = ' '.join(value for value in (person['first_name'],person['patronymic']) if value).strip()
    if not greeting:
        parts = person['name'].split()
        greeting = ' '.join(parts[1:3]) if len(parts) >= 3 else person['name']
    payload = seal(json.dumps({'code':code,'greeting':greeting,'unsubscribe':token}, ensure_ascii=False))
    c.execute('INSERT INTO mail_outbox(id,person_id,recipient,payload,next_attempt,created_at,updated_at,message_id) VALUES(?,?,?,?,?,?,?,?)', (identifier,person['id'],email,payload,now,now,now,'<'+identifier+'@'+domain+'>'))
    return identifier


def invitation(recipient, code, greeting, unsubscribe, message_id, sender=None):
    sender = sender or os.environ['SBS_SMTP_FROM']
    link = PUBLIC_URL + '/mail/unsubscribe/' + unsubscribe
    message = EmailMessage()
    message['From'] = formataddr((SENDER_NAME,sender))
    message['To'] = recipient
    message['Subject'] = 'Вход в приложение Sirius Biotech Summit'
    message['Date'] = formatdate(localtime=False)
    message['Message-ID'] = message_id
    message['List-Unsubscribe'] = '<'+link+'>'
    message['List-Unsubscribe-Post'] = 'List-Unsubscribe=One-Click'
    message.set_content(f'{greeting}, добрый день!\n\nВоспользуйтесь официальным приложением Sirius Biotech Summit: {APP_URL}\nВаш персональный код входа: {code}\n\nСкачайте приложение заранее и войдите в свой профиль.\nДо встречи на полях Sirius Biotech Summit!\n\nПожалуйста, не отвечайте: письмо создано автоматически.\nОтказаться от писем: {link}')
    template = (ROOT/'mail/invitation.html').read_text()
    for key,value in {'GREETING':greeting,'CODE':code,'APP_URL':APP_URL,'UNSUBSCRIBE_URL':link}.items():
        template = template.replace('{{'+key+'}}',html.escape(value,quote=True))
    message.add_alternative(template,subtype='html')
    message.get_payload()[-1].add_related((ROOT/'mail/SBS-mail-light.png').read_bytes(),maintype='image',subtype='png',cid='<sbs-banner>',disposition='inline',filename='SBS-mail.png')
    return message


def delivery_ready():
    return bool(os.environ.get('SBS_SMTP_HOST') and os.environ.get('SBS_SMTP_FROM') and os.environ.get('SBS_SMTP_SECURITY','starttls') in ('ssl','starttls'))


def deliver(message):
    security = os.environ.get('SBS_SMTP_SECURITY','starttls')
    if security not in ('ssl','starttls'):
        raise RuntimeError('TLS required')
    host,port = os.environ['SBS_SMTP_HOST'],int(os.environ.get('SBS_SMTP_PORT','587'))
    context = ssl.create_default_context()
    with (smtplib.SMTP_SSL(host,port,timeout=20,context=context) if security == 'ssl' else smtplib.SMTP(host,port,timeout=20)) as smtp:
        if security == 'starttls':smtp.starttls(context=context);smtp.ehlo()
        if os.environ.get('SBS_SMTP_USER'):smtp.login(os.environ['SBS_SMTP_USER'],os.environ['SBS_SMTP_PASSWORD'])
        if smtp.send_message(message):raise RuntimeError('Recipient refused')


def process_one(connect):
    if not delivery_ready():return False
    now = time.time()
    with connect() as c:
        c.execute('BEGIN IMMEDIATE')
        # A worker killed during SMTP has an ambiguous result; do not automatically
        # resend and risk duplicates. Keep it visible for organizer review.
        c.execute("UPDATE mail_outbox SET status='unknown',last_error='Отправка прервана; проверьте получение перед повтором.',updated_at=? WHERE status='sending' AND updated_at<?",(now,now-900))
        row = c.execute("SELECT * FROM mail_outbox WHERE status='queued' AND next_attempt<=? ORDER BY created_at LIMIT 1",(now,)).fetchone()
        if not row:return False
        person = c.execute('SELECT * FROM people WHERE id=?',(row['person_id'],)).fetchone()
        subscription = c.execute('SELECT * FROM mail_subscriptions WHERE person_id=? AND email=?',(row['person_id'],row['recipient'])).fetchone()
        if not person or not person['active'] or person['recovery_email'] != row['recipient'] or not subscription or subscription['unsubscribed']:
            c.execute("UPDATE mail_outbox SET status='cancelled',payload='',updated_at=? WHERE id=?",(now,row['id']));return True
        try:
            data = json.loads(unseal(row['payload']))
        except Exception:
            c.execute("UPDATE mail_outbox SET status='failed',payload='',last_error='Не удалось прочитать сохранённое письмо.',updated_at=? WHERE id=?",(now,row['id']))
            return True
        key = hmac.new(os.environ.get('PIN_LOOKUP_SECRET',os.environ.get('ADMIN_CODE','')).encode(),data['code'].encode(),hashlib.sha256).hexdigest()
        if not hmac.compare_digest(person['code_key'] or '',key):
            c.execute("UPDATE mail_outbox SET status='cancelled',payload='',updated_at=? WHERE id=?",(now,row['id']));return True
        c.execute("UPDATE mail_outbox SET status='sending',attempts=attempts+1,updated_at=? WHERE id=?",(now,row['id']))
    try:
        message = invitation(row['recipient'],data['code'],data['greeting'],data['unsubscribe'],row['message_id'])
        deliver(message)
    except Exception as error:
        # A disconnect during DATA may occur after the recipient has accepted mail.
        ambiguous = isinstance(error,(smtplib.SMTPServerDisconnected,socket.timeout))
        permanent = isinstance(error,(smtplib.SMTPRecipientsRefused,smtplib.SMTPSenderRefused)) or 500 <= getattr(error,'smtp_code',0) < 600 or row['attempts']>=4
        state = 'unknown' if ambiguous else 'failed' if permanent else 'queued'
        detail = 'Результат отправки неизвестен; проверьте получение.' if ambiguous else 'Почтовый сервер отклонил отправку или недоступен.'
        with connect() as c:
            c.execute('UPDATE mail_outbox SET status=?,last_error=?,next_attempt=?,updated_at=? WHERE id=?',(state,detail,now+min(3600,60*2**row['attempts']),time.time(),row['id']))
        return True
    with connect() as c:
        c.execute("UPDATE mail_outbox SET status='sent',payload='',last_error='',updated_at=? WHERE id=?",(time.time(),row['id']))
    return True

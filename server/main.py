"""Single-instance event API. No third-party services or AI integration."""
import hashlib
import hmac
import json
import os
import re
import secrets
import smtplib
import sqlite3
import ssl
import time
import unicodedata
from email.message import EmailMessage
import warnings
from contextlib import asynccontextmanager, contextmanager
from datetime import datetime, timedelta
from io import BytesIO
from pathlib import Path
from typing import Literal
from uuid import UUID

from fastapi import Depends, FastAPI, HTTPException, Request, Response, Query
from fastapi.responses import FileResponse, JSONResponse, HTMLResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, ConfigDict, Field, field_validator
from PIL import Image, ImageOps, UnidentifiedImageError
from starlette.concurrency import run_in_threadpool
from starlette.datastructures import UploadFile

import mail_service as mail

ROOT = Path(__file__).parent
SPEAKER_PHOTOS = json.loads((ROOT/'fixtures/speaker-photos.json').read_text()) if (ROOT/'fixtures/speaker-photos.json').is_file() else {}
DB = os.getenv('DATABASE_PATH', '/data/sbs.sqlite3')
TOKEN_TTL = 12 * 3600
COOKIE = 'sbs_admin'
CREDENTIAL_SECRET = None
VIEWER_ID = 'admin_viewer'
AVATAR_DIR = Path(os.getenv('AVATAR_UPLOAD_DIR', '/data/uploads'))
AVATAR_LIMIT = 4 * 1024 * 1024
CHAT_IMAGE_DIR = Path(os.getenv('CHAT_IMAGE_DIR', str(AVATAR_DIR.parent / 'chat-images')))
AVATAR_NAME = re.compile(r'[0-9a-f]{64}\.(?:png|webp)', re.ASCII)


def avatar_path(url):
    prefix = '/media/avatars/'
    if not url.startswith(prefix) or not AVATAR_NAME.fullmatch(url[len(prefix):]):
        raise ValueError('Use an existing avatar URL or leave empty')
    name=url[len(prefix):]
    path = AVATAR_DIR / name
    if not path.is_file() and name.endswith('.png'):
        alias=AVATAR_DIR / 'aliases' / (name+'.alias')
        if alias.is_file() and not alias.is_symlink():
            target=alias.read_text(encoding='ascii').strip()
            if AVATAR_NAME.fullmatch(target) and target.endswith('.webp'):
                path=AVATAR_DIR / target
    if path.is_symlink() or not path.is_file():
        raise ValueError('Avatar does not exist')
    return path


def controlled_avatar(url):
    if url:
        avatar_path(url)
    return url


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def credential_key(value):
    # Keyed lookup avoids reducing six-digit PIN protection to a plain SHA lookup.
    return hmac.new(CREDENTIAL_SECRET.encode(), value.encode(), hashlib.sha256).hexdigest()


def password_hash(value, salt=None):
    salt = salt or secrets.token_hex(16)
    return salt + ':' + hashlib.pbkdf2_hmac('sha256', value.encode(), bytes.fromhex(salt), 310000).hex()


def verify(value, stored):
    return hmac.compare_digest(password_hash(value, stored.split(':')[0]), stored)


@contextmanager
def db():
    conn = sqlite3.connect(DB, timeout=15)
    conn.row_factory = sqlite3.Row
    conn.execute('PRAGMA foreign_keys=ON')
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def fail(status=404, detail='Not found'):
    raise HTTPException(status, detail)


def profile(row):
    return {k: row[k] for k in ('id', 'name', 'role', 'bio', 'company', 'position', 'avatar_url')}


@asynccontextmanager
async def lifespan(app):
    global CREDENTIAL_SECRET
    code = os.environ.get('ADMIN_CODE', '')
    if len(code) < 16:
        raise RuntimeError('ADMIN_CODE must be set to a cryptographically random value of at least 16 characters')
    Path(DB).parent.mkdir(parents=True, exist_ok=True)
    with db() as c:
        c.executescript('''
        PRAGMA journal_mode=WAL;
        CREATE TABLE IF NOT EXISTS people(id TEXT PRIMARY KEY,name TEXT NOT NULL,role TEXT NOT NULL,bio TEXT NOT NULL DEFAULT '',company TEXT NOT NULL DEFAULT '',position TEXT NOT NULL DEFAULT '',code_hash TEXT,code_key TEXT UNIQUE,active INTEGER NOT NULL DEFAULT 1);
        CREATE TABLE IF NOT EXISTS tokens(hash TEXT PRIMARY KEY,person_id TEXT REFERENCES people(id),expires REAL,kind TEXT,csrf TEXT);
        CREATE TABLE IF NOT EXISTS content(kind TEXT,id TEXT,data TEXT NOT NULL,PRIMARY KEY(kind,id));
        CREATE TABLE IF NOT EXISTS chats(id TEXT PRIMARY KEY,a TEXT REFERENCES people(id),b TEXT REFERENCES people(id),UNIQUE(a,b));
        CREATE TABLE IF NOT EXISTS chat_states(chat_id TEXT REFERENCES chats(id),person_id TEXT REFERENCES people(id),hidden INTEGER NOT NULL DEFAULT 0,muted INTEGER NOT NULL DEFAULT 0,last_read INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(chat_id,person_id));
        CREATE TABLE IF NOT EXISTS meeting_availability(person_id TEXT PRIMARY KEY REFERENCES people(id),data TEXT NOT NULL DEFAULT '[]');
        CREATE TABLE IF NOT EXISTS messages(id INTEGER PRIMARY KEY AUTOINCREMENT,chat_id TEXT REFERENCES chats(id),sender_id TEXT REFERENCES people(id),text TEXT,created_at TEXT);
        CREATE TABLE IF NOT EXISTS chat_images(id TEXT PRIMARY KEY, chat_id TEXT NOT NULL, uploader_id TEXT NOT NULL, created REAL NOT NULL, message_id INTEGER);
        CREATE TABLE IF NOT EXISTS meetings(id TEXT PRIMARY KEY,owner_id TEXT REFERENCES people(id),person_id TEXT REFERENCES people(id),starts_at TEXT,location TEXT,title TEXT,status TEXT);
        CREATE TABLE IF NOT EXISTS favorites(person_id TEXT REFERENCES people(id),kind TEXT,item_id TEXT,PRIMARY KEY(person_id,kind,item_id));
        CREATE TABLE IF NOT EXISTS questions(id TEXT PRIMARY KEY,person_id TEXT REFERENCES people(id),session_id TEXT,text TEXT);
        CREATE TABLE IF NOT EXISTS ratings(person_id TEXT REFERENCES people(id),session_id TEXT,value INTEGER,PRIMARY KEY(person_id,session_id));
        CREATE TABLE IF NOT EXISTS rate_limits(key TEXT PRIMARY KEY,start REAL,count INTEGER);
        CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY,value TEXT);
        CREATE INDEX IF NOT EXISTS messages_chat_order ON messages(chat_id,id);
        CREATE TABLE IF NOT EXISTS recovery_requests(id TEXT PRIMARY KEY,surname TEXT NOT NULL,contact TEXT NOT NULL,created_at TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'pending');
        CREATE TABLE IF NOT EXISTS auth_selections(hash TEXT PRIMARY KEY,person_id TEXT REFERENCES people(id),ip TEXT NOT NULL,recipient TEXT NOT NULL,expires REAL NOT NULL,used INTEGER NOT NULL DEFAULT 0);
        CREATE TABLE IF NOT EXISTS auth_challenges(hash TEXT PRIMARY KEY,selection_hash TEXT NOT NULL,person_id TEXT REFERENCES people(id),ip TEXT NOT NULL,recipient TEXT NOT NULL,pin_hash TEXT NOT NULL,expires REAL NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,delivered INTEGER NOT NULL DEFAULT 0,consumed INTEGER NOT NULL DEFAULT 0);
        CREATE INDEX IF NOT EXISTS auth_selections_expiry ON auth_selections(expires);
        CREATE INDEX IF NOT EXISTS auth_challenges_expiry ON auth_challenges(expires);
        ''')
        mail.initialize(c)
        # Serialize additive migrations when upgrading an existing database.
        c.execute('BEGIN IMMEDIATE')
        additions = {
            'people': {'avatar_url': "TEXT NOT NULL DEFAULT ''", 'last_name': "TEXT NOT NULL DEFAULT ''", 'recovery_email': "TEXT NOT NULL DEFAULT ''", 'first_name': "TEXT NOT NULL DEFAULT ''", 'patronymic': "TEXT NOT NULL DEFAULT ''", 'access_code_cipher': "TEXT NOT NULL DEFAULT ''", 'registration_key': 'TEXT', 'registration_fingerprint': "TEXT NOT NULL DEFAULT ''", 'deleted': 'INTEGER NOT NULL DEFAULT 0'},
            'messages': {'client_message_id': 'TEXT', 'attachment_id': 'TEXT'},
            'questions': {
                'status': "TEXT NOT NULL DEFAULT 'pending'",
                'response': "TEXT NOT NULL DEFAULT ''",
                'created_at': "TEXT NOT NULL DEFAULT ''",
                'updated_at': "TEXT NOT NULL DEFAULT ''",
            },
            'ratings': {'updated_at': "TEXT NOT NULL DEFAULT ''"},
        }
        for table, columns in additions.items():
            existing_columns = {r['name'] for r in c.execute(f'PRAGMA table_info({table})')}
            for column, definition in columns.items():
                if column not in existing_columns:
                    c.execute(f'ALTER TABLE {table} ADD COLUMN {column} {definition}')
        c.execute('CREATE UNIQUE INDEX IF NOT EXISTS people_registration_key ON people(registration_key) WHERE registration_key IS NOT NULL')
        c.execute('CREATE UNIQUE INDEX IF NOT EXISTS messages_client_id ON messages(chat_id,sender_id,client_message_id) WHERE client_message_id IS NOT NULL')
        c.execute('CREATE INDEX IF NOT EXISTS questions_inbox ON questions(status,session_id,id)')
        c.execute('CREATE INDEX IF NOT EXISTS ratings_session ON ratings(session_id,person_id)')
        CREDENTIAL_SECRET = os.environ.get('PIN_LOOKUP_SECRET', code)
        if len(CREDENTIAL_SECRET) < 16:
            raise RuntimeError('PIN_LOOKUP_SECRET must be at least 16 random characters')
        existing = c.execute("SELECT * FROM people WHERE id='admin'").fetchone()
        if not existing:
            c.execute('INSERT INTO people(id,name,role,code_hash,code_key) VALUES(?,?,?,?,?)', ('admin','Organizer','admin',password_hash(code),credential_key(code)))
        elif not verify(code, existing['code_hash']):
            c.execute("UPDATE people SET code_hash=?,code_key=? WHERE id='admin'", (password_hash(code),credential_key(code)))
            c.execute("DELETE FROM tokens WHERE person_id='admin'")
        viewer_code = os.environ.get('ADMIN_VIEWER_CODE', '')
        if viewer_code:
            if len(viewer_code) < 16:
                raise RuntimeError('ADMIN_VIEWER_CODE must be at least 16 characters')
            viewer = c.execute('SELECT * FROM people WHERE id=?', (VIEWER_ID,)).fetchone()
            if not viewer:
                c.execute('INSERT INTO people(id,name,role,code_hash,code_key) VALUES(?,?,?,?,?)', (VIEWER_ID, 'Админ — просмотр', 'viewer', password_hash(viewer_code), credential_key(viewer_code)))
            elif not viewer['code_hash'] or not verify(viewer_code, viewer['code_hash']):
                c.execute('UPDATE people SET name=?,role=?,code_hash=?,code_key=?,active=1,deleted=0 WHERE id=?', ('Админ — просмотр', 'viewer', password_hash(viewer_code), credential_key(viewer_code), VIEWER_ID))
                c.execute('DELETE FROM tokens WHERE person_id=?', (VIEWER_ID,))
        else:
            c.execute('DELETE FROM tokens WHERE person_id=?', (VIEWER_ID,))
            c.execute('UPDATE people SET active=0 WHERE id=?', (VIEWER_ID,))
        if not c.execute("SELECT 1 FROM metadata WHERE key='archive_seeded'").fetchone():
            fixture = json.loads((ROOT / 'fixtures/programme-2026.json').read_text())
            for item in fixture['sessions']:
                c.execute('INSERT OR IGNORE INTO content VALUES(?,?,?)', ('sessions',item['id'],json.dumps(item,ensure_ascii=False)))
            c.execute("INSERT INTO metadata VALUES('archive_seeded','2026')")
        public_fixture=ROOT/'fixtures/official-2026-content.json'
        if public_fixture.exists() and not c.execute("SELECT 1 FROM metadata WHERE key='public_archive_seeded'").fetchone():
            public=json.loads(public_fixture.read_text())
            for kind,records in (('speakers',public['speakers']),('partners',public['partners']),('posts',public['news'])):
                for source in records:
                    record={'id':source['id'],'title':source.get('title',source.get('name','')),'name':source.get('name',''),'description':source.get('bio',source.get('summary',source.get('category',''))),'label':'ARCHIVE2026','year':2026,'source_url':source['sourceURL'],'avatar_url':source.get('photoURL',''),'image_url':source.get('imageURL',''),'url':source.get('websiteURL',source['sourceURL'])}
                    c.execute('INSERT OR IGNORE INTO content VALUES(?,?,?)',(kind,record['id'],json.dumps(record,ensure_ascii=False)))
            c.execute("INSERT INTO metadata VALUES('public_archive_seeded','2026')")
        adapted_path=ROOT/'fixtures/programme-adapted-2027.json'
        if adapted_path.exists() and not c.execute("SELECT 1 FROM metadata WHERE key='adapted_programme_2027'").fetchone():
            adapted=json.loads(adapted_path.read_text())
            for source in adapted['sessions']:
                record={**source,'label':'ADAPTED2027','year':2027,'source_year':adapted['source_year'],'room':source.get('room') or '', 'speaker':', '.join(p['name'] for p in source.get('speakers',[])), 'source_sha256':adapted['source_sha256']}
                c.execute('INSERT OR IGNORE INTO content VALUES(?,?,?)',('sessions',record['id'],json.dumps(record,ensure_ascii=False)))
            c.execute("INSERT INTO metadata VALUES('adapted_programme_2027','imported')")
        c.execute("INSERT OR IGNORE INTO metadata(key,value) VALUES('admin_revision','0')")
        for table in ('content','people','questions','ratings','recovery_requests','mail_outbox'):
            for operation in ('INSERT','UPDATE','DELETE'):
                c.execute(f"CREATE TRIGGER IF NOT EXISTS admin_revision_{table}_{operation.lower()} AFTER {operation} ON {table} BEGIN UPDATE metadata SET value=CAST(value AS INTEGER)+1 WHERE key='admin_revision'; END")
    yield


app = FastAPI(title='SBS Event API', version='1.0.0', lifespan=lifespan)
app.mount('/static', StaticFiles(directory=ROOT / 'static'), name='static')


@app.middleware('http')
async def security_headers(request, call_next):
    if request.method in ('POST','PUT','PATCH'):
        limit = AVATAR_LIMIT + 65536 if (request.url.path in ('/api/v1/profile/avatar','/admin/api/participant-avatar') or re.fullmatch(r'/api/v1/chats/[0-9a-f]{32}/attachments', request.url.path)) else 262144
        length = request.headers.get('content-length')
        if length and (not length.isdigit() or int(length) > limit):
            return JSONResponse({'detail':'Request body too large'}, status_code=413)
        # Bound chunked bodies as well as bodies carrying Content-Length.
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > limit:
                return JSONResponse({'detail':'Request body too large'}, status_code=413)
        request._body = bytes(body)
    response = await call_next(request)
    response.headers['X-Content-Type-Options'] = 'nosniff'
    response.headers['X-Frame-Options'] = 'DENY'
    response.headers['Referrer-Policy'] = 'no-referrer'
    response.headers['Cache-Control'] = ('public, max-age=31536000, immutable' if request.method=='GET' and response.status_code==200 and re.fullmatch(r'/media/avatars/[0-9a-f]{64}\.(?:png|webp)',request.url.path) else 'no-store')
    response.headers['Content-Security-Policy'] = "default-src 'self'; script-src 'self'; style-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
    return response


class Model(BaseModel):
    model_config = ConfigDict(extra='forbid', str_strip_whitespace=True)


class Login(Model):
    code: str = Field(min_length=1, max_length=256)


def rate_limit(request, bucket='login', maximum=10, window=900):
    # Only direct peer IP is trusted; untrusted forwarded headers cannot bypass limits.
    key = bucket + ':' + (request.client.host if request.client else 'unknown')
    rate_limit_key(key, maximum, window)


def rate_limit_key(key, maximum, window=900):
    now = time.time()
    with db() as c:
        c.execute('BEGIN IMMEDIATE')
        c.execute('DELETE FROM rate_limits WHERE start < ?', (now - 900,))
        row = c.execute('SELECT * FROM rate_limits WHERE key=?', (key,)).fetchone()
        if row and row['start'] <= now - window:
            c.execute('DELETE FROM rate_limits WHERE key=?', (key,))
            row = None
        if row and row['count'] >= maximum:
            remaining = max(1, int(window - (now - row['start'])) + 1)
            raise HTTPException(429, 'Too many attempts; try again later', headers={'Retry-After':str(remaining)})
        if row:
            c.execute('UPDATE rate_limits SET count=count+1 WHERE key=?', (key,))
        else:
            # Keep storage bounded even when many distinct addresses hit this instance.
            c.execute('DELETE FROM rate_limits WHERE key IN (SELECT key FROM rate_limits ORDER BY start LIMIT MAX(0,(SELECT COUNT(*) FROM rate_limits)-4095))')
            c.execute('INSERT INTO rate_limits VALUES(?,?,1)', (key,now))


def authenticate(code):
    with db() as c:
        row = c.execute('SELECT * FROM people WHERE code_key=? AND active=1', (credential_key(code),)).fetchone()
    # Spend the same expensive hash work for unknown credentials.
    valid = verify(code, row['code_hash'] if row else DUMMY_HASH)
    if not row or not valid:
        fail(401, 'Invalid credentials')
    return profile(row)


DUMMY_HASH = password_hash('not-a-real-credential')


def issue(person_id, kind):
    token = secrets.token_urlsafe(32)
    csrf = secrets.token_urlsafe(32)
    with db() as c:
        c.execute('DELETE FROM tokens WHERE expires < ?', (time.time(),))
        c.execute('DELETE FROM tokens WHERE hash IN (SELECT hash FROM tokens WHERE person_id=? AND kind=? ORDER BY expires DESC LIMIT -1 OFFSET 19)', (person_id,kind))
        c.execute('INSERT INTO tokens VALUES(?,?,?,?,?)', (digest(token),person_id,time.time()+TOKEN_TTL,kind,digest(csrf)))
    return token, csrf


def current(request: Request):
    auth = request.headers.get('authorization', '')
    if not auth.startswith('Bearer '):
        fail(401, 'Bearer token required')
    return token_user(auth[7:], 'api')[0]


def token_user(token, kind):
    with db() as c:
        row = c.execute('SELECT p.*,t.csrf FROM tokens t JOIN people p ON p.id=t.person_id WHERE t.hash=? AND t.kind=? AND t.expires>? AND p.active=1', (digest(token),kind,time.time())).fetchone()
    if not row:
        fail(401, 'Session expired or invalid')
    return profile(row), row['csrf']


def admin(request: Request):
    user, csrf = token_user(request.cookies.get(COOKIE,''), 'web')
    if user['role'] not in ('admin','organizer','viewer'):
        fail(403, 'Organizer access required')
    if user['role'] == 'viewer' and request.method not in ('GET','HEAD') and request.url.path != '/admin/logout':
        fail(403, 'Read-only access')
    if request.method not in ('GET','HEAD') and not hmac.compare_digest(digest(request.headers.get('x-csrf-token','')),csrf):
        fail(403, 'CSRF token required')
    return user


@app.get('/health')
def health():
    with db() as c:
        c.execute('SELECT 1')
    return {'status':'ok'}


@app.post('/api/v1/auth/login')
def login(data: Login, request: Request):
    rate_limit(request)
    user = authenticate(data.code)
    if user['role'] == 'viewer':
        fail(403, 'Panel-only account')
    token, _ = issue(user['id'],'api')
    return {'access_token':token,'token_type':'bearer','expires_in':TOKEN_TTL,'profile':user}


def normalized_last_name(value):
    return ' '.join(unicodedata.normalize('NFKC', value).casefold().split())


def recovery_address(value):
    # Deliberately narrow ASCII mailbox syntax: no display names or header controls.
    if value and (len(value) > 254 or not re.fullmatch(
            r"[A-Za-z0-9_+%-]+(?:\.[A-Za-z0-9_+%-]+)*@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+", value)):
        raise ValueError('Use a plain ASCII email address or leave empty')
    return value


def masked_contact(value):
    if not value:
        return None
    _, domain = value.split('@')
    # Do not expose the raw destination, even for a one-character local part.
    return '***@' + domain


def smtp_settings():
    try:
        host = os.environ.get('SBS_SMTP_HOST', '')
        sender = recovery_address(os.environ.get('SBS_SMTP_FROM', ''))
        security = os.environ.get('SBS_SMTP_SECURITY', 'starttls')
        port = int(os.environ.get('SBS_SMTP_PORT', '587'))
        user = os.environ.get('SBS_SMTP_USER', '')
        password = os.environ.get('SBS_SMTP_PASSWORD', '')
        if not host or any(ch.isspace() or ord(ch) < 32 for ch in host) or not sender or security not in ('starttls', 'ssl') or not 1 <= port <= 65535 or bool(user) != bool(password):
            raise ValueError()
        return host, port, sender, security, user, password
    except (ValueError, TypeError):
        fail(503, 'Code delivery unavailable')


def deliver_pin(recipient, code):
    host, port, sender, security, user, password = smtp_settings()
    message = EmailMessage()
    message['From'] = sender
    message['To'] = recovery_address(recipient)
    message['Subject'] = 'SBS sign-in code'
    message.set_content('Your SBS sign-in code is: ' + code + '\nIt expires in 10 minutes. Do not share this code.')
    context = ssl.create_default_context()
    transport = smtplib.SMTP_SSL if security == 'ssl' else smtplib.SMTP
    kwargs = {'timeout': 10}
    if security == 'ssl':
        kwargs['context'] = context
    with transport(host, port, **kwargs) as smtp:
        if security == 'starttls':
            smtp.ehlo()
            smtp.starttls(context=context)
            smtp.ehlo()
        if user:
            smtp.login(user, password)
        if smtp.send_message(message):
            raise RuntimeError('Delivery refused')


class SurnameSearch(Model):
    last_name: str = Field(min_length=1, max_length=100)


class SendPin(Model):
    selection_id: str = Field(min_length=20, max_length=128)


class VerifyPin(Model):
    challenge_id: str = Field(min_length=20, max_length=128)
    code: str = Field(pattern=r'^[0-9]{6}$')


def peer(request):
    return digest(request.client.host if request.client else 'unknown')


@app.post('/api/v1/auth/search')
def search_surname(data: SurnameSearch, request: Request):
    rate_limit(request, 'surname-search', 20)
    wanted = normalized_last_name(data.last_name)
    now = time.time()
    items = []
    with db() as c:
        c.execute('BEGIN IMMEDIATE')
        c.execute('DELETE FROM auth_challenges WHERE expires<=?', (now,))
        c.execute('DELETE FROM auth_selections WHERE expires<=?', (now,))
        rows = c.execute("SELECT * FROM people WHERE active=1 AND role NOT IN ('admin','viewer') AND last_name!=''").fetchall()
        for row in rows:
            if not wanted or normalized_last_name(row['last_name']) != wanted:
                continue
            selection = secrets.token_urlsafe(32)
            c.execute('INSERT INTO auth_selections(hash,person_id,ip,recipient,expires) VALUES(?,?,?,?,?)',
                      (digest(selection), row['id'], peer(request), row['recovery_email'], now + 600))
            items.append({'selection_id': selection, 'name': row['name'], 'contact': masked_contact(row['recovery_email'])})
    return {'items': items}


@app.post('/api/v1/auth/send-pin')
def send_pin(data: SendPin, request: Request):
    rate_limit(request, 'send-pin', 10)
    now = time.time()
    selection_hash = digest(data.selection_id)
    with db() as c:
        row = c.execute('SELECT s.*,p.active,p.recovery_email FROM auth_selections s JOIN people p ON p.id=s.person_id WHERE s.hash=? AND s.ip=? AND s.expires>? AND s.used=0',
                        (selection_hash, peer(request), now)).fetchone()
    if not row or not row['active'] or row['recipient'] != row['recovery_email']:
        fail(404, 'Selection expired or invalid')
    rate_limit_key('pin-person:' + row['person_id'], 3)
    rate_limit_key('pin-recipient:' + digest(row['recipient']), 3)
    if not row['recipient']:
        fail(503, 'Code delivery unavailable')
    smtp_settings()
    challenge = secrets.token_urlsafe(32)
    code = f'{secrets.randbelow(1000000):06d}'
    with db() as c:
        c.execute('BEGIN IMMEDIATE')
        changed = c.execute('UPDATE auth_selections SET used=1 WHERE hash=? AND used=0 AND expires>?', (selection_hash, time.time())).rowcount
        if not changed:
            fail(404, 'Selection expired or invalid')
        c.execute('INSERT INTO auth_challenges(hash,selection_hash,person_id,ip,recipient,pin_hash,expires) VALUES(?,?,?,?,?,?,?)',
                  (digest(challenge), selection_hash, row['person_id'], peer(request), row['recipient'], credential_key(challenge + ':' + code), time.time() + 600))
    try:
        # Sync endpoint executes in the worker pool; each SMTP operation has a timeout.
        deliver_pin(row['recipient'], code)
    except Exception:
        with db() as c:
            c.execute('DELETE FROM auth_challenges WHERE hash=?', (digest(challenge),))
        fail(503, 'Code delivery unavailable')
    with db() as c:
        changed = c.execute('UPDATE auth_challenges SET delivered=1 WHERE hash=? AND expires>? AND EXISTS (SELECT 1 FROM people p WHERE p.id=auth_challenges.person_id AND p.active=1 AND p.recovery_email=auth_challenges.recipient)', (digest(challenge), time.time())).rowcount
    if not changed:
        fail(503, 'Code delivery unavailable')
    return {'status': 'success', 'challenge_id': challenge, 'contact': masked_contact(row['recipient'])}


@app.post('/api/v1/auth/verify-pin')
def verify_pin(data: VerifyPin, request: Request):
    rate_limit(request, 'verify-pin', 30)
    user = None
    with db() as c:
        c.execute('BEGIN IMMEDIATE')
        row = c.execute('SELECT ch.*,p.active,p.recovery_email FROM auth_challenges ch JOIN people p ON p.id=ch.person_id WHERE ch.hash=? AND ch.ip=? AND ch.expires>? AND ch.delivered=1 AND ch.consumed=0 AND ch.attempts<5',
                        (digest(data.challenge_id), peer(request), time.time())).fetchone()
        if row and row['active'] and row['recipient'] == row['recovery_email']:
            c.execute('UPDATE auth_challenges SET attempts=attempts+1 WHERE hash=?', (row['hash'],))
            if hmac.compare_digest(row['pin_hash'], credential_key(data.challenge_id + ':' + data.code)):
                c.execute('UPDATE auth_challenges SET consumed=1 WHERE hash=?', (row['hash'],))
                user = profile(c.execute('SELECT * FROM people WHERE id=?', (row['person_id'],)).fetchone())
    # Failure is raised after commit so wrong attempts cannot be rolled back.
    if user is None:
        fail(401, 'Invalid or expired code')
    token, _ = issue(user['id'], 'api')
    return {'access_token': token, 'token_type': 'bearer', 'expires_in': TOKEN_TTL, 'profile': user}


class RecoveryRequest(Model):
    surname: str = Field(min_length=1,max_length=100)
    contact: str = Field(min_length=5,max_length=254)


@app.post('/api/v1/auth/recovery',status_code=202)
def recover_id(data: RecoveryRequest,request: Request):
    rate_limit(request,'recovery',5,900)
    # Never disclose matching names, registration status, contact details or the access code.
    with db() as c:
        rid=secrets.token_hex(16)
        c.execute('INSERT INTO recovery_requests(id,surname,contact,created_at) VALUES(?,?,?,?)',(rid,data.surname,data.contact,datetime.now().astimezone().isoformat()))
    return {'status':'pending','message':'Organizer review requested'}


@app.get('/admin/api/recovery')
def recovery_inbox(user=Depends(admin)):
    with db() as c:
        c.execute('BEGIN')
        return admin_response(c, [dict(r) for r in c.execute('SELECT * FROM recovery_requests ORDER BY created_at DESC LIMIT 100')])


@app.get('/api/v1/profile')
def get_profile(user=Depends(current)):
    return user


def normalize_avatar(raw, content_type):
    try:
        with warnings.catch_warnings():
            warnings.simplefilter('error', Image.DecompressionBombWarning)
            with Image.open(BytesIO(raw)) as image:
                expected = {'image/jpeg': 'JPEG', 'image/png': 'PNG', 'image/webp': 'WEBP'}[content_type]
                if image.format != expected or image.width * image.height > 16_000_000:
                    fail(415, 'Invalid or oversized JPEG/PNG image')
                image.verify()
            with Image.open(BytesIO(raw)) as image:
                image.load()
                oriented = ImageOps.exif_transpose(image)
                fitted = ImageOps.fit(oriented.convert('RGB'), (512, 512), Image.Resampling.LANCZOS)
                # A fresh image strips EXIF, text chunks, ICC and all other source metadata.
                clean = Image.new('RGB', fitted.size)
                clean.paste(fitted)
                output = BytesIO()
                clean.save(output, format='WEBP', quality=88, method=6)
                return output.getvalue()
    except (UnidentifiedImageError, OSError, ValueError, SyntaxError,
            Image.DecompressionBombError, Image.DecompressionBombWarning):
        fail(415, 'Invalid JPEG/PNG image')


async def receive_avatar(request: Request):
    # Parse only after authentication, with bounded multipart fields and body size.
    async with request.form(max_files=1, max_fields=0) as form:
        file = form.get('file')
        if len(form.multi_items()) != 1 or not isinstance(file, UploadFile):
            fail(422, 'Exactly one multipart file field named file is required')
        if file.content_type not in ('image/jpeg', 'image/png', 'image/webp'):
            fail(415, 'Only JPEG, PNG and WebP images are supported')
        raw = await file.read(AVATAR_LIMIT + 1)
        if len(raw) > AVATAR_LIMIT:
            fail(413, 'Compressed avatar must not exceed 4 MiB')
        image = await run_in_threadpool(normalize_avatar, raw, file.content_type)
    AVATAR_DIR.mkdir(parents=True, exist_ok=True)
    name = secrets.token_hex(32) + '.webp'
    path = AVATAR_DIR / name
    with path.open('xb') as output:
        output.write(image)
    return '/media/avatars/' + name


@app.post('/admin/api/participant-avatar')
async def organizer_avatar(request: Request,user=Depends(admin)):
    return {'avatar_url':await receive_avatar(request)}


@app.post('/api/v1/profile/avatar')
async def upload_avatar(request: Request,user=Depends(current)):
    url = await receive_avatar(request)
    path = avatar_path(url)
    try:
        with db() as c:
            c.execute('UPDATE people SET avatar_url=? WHERE id=?', (url, user['id']))
            return profile(c.execute('SELECT * FROM people WHERE id=?', (user['id'],)).fetchone())
    except Exception:
        path.unlink(missing_ok=True)
        raise


@app.get('/media/avatars/{name}')
def get_avatar(name: str):
    """Avatars are public to anyone with the unguessable link, not private files."""
    try:
        path = avatar_path('/media/avatars/' + name)
    except ValueError:
        fail()
    return FileResponse(path, media_type='image/webp' if path.suffix=='.webp' else 'image/png', headers={'Cache-Control':'public, max-age=31536000, immutable'})


class ProfilePatch(Model):
    name: str | None = Field(default=None,min_length=1,max_length=160)
    bio: str | None = Field(default=None,max_length=4000)
    company: str | None = Field(default=None,max_length=200)
    position: str | None = Field(default=None,max_length=200)


@app.patch('/api/v1/profile')
def update_profile(data: ProfilePatch, user=Depends(current)):
    changes = data.model_dump(exclude_none=True)
    with db() as c:
        for key,value in changes.items():
            c.execute(f'UPDATE people SET {key}=? WHERE id=?', (value,user['id']))
        return profile(c.execute('SELECT * FROM people WHERE id=?',(user['id'],)).fetchone())


def content(c,kind):
    return [json.loads(r['data']) for r in c.execute('SELECT data FROM content WHERE kind=? ORDER BY id',(kind,))]


@app.get('/api/v1/bootstrap')
def bootstrap(user=Depends(current)):
    with db() as c:
        result = {kind:[item for item in content(c,kind) if item.get('label') in ('PUBLISHED','ARCHIVE2026','ADAPTED2027') and not(kind=='sessions' and item.get('label')=='ARCHIVE2026' and c.execute("SELECT 1 FROM metadata WHERE key='adapted_programme_2027'").fetchone())] for kind in ('sessions','posts','partners')}
        result['people'] = [profile(r) for r in c.execute("SELECT * FROM people WHERE active=1 AND id NOT IN ('admin','admin_viewer') ORDER BY name")]
        result['people'].extend({'id':x['id'],'name':x['name'],'role':'speaker_archive2026','position':x['description'],'bio':x['description'],'company':'Сириус.Биотех 2026','avatar_url':SPEAKER_PHOTOS.get(x['id'],x.get('avatar_url','')),'archive':True,'year':2026,'source_url':x['source_url']} for x in content(c,'speakers'))
        return result


def require_chat(c, chat_id, user):
    row = c.execute('SELECT * FROM chats WHERE id=? AND (a=? OR b=?)',(chat_id,user['id'],user['id'])).fetchone()
    if not row:
        fail()
    return row


def chat_view(c,row,user):
    other = row['b'] if row['a']==user['id'] else row['a']
    person = c.execute('SELECT name,avatar_url FROM people WHERE id=?',(other,)).fetchone()
    last = c.execute('SELECT id,text,created_at,attachment_id FROM messages WHERE chat_id=? ORDER BY id DESC LIMIT 1',(row['id'],)).fetchone()
    state = c.execute('SELECT * FROM chat_states WHERE chat_id=? AND person_id=?',(row['id'],user['id'])).fetchone()
    read = state['last_read'] if state else 0
    unread = c.execute('SELECT COUNT(*) FROM messages WHERE chat_id=? AND sender_id!=? AND id>?',(row['id'],user['id'],read)).fetchone()[0]
    return {'id':row['id'],'person_id':other,'title':person['name'],'avatar_url':person['avatar_url'] or '', 'last_message':(last['text'] or ('Фото' if last['attachment_id'] else '')) if last else '', 'updated_at':last['created_at'] if last else None,'last_message_id':last['id'] if last else 0,'unread':unread,'hidden':bool(state and state['hidden']),'muted':bool(state and state['muted'])}


class ChatState(Model):
    hidden: bool | None = None
    muted: bool | None = None
    last_read: int | None = Field(default=None,ge=0)


@app.put('/api/v1/chats/{chat_id}/state')
def update_chat_state(chat_id: str,data: ChatState,user=Depends(current)):
    with db() as c:
        c.execute('BEGIN IMMEDIATE')
        row = require_chat(c,chat_id,user)
        if data.last_read and not c.execute('SELECT 1 FROM messages WHERE chat_id=? AND id=?',(chat_id,data.last_read)).fetchone():
            fail(422,'Read marker does not belong to this chat')
        c.execute('INSERT OR IGNORE INTO chat_states(chat_id,person_id) VALUES(?,?)',(chat_id,user['id']))
        for key,value in data.model_dump(exclude_none=True).items():
            if key=='last_read':
                c.execute('UPDATE chat_states SET last_read=MAX(last_read,?) WHERE chat_id=? AND person_id=?',(value,chat_id,user['id']))
            else:
                c.execute('UPDATE chat_states SET '+key+'=? WHERE chat_id=? AND person_id=?',(int(value),chat_id,user['id']))
        return chat_view(c,row,user)


@app.get('/api/v1/chats')
def chats(limit: int=Query(50,ge=1,le=100),offset: int=Query(0,ge=0),user=Depends(current)):
    with db() as c:
        return [chat_view(c,r,user) for r in c.execute('SELECT * FROM chats WHERE a=? OR b=? ORDER BY id LIMIT ? OFFSET ?',(user['id'],user['id'],limit,offset))]


class PersonRef(Model):
    person_id: str = Field(min_length=1,max_length=100)


@app.post('/api/v1/chats',status_code=201)
def create_chat(data: PersonRef,user=Depends(current)):
    if data.person_id == user['id']:
        fail(422,'Cannot create a direct conversation with yourself')
    a,b = sorted((data.person_id,user['id']))
    with db() as c:
        if not c.execute('SELECT 1 FROM people WHERE id=? AND active=1',(data.person_id,)).fetchone():
            fail()
        c.execute('INSERT OR IGNORE INTO chats VALUES(?,?,?)',(secrets.token_hex(16),a,b))
        return chat_view(c,c.execute('SELECT * FROM chats WHERE a=? AND b=?',(a,b)).fetchone(),user)


class Text(Model):
    text: str = Field(min_length=1,max_length=10000)


class Message(Model):
    text: str = Field(default='',max_length=10000)
    client_message_id: UUID | None = None
    attachment_id: str | None = Field(default=None,pattern=r'^[0-9a-f]{64}$')


@app.get('/api/v1/chats/{chat_id}/messages')
def messages(chat_id: str,limit: int=Query(50,ge=1,le=100),offset: int=Query(0,ge=0),user=Depends(current)):
    with db() as c:
        require_chat(c,chat_id,user)
        return [dict(r) for r in c.execute('SELECT * FROM messages WHERE chat_id=? ORDER BY id LIMIT ? OFFSET ?',(chat_id,limit,offset))]


@app.post('/api/v1/chats/{chat_id}/messages',status_code=201)
def send_message(chat_id: str,data: Message,request: Request,user=Depends(current)):
    rate_limit(request,'message:'+user['id'],120,60)
    with db() as c:
        # Serialize retries before checking the per-chat, per-sender key.
        c.execute('BEGIN IMMEDIATE')
        require_chat(c,chat_id,user)
        client_id = str(data.client_message_id) if data.client_message_id is not None else None
        if client_id is not None:
            existing = c.execute('SELECT * FROM messages WHERE chat_id=? AND sender_id=? AND client_message_id=?',(chat_id,user['id'],client_id)).fetchone()
            if existing:
                if existing['text'] != data.text or existing['attachment_id'] != data.attachment_id:
                    fail(409,'client_message_id already used with different text')
                return dict(existing)
        if not data.text.strip() and not data.attachment_id:
            fail(422,'Message must contain text or a photo')
        if data.attachment_id and not c.execute('SELECT 1 FROM chat_images WHERE id=? AND chat_id=? AND uploader_id=? AND message_id IS NULL',(data.attachment_id,chat_id,user['id'])).fetchone():
            fail(404,'Attachment unavailable')
        cursor = c.execute('INSERT INTO messages(chat_id,sender_id,text,created_at,client_message_id,attachment_id) VALUES(?,?,?,?,?,?)',(chat_id,user['id'],data.text,datetime.now().astimezone().isoformat(),client_id,data.attachment_id))
        if data.attachment_id:
            c.execute('UPDATE chat_images SET message_id=? WHERE id=?',(cursor.lastrowid,data.attachment_id))
        c.execute('UPDATE chat_states SET hidden=0 WHERE chat_id=? AND person_id!=?',(chat_id,user['id']))
        return dict(c.execute('SELECT * FROM messages WHERE id=?',(cursor.lastrowid,)).fetchone())


def normalize_chat_image(raw, content_type):
    try:
        with warnings.catch_warnings():
            warnings.simplefilter('error', Image.DecompressionBombWarning)
            with Image.open(BytesIO(raw)) as image:
                expected={'image/jpeg':'JPEG','image/png':'PNG','image/webp':'WEBP'}[content_type]
                if image.format != expected or image.width * image.height > 32_000_000:
                    fail(415,'Invalid or oversized image')
                image.verify()
            with Image.open(BytesIO(raw)) as image:
                image.load()
                image=ImageOps.exif_transpose(image).convert('RGB')
                image.thumbnail((2560,2560),Image.Resampling.LANCZOS)
                for quality in (88,78,68,58,48):
                    output=BytesIO()
                    image.save(output,format='JPEG',quality=quality,optimize=True)
                    if output.tell() <= AVATAR_LIMIT:
                        return output.getvalue()
                fail(413,'Image cannot be compressed to 4 MiB')
    except (UnidentifiedImageError,OSError,ValueError,SyntaxError,Image.DecompressionBombError,Image.DecompressionBombWarning):
        fail(415,'Invalid image')


@app.post('/api/v1/chats/{chat_id}/attachments', status_code=201)
async def upload_chat_image(chat_id: str, request: Request, user=Depends(current)):
    rate_limit(request,'chat-image:'+user['id'],30,60)
    with db() as c:
        require_chat(c,chat_id,user)
        expired=[r['id'] for r in c.execute('SELECT id FROM chat_images WHERE uploader_id=? AND message_id IS NULL AND created<?',(user['id'],time.time()-86400))]
        c.executemany('DELETE FROM chat_images WHERE id=?',[(i,) for i in expired])
        if c.execute('SELECT COUNT(*) FROM chat_images WHERE uploader_id=? AND message_id IS NULL',(user['id'],)).fetchone()[0]>=20:
            fail(429,'Too many pending images')
    for image_id in expired:
        (CHAT_IMAGE_DIR / (image_id+'.jpg')).unlink(missing_ok=True)
    async with request.form(max_files=1,max_fields=0) as form:
        file=form.get('file')
        if len(form.multi_items())!=1 or not isinstance(file,UploadFile):
            fail(422,'Exactly one file is required')
        if file.content_type not in ('image/jpeg','image/png','image/webp'):
            fail(415,'Only JPEG, PNG and WebP images are supported')
        raw=await file.read(AVATAR_LIMIT+1)
        if len(raw)>AVATAR_LIMIT:
            fail(413,'Compressed image must not exceed 4 MiB')
        image=await run_in_threadpool(normalize_chat_image,raw,file.content_type)
    CHAT_IMAGE_DIR.mkdir(parents=True,exist_ok=True)
    image_id=secrets.token_hex(32)
    path=CHAT_IMAGE_DIR / (image_id+'.jpg')
    try:
        with path.open('xb') as output:
            os.chmod(path,0o600)
            output.write(image)
        with db() as c:
            require_chat(c,chat_id,user)
            c.execute('INSERT INTO chat_images(id,chat_id,uploader_id,created) VALUES(?,?,?,?)',(image_id,chat_id,user['id'],time.time()))
    except Exception:
        path.unlink(missing_ok=True)
        raise
    return {'attachment_id':image_id}


@app.get('/api/v1/chats/{chat_id}/attachments/{image_id}')
def get_chat_image(chat_id: str,image_id: str,user=Depends(current)):
    if not re.fullmatch(r'[0-9a-f]{64}',image_id):
        fail()
    with db() as c:
        require_chat(c,chat_id,user)
        if not c.execute('SELECT 1 FROM chat_images WHERE id=? AND chat_id=? AND message_id IS NOT NULL',(image_id,chat_id)).fetchone():
            fail()
    path=CHAT_IMAGE_DIR / (image_id+'.jpg')
    if path.is_symlink() or not path.is_file():
        fail()
    return FileResponse(path,media_type='image/jpeg',headers={'Cache-Control':'private, no-store','X-Content-Type-Options':'nosniff'})


class Meeting(Model):
    person_id: str = Field(min_length=1,max_length=100)
    starts_at: datetime
    location: str = Field(min_length=1,max_length=300)
    title: str = Field(min_length=1,max_length=200)

    @field_validator('starts_at')
    @classmethod
    def timezone_required(cls,value):
        if value.tzinfo is None:
            raise ValueError('Timezone is required')
        return value


class MeetingPatch(Model):
    status: Literal['accepted','declined','cancelled']


class AvailabilityInterval(Model):
    start: datetime
    end: datetime

    @field_validator('start','end')
    @classmethod
    def timezone_required(cls,value):
        if value.tzinfo is None:
            raise ValueError('Timezone is required')
        return value

    @field_validator('end')
    @classmethod
    def ordered(cls,value,info):
        if 'start' in info.data and (value<=info.data['start'] or value-info.data['start']>timedelta(days=1)):
            raise ValueError('An interval must be positive and no longer than one day')
        return value


class Availability(Model):
    items: list[AvailabilityInterval] = Field(max_length=100)


def available_intervals(c,person_id):
    row = c.execute('SELECT data FROM meeting_availability WHERE person_id=?',(person_id,)).fetchone()
    return json.loads(row['data']) if row else []


def validate_meeting_slot(c,owner_id,person_id,start,exclude_id=None):
    end=start+timedelta(minutes=30)
    for row in c.execute("SELECT id,starts_at FROM meetings WHERE status IN ('pending','accepted') AND (owner_id IN (?,?) OR person_id IN (?,?))",(owner_id,person_id,owner_id,person_id)):
        previous=datetime.fromisoformat(row['starts_at'])
        if row['id']!=exclude_id and start<previous+timedelta(minutes=30) and end>previous:
            fail(409,'Selected time is already booked')


@app.get('/api/v1/meeting-availability')
def get_availability(user=Depends(current)):
    with db() as c:
        return {'items':available_intervals(c,user['id'])}


@app.put('/api/v1/meeting-availability')
def set_availability(data: Availability,user=Depends(current)):
    items = [item.model_dump(mode='json') for item in data.items]
    with db() as c:
        c.execute('INSERT INTO meeting_availability(person_id,data) VALUES(?,?) ON CONFLICT(person_id) DO UPDATE SET data=excluded.data',(user['id'],json.dumps(items)))
    return {'items':items}


@app.get('/api/v1/meeting-options')
def meeting_options(person_id: str=Query(min_length=1,max_length=100),user=Depends(current)):
    with db() as c:
        if person_id==user['id'] or not c.execute('SELECT 1 FROM people WHERE id=? AND active=1 AND deleted=0',(person_id,)).fetchone():
            fail(422,'Invalid participant')
        busy=[]
        for row in c.execute("SELECT starts_at FROM meetings WHERE status IN ('pending','accepted') AND (owner_id IN (?,?) OR person_id IN (?,?))",(person_id,user['id'],person_id,user['id'])):
            start=datetime.fromisoformat(row['starts_at'])
            busy.append({'start':start.isoformat(),'end':(start+timedelta(minutes=30)).isoformat()})
        return {'busy':busy,'available':{'self':available_intervals(c,user['id']),'peer':available_intervals(c,person_id)}}


@app.get('/api/v1/meetings')
def meetings(user=Depends(current)):
    with db() as c:
        return [dict(r) for r in c.execute('SELECT * FROM meetings WHERE owner_id=? OR person_id=? ORDER BY starts_at',(user['id'],user['id']))]


@app.post('/api/v1/meetings',status_code=201)
def create_meeting(data: Meeting,user=Depends(current)):
    with db() as c:
        c.execute('BEGIN IMMEDIATE')
        if data.person_id==user['id'] or not c.execute('SELECT 1 FROM people WHERE id=? AND active=1',(data.person_id,)).fetchone():
            fail(422,'Invalid participant')
        validate_meeting_slot(c,user['id'],data.person_id,data.starts_at)
        mid = secrets.token_hex(16)
        c.execute('INSERT INTO meetings VALUES(?,?,?,?,?,?,?)',(mid,user['id'],data.person_id,data.starts_at.isoformat(),data.location,data.title,'pending'))
        return dict(c.execute('SELECT * FROM meetings WHERE id=?',(mid,)).fetchone())


@app.patch('/api/v1/meetings/{meeting_id}')
def patch_meeting(meeting_id: str,data: MeetingPatch,user=Depends(current)):
    with db() as c:
        c.execute('BEGIN IMMEDIATE')
        row = c.execute('SELECT * FROM meetings WHERE id=? AND (owner_id=? OR person_id=?)',(meeting_id,user['id'],user['id'])).fetchone()
        if not row:
            fail()
        if (user['id']==row['owner_id'] and data.status!='cancelled') or (user['id']==row['person_id'] and data.status=='cancelled'):
            fail(403,'Only invitees accept/decline; only owners cancel')
        if row['status'] in ('cancelled','declined'):
            fail(409,'Meeting is closed')
        if data.status=='accepted':
            validate_meeting_slot(c,row['owner_id'],row['person_id'],datetime.fromisoformat(row['starts_at']),row['id'])
        c.execute('UPDATE meetings SET status=? WHERE id=?',(data.status,meeting_id))
        return dict(c.execute('SELECT * FROM meetings WHERE id=?',(meeting_id,)).fetchone())


class Favorite(Model):
    kind: Literal['sessions','people','posts','partners']
    item_id: str = Field(min_length=1,max_length=100)


class Favorites(Model):
    items: list[Favorite] = Field(max_length=1000)


@app.get('/api/v1/favorites')
def favorites(user=Depends(current)):
    with db() as c:
        return {'items':[dict(r) for r in c.execute('SELECT kind,item_id FROM favorites WHERE person_id=? ORDER BY kind,item_id',(user['id'],))]}


@app.put('/api/v1/favorites')
def set_favorites(data: Favorites,user=Depends(current)):
    with db() as c:
        for item in data.items:
            if item.kind=='people':
                exists = c.execute('SELECT 1 FROM people WHERE id=? AND active=1',(item.item_id,)).fetchone() or c.execute("SELECT 1 FROM content WHERE kind='speakers' AND id=?",(item.item_id,)).fetchone()
            else:
                exists = c.execute('SELECT 1 FROM content WHERE kind=? AND id=?',(item.kind,item.item_id)).fetchone()
            if not exists:
                fail(422,'Unknown favorite item')
        c.execute('DELETE FROM favorites WHERE person_id=?',(user['id'],))
        c.executemany('INSERT OR IGNORE INTO favorites VALUES(?,?,?)',[(user['id'],i.kind,i.item_id) for i in data.items])
    return favorites(user)


def require_session(c,sid):
    row = c.execute("SELECT data FROM content WHERE kind='sessions' AND id=?",(sid,)).fetchone()
    if not row or json.loads(row['data']).get('label') not in ('PUBLISHED','ARCHIVE2026','ADAPTED2027'):
        fail()


@app.post('/api/v1/sessions/{session_id}/questions',status_code=201)
def question(session_id: str,data: Text,user=Depends(current)):
    with db() as c:
        require_session(c,session_id)
        qid = secrets.token_hex(16)
        now = datetime.now().astimezone().isoformat()
        c.execute('INSERT INTO questions(id,person_id,session_id,text,created_at,updated_at) VALUES(?,?,?,?,?,?)',(qid,user['id'],session_id,data.text,now,now))
        return dict(c.execute('SELECT * FROM questions WHERE id=?',(qid,)).fetchone())


@app.get('/api/v1/sessions/{session_id}/questions')
def own_questions(session_id: str,limit: int=Query(50,ge=1,le=100),offset: int=Query(0,ge=0),user=Depends(current)):
    with db() as c:
        require_session(c,session_id)
        return [dict(r) for r in c.execute('SELECT * FROM questions WHERE session_id=? AND person_id=? ORDER BY created_at,id LIMIT ? OFFSET ?',(session_id,user['id'],limit,offset))]


class Rating(Model):
    value: int = Field(ge=1,le=5,strict=True)


@app.put('/api/v1/sessions/{session_id}/rating')
def rating(session_id: str,data: Rating,user=Depends(current)):
    with db() as c:
        require_session(c,session_id)
        c.execute('INSERT INTO ratings(person_id,session_id,value,updated_at) VALUES(?,?,?,?) ON CONFLICT(person_id,session_id) DO UPDATE SET value=excluded.value,updated_at=excluded.updated_at',(user['id'],session_id,data.value,datetime.now().astimezone().isoformat()))
    return {'session_id':session_id,'value':data.value}


@app.get('/api/v1/sessions/{session_id}/rating')
def own_rating(session_id: str,user=Depends(current)):
    with db() as c:
        require_session(c,session_id)
        row = c.execute('SELECT session_id,value,updated_at FROM ratings WHERE session_id=? AND person_id=?',(session_id,user['id'])).fetchone()
        return dict(row) if row else {'session_id':session_id,'value':None,'updated_at':None}


@app.get('/admin',include_in_schema=False)
def admin_page():
    return FileResponse(ROOT / 'static/admin.html')


@app.post('/admin/login')
def admin_login(data: Login,request: Request,response: Response):
    if request.headers.get('origin') and request.headers['origin'] != str(request.base_url).rstrip('/'):
        fail(403,'Cross-origin login denied')
    rate_limit(request,'admin-login')
    user = authenticate(data.code)
    if user['role'] not in ('admin','organizer','viewer'):
        fail(403,'Organizer access required')
    token,csrf = issue(user['id'],'web')
    response.set_cookie(COOKIE,token,httponly=True,secure=os.getenv('COOKIE_SECURE','true').lower()=='true',samesite='strict',max_age=TOKEN_TTL,path='/admin')
    return {'csrf_token':csrf,'profile':user}


@app.post('/admin/logout')
def logout(request: Request,response: Response,user=Depends(admin)):
    with db() as c:
        c.execute('DELETE FROM tokens WHERE hash=?',(digest(request.cookies.get(COOKIE,'')),))
    response.delete_cookie(COOKIE,path='/admin')
    return {'ok':True}


@app.get('/admin/api/session')
def admin_session(user=Depends(admin)):
    return {'profile':user}


class Participant(Model):
    registration_key: UUID | None = None
    first_name: str = Field(default='', max_length=100)
    patronymic: str = Field(default='', max_length=100)
    send_email: bool = False
    last_name: str = Field(default='', max_length=100)
    recovery_email: str = Field(default='', max_length=254)
    _email = field_validator('recovery_email')(recovery_address)

    name: str = Field(min_length=1,max_length=160)
    company: str = Field(default='',max_length=200)
    position: str = Field(default='',max_length=200)
    bio: str = Field(default='',max_length=4000)
    role: Literal['attendee','organizer'] = 'attendee'
    avatar_url: str = Field(default='', max_length=128)

    _avatar = field_validator('avatar_url')(controlled_avatar)


class ParticipantPatch(ProfilePatch):
    first_name: str | None = Field(default=None, max_length=100)
    patronymic: str | None = Field(default=None, max_length=100)
    last_name: str | None = Field(default=None, max_length=100)
    recovery_email: str | None = Field(default=None, max_length=254)

    @field_validator('recovery_email')
    @classmethod
    def validate_recovery_email(cls, value):
        return recovery_address(value) if value is not None else None

    avatar_url: str | None = Field(default=None, max_length=128)

    @field_validator('avatar_url')
    @classmethod
    def validate_avatar(cls, value):
        return controlled_avatar(value) if value is not None else None

    active: bool | None = None
    role: Literal['attendee','organizer'] | None = None


def new_code(c):
    for _ in range(50):
        code = f'{secrets.randbelow(1000000):06d}'
        if not c.execute('SELECT 1 FROM people WHERE code_key=?',(credential_key(code),)).fetchone():
            return code
    fail(503,'Unable to allocate access code')


def admin_response(c, data):
    revision = c.execute("SELECT value FROM metadata WHERE key='admin_revision'").fetchone()[0]
    return JSONResponse(data,headers={'X-SBS-Revision':revision})


@app.get('/admin/api/revision')
def admin_revision(user=Depends(admin)):
    with db() as c:
        return {'revision':c.execute("SELECT value FROM metadata WHERE key='admin_revision'").fetchone()[0]}


@app.get('/admin/api/participants')
def participants(user=Depends(admin)):
    with db() as c:
        c.execute('BEGIN')
        return admin_response(c, [{**profile(r),'active':bool(r['active']),'last_name':r['last_name'],'recovery_email':r['recovery_email'],'first_name':r['first_name'],'patronymic':r['patronymic'],'code_available':bool(r['access_code_cipher']),'mail_status': dict(mail_row) if (mail_row := c.execute('SELECT id,status,updated_at,last_error FROM mail_outbox WHERE person_id=? ORDER BY created_at DESC LIMIT 1',(r['id'],)).fetchone()) else None} for r in c.execute("SELECT * FROM people WHERE deleted=0 AND id NOT IN ('admin','admin_viewer') ORDER BY name")])


@app.post('/admin/api/participants',status_code=201)
def create_participant(data: Participant,user=Depends(admin)):
    if data.role=='organizer' and user['role']!='admin':
        fail(403,'Only admin can grant organizer access')
    with db() as c:
        c.execute('BEGIN IMMEDIATE')
        registration_key = str(data.registration_key) if data.registration_key else None
        fingerprint = digest(json.dumps(data.model_dump(exclude={'registration_key'}),sort_keys=True,ensure_ascii=False))
        if registration_key:
            existing = c.execute('SELECT * FROM people WHERE registration_key=?',(registration_key,)).fetchone()
            if existing:
                if existing['deleted']:
                    fail(409,'Эта регистрация была удалена. Создайте новую регистрацию.')
                if existing['registration_fingerprint'] != fingerprint:
                    fail(409,'Эта регистрация уже сохранена с другими данными. Обновите список участников.')
                latest = c.execute('SELECT id FROM mail_outbox WHERE person_id=? ORDER BY created_at DESC LIMIT 1',(existing['id'],)).fetchone()
                return {'profile':profile(existing),'code':mail.unseal(existing['access_code_cipher']),'mail_id':latest['id'] if latest else None}
        pid,code = secrets.token_hex(16),new_code(c)
        c.execute('INSERT INTO people(id,name,role,bio,company,position,avatar_url,code_hash,code_key,last_name,recovery_email) VALUES(?,?,?,?,?,?,?,?,?,?,?)',(pid,data.name,data.role,data.bio,data.company,data.position,data.avatar_url,password_hash(code),credential_key(code),data.last_name,data.recovery_email))
        c.execute('UPDATE people SET first_name=?,patronymic=?,access_code_cipher=?,registration_key=?,registration_fingerprint=? WHERE id=?',(data.first_name,data.patronymic,mail.seal(code),registration_key,fingerprint,pid))
        person = c.execute('SELECT * FROM people WHERE id=?',(pid,)).fetchone()
        mail_id = None
        if data.send_email:
            try:
                mail_id = mail.queue_invitation(c,person,code)
            except ValueError as error:
                fail(422,str(error))
        return {'profile':profile(person),'code':code,'mail_id':mail_id}



def manageable(c,pid,user):
    row = c.execute('SELECT * FROM people WHERE id=?',(pid,)).fetchone()
    if not row or row['deleted']:
        fail()
    if pid in ('admin', VIEWER_ID) or (row['role']=='organizer' and user['role']!='admin'):
        fail(403,'Protected organizer account')
    return row


@app.patch('/admin/api/participants/{pid}')
def patch_participant(pid: str,data: ParticipantPatch,user=Depends(admin)):
    if data.role=='organizer' and user['role']!='admin':
        fail(403,'Only admin can grant organizer access')
    with db() as c:
        c.execute('BEGIN IMMEDIATE')
        manageable(c,pid,user)
        if any(k in data.model_fields_set for k in ('last_name', 'recovery_email', 'active')):
            c.execute('DELETE FROM auth_selections WHERE person_id=?', (pid,))
            c.execute('DELETE FROM auth_challenges WHERE person_id=?', (pid,))
        for key,value in data.model_dump(exclude_none=True).items():
            c.execute(f'UPDATE people SET {key}=? WHERE id=?',(value,pid))
        c.execute('DELETE FROM tokens WHERE person_id=?',(pid,))
        return profile(c.execute('SELECT * FROM people WHERE id=?',(pid,)).fetchone())


@app.delete('/admin/api/participants/{pid}')
def delete_participant(pid: str,user=Depends(admin)):
    # Remove the account from participant lists while preserving chat references.
    with db() as c:
        c.execute('BEGIN IMMEDIATE')
        manageable(c,pid,user)
        c.execute("UPDATE people SET active=0,deleted=1,code_hash=NULL,code_key=NULL,access_code_cipher='' WHERE id=?",(pid,))
        c.execute("UPDATE mail_outbox SET status='cancelled',payload='' WHERE person_id=? AND status='queued'",(pid,))
        c.execute('DELETE FROM tokens WHERE person_id=?',(pid,))
        c.execute('DELETE FROM auth_selections WHERE person_id=?',(pid,))
        c.execute('DELETE FROM auth_challenges WHERE person_id=?',(pid,))
    return {'ok':True}


@app.post('/admin/api/participants/{pid}/code')
def rotate_code(pid: str,user=Depends(admin)):
    with db() as c:
        c.execute('BEGIN IMMEDIATE')
        manageable(c,pid,user)
        code = new_code(c)
        c.execute('UPDATE people SET code_hash=?,code_key=?,access_code_cipher=? WHERE id=?',(password_hash(code),credential_key(code),mail.seal(code),pid))
        c.execute("UPDATE mail_outbox SET status='cancelled',payload='' WHERE person_id=? AND status='queued'",(pid,))
        c.execute('DELETE FROM tokens WHERE person_id=?',(pid,))
        return {'code':code}


@app.post('/admin/api/participants/{pid}/code/view')
def view_participant_code(pid: str,user=Depends(admin)):
    with db() as c:
        person = manageable(c,pid,user)
        if not person['access_code_cipher'] or not person['active']:
            fail(409,'Для старого профиля создайте новый код; прежний код восстановить нельзя.')
        return {'code':mail.unseal(person['access_code_cipher'])}


@app.get('/admin/api/mail/status')
def mail_status(user=Depends(admin)):
    return {'configured':mail.delivery_ready(),'transport':'smtp','sender_name':mail.SENDER_NAME,'sender_address':os.environ.get('SBS_SMTP_FROM',''),'domain':os.environ.get('SBS_MAIL_DOMAIN','')}


@app.post('/admin/api/participants/{pid}/invitation',status_code=202)
def send_invitation(pid: str,user=Depends(admin)):
    with db() as c:
        c.execute('BEGIN IMMEDIATE')
        person = manageable(c,pid,user)
        if not person['access_code_cipher']:
            fail(409,'Для старого профиля сначала создайте новый код.')
        try:
            identifier = mail.queue_invitation(c,person,mail.unseal(person['access_code_cipher']))
        except ValueError as error:
            fail(422,str(error))
        return {'id':identifier,'status':c.execute('SELECT status FROM mail_outbox WHERE id=?',(identifier,)).fetchone()['status']}


@app.get('/mail/unsubscribe/{token}',response_class=HTMLResponse)
def unsubscribe_page(token: str):
    with db() as c:
        row = c.execute('SELECT unsubscribed FROM mail_subscriptions WHERE token=?',(token,)).fetchone()
    if not row:fail()
    text = 'Вы уже отказались от писем.' if row['unsubscribed'] else 'Отказаться от писем Sirius Biotech Summit?'
    form = '' if row['unsubscribed'] else '<form method="post"><button type="submit">Отказаться от писем</button></form>'
    return '<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Sirius Biotech Summit</title><body><h1>Sirius Biotech Summit</h1><p>'+text+'</p>'+form+'</body></html>'


@app.post('/mail/unsubscribe/{token}',response_class=HTMLResponse)
def unsubscribe_mail(token: str):
    with db() as c:
        c.execute('BEGIN IMMEDIATE')
        row = c.execute('SELECT * FROM mail_subscriptions WHERE token=?',(token,)).fetchone()
        if not row:fail()
        c.execute('UPDATE mail_subscriptions SET unsubscribed=1 WHERE token=?',(token,))
        c.execute("UPDATE mail_outbox SET status='cancelled',payload='' WHERE person_id=? AND recipient=? AND status='queued'",(row['person_id'],row['email']))
    return '<!doctype html><html lang="ru"><meta charset="utf-8"><title>Sirius Biotech Summit</title><body><h1>Вы отказались от писем Sirius Biotech Summit</h1><p>Доступ к приложению сохранён.</p></body></html>'


@app.get('/admin/api/questions')
def question_inbox(status: Literal['pending','answered','hidden'] | None=None,session_id: str | None=Query(None,max_length=100),limit: int=Query(50,ge=1,le=100),offset: int=Query(0,ge=0),user=Depends(admin)):
    with db() as c:
        c.execute('BEGIN')
        return admin_response(c, [dict(r) for r in c.execute("""SELECT q.*,p.name AS person_name FROM questions q
            JOIN people p ON p.id=q.person_id
            WHERE p.deleted=0 AND EXISTS (SELECT 1 FROM content c WHERE c.kind='sessions' AND c.id=q.session_id) AND (? IS NULL OR q.status=?) AND (? IS NULL OR q.session_id=?)
            ORDER BY q.created_at DESC,q.id LIMIT ? OFFSET ?""",(status,status,session_id,session_id,limit,offset))])


class QuestionModeration(Model):
    status: Literal['pending','answered','hidden']
    response: str = Field(default='',max_length=10000)


@app.patch('/admin/api/questions/{question_id}')
def moderate_question(question_id: str,data: QuestionModeration,user=Depends(admin)):
    if data.status=='answered' and not data.response:
        fail(422,'An answered question requires a response')
    with db() as c:
        if not c.execute('UPDATE questions SET status=?,response=?,updated_at=? WHERE id=?',(data.status,data.response,datetime.now().astimezone().isoformat(),question_id)).rowcount:
            fail()
        return dict(c.execute('SELECT * FROM questions WHERE id=?',(question_id,)).fetchone())


@app.get('/admin/api/ratings')
def rating_inbox(session_id: str | None=Query(None,max_length=100),limit: int=Query(50,ge=1,le=100),offset: int=Query(0,ge=0),user=Depends(admin)):
    with db() as c:
        c.execute('BEGIN')
        return admin_response(c, [dict(r) for r in c.execute("""SELECT r.*,p.name AS person_name FROM ratings r
            JOIN people p ON p.id=r.person_id WHERE p.deleted=0 AND EXISTS (SELECT 1 FROM content c WHERE c.kind='sessions' AND c.id=r.session_id) AND (? IS NULL OR r.session_id=?)
            ORDER BY r.updated_at DESC,r.session_id,r.person_id LIMIT ? OFFSET ?""",(session_id,session_id,limit,offset))])


@app.delete('/admin/api/ratings/{session_id}/{person_id}')
def delete_rating(session_id: str,person_id: str,user=Depends(admin)):
    with db() as c:
        if not c.execute('DELETE FROM ratings WHERE session_id=? AND person_id=?',(session_id,person_id)).rowcount:
            fail()
    return {'ok':True}


class Entry(Model):
    title: str = Field(min_length=1,max_length=300)
    description: str = Field(default='',max_length=20000)
    date: str = Field(default='',max_length=10)
    start_time: str = Field(default='',max_length=5)
    end_time: str = Field(default='',max_length=5)
    room: str = Field(default='',max_length=200)
    url: str = Field(default='',max_length=2000)
    label: str = Field(default='DRAFT',max_length=40)


def kind_check(kind):
    if kind not in ('sessions','posts','partners'):
        fail()


@app.get('/admin/api/{kind}')
def admin_content(kind: str,user=Depends(admin)):
    kind_check(kind)
    with db() as c:
        c.execute('BEGIN')
        return admin_response(c, content(c,kind))


@app.post('/admin/api/{kind}',status_code=201)
def add_content(kind: str,data: Entry,user=Depends(admin)):
    kind_check(kind)
    item = {'id':secrets.token_hex(16),**data.model_dump()}
    with db() as c:
        c.execute('INSERT INTO content VALUES(?,?,?)',(kind,item['id'],json.dumps(item)))
    return item


@app.put('/admin/api/{kind}/{item_id}')
def edit_content(kind: str,item_id: str,data: Entry,user=Depends(admin)):
    kind_check(kind)
    with db() as c:
        row = c.execute('SELECT data FROM content WHERE kind=? AND id=?',(kind,item_id)).fetchone()
        if not row:
            fail()
        old = json.loads(row['data'])
        if old.get('label')=='ARCHIVE2026':
            fail(409,'Archival source records are immutable; create a new draft instead')
        item = {'id':item_id,**data.model_dump()}
        c.execute('UPDATE content SET data=? WHERE kind=? AND id=?',(json.dumps(item),kind,item_id))
    return item


@app.delete('/admin/api/{kind}/{item_id}')
def delete_content(kind: str,item_id: str,user=Depends(admin)):
    kind_check(kind)
    with db() as c:
        if not c.execute('DELETE FROM content WHERE kind=? AND id=?',(kind,item_id)).rowcount:
            fail()
        c.execute('DELETE FROM favorites WHERE kind=? AND item_id=?',(kind,item_id))
    return {'ok':True}

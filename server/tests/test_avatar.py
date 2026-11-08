import importlib.util
import os
import secrets
import sqlite3
from io import BytesIO
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from PIL import Image

spec = importlib.util.spec_from_file_location('sbs_avatar_main', Path(__file__).parents[1] / 'main.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setenv('ADMIN_CODE', secrets.token_urlsafe(32))
    monkeypatch.setenv('COOKIE_SECURE', 'false')
    monkeypatch.setattr(m, 'DB', str(tmp_path / 'test.sqlite3'))
    monkeypatch.setattr(m, 'AVATAR_DIR', tmp_path / 'uploads')
    with TestClient(m.app) as c:
        yield c


def account(c):
    login = c.post('/admin/login', json={'code': os.environ['ADMIN_CODE']}).json()
    admin = {'X-CSRF-Token': login['csrf_token']}
    result = c.post('/admin/api/participants', headers=admin, json={'name': 'Test Guest'}).json()
    token = c.post('/api/v1/auth/login', json={'code': result['code']}).json()
    return admin, {'Authorization': 'Bearer ' + token['access_token']}, result['profile']


def picture(fmt='PNG'):
    stream = BytesIO()
    image = Image.new('RGB', (700, 350), 'blue')
    exif = Image.Exif()
    exif[270] = 'private metadata'
    image.save(stream, format=fmt, exif=exif)
    return stream.getvalue()


def upload(c, headers, raw=None, mime='image/png', filename='photo.png'):
    return c.post('/api/v1/profile/avatar', headers=headers,
                  files={'file': (filename, picture() if raw is None else raw, mime)})


@pytest.mark.parametrize('fmt,mime', [('PNG', 'image/png'), ('JPEG', 'image/jpeg')])
def test_upload_normalizes_and_exposes_public_link(client, fmt, mime):
    _, headers, person = account(client)
    assert person['avatar_url'] == ''
    result = upload(client, headers, picture(fmt), mime, '../../ignored.jpg')
    assert result.status_code == 200, result.text
    profile = result.json()
    url = profile['avatar_url']
    assert profile['id'] == person['id']
    assert m.AVATAR_NAME.fullmatch(url.rsplit('/', 1)[1])
    public = client.get(url)
    assert public.status_code == 200
    assert public.headers['content-type'] == 'image/webp'
    assert public.headers['cache-control'] == 'public, max-age=31536000, immutable'
    assert public.headers['x-content-type-options'] == 'nosniff'
    image = Image.open(BytesIO(public.content))
    assert image.size == (512, 512)
    assert image.format == 'WEBP'
    assert not image.getexif() and not any(k in image.info for k in ('exif','icc_profile','xmp'))
    assert client.get('/api/v1/profile', headers=headers).json()['avatar_url'] == url
    people = client.get('/api/v1/bootstrap', headers=headers).json()['people']
    assert next(p for p in people if p['id'] == person['id'])['avatar_url'] == url
    assert len(list(m.AVATAR_DIR.iterdir())) == 1


def test_auth_and_file_validation(client):
    assert upload(client, {}).status_code == 401
    assert not m.AVATAR_DIR.exists()
    _, headers, _ = account(client)
    for mime, raw in [('image/svg+xml', b'<svg/>'), ('text/plain', picture()),
                      ('image/png', b'not an image'), ('image/jpeg', picture())]:
        assert upload(client, headers, raw, mime).status_code == 415
    assert client.post('/api/v1/profile/avatar', headers=headers, files={'other': ('a.png', picture(), 'image/png')}).status_code == 422
    assert client.post('/api/v1/profile/avatar', headers=headers, files=[('file', ('a.png', picture(), 'image/png')), ('file', ('b.png', picture(), 'image/png'))]).status_code == 400
    assert not m.AVATAR_DIR.exists()
    assert client.patch('/api/v1/profile', headers=headers, json={'avatar_url': 'https://example.org/avatar.png'}).status_code == 422


def test_size_limits_include_chunked_uploads(client):
    _, headers, _ = account(client)
    assert upload(client, headers, b'x' * (m.AVATAR_LIMIT + 1)).status_code == 413
    assert upload(client, headers, b'x' * (m.AVATAR_LIMIT + 65537)).status_code == 413
    # An exactly 2 MiB image payload is accepted; extra harmless bytes never survive transcoding.
    valid = picture()
    assert upload(client, headers, valid + b'\0' * (m.AVATAR_LIMIT - len(valid))).status_code == 200
    boundary = 'test-boundary'
    prefix = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="x.png"\r\n'
              'Content-Type: image/png\r\n\r\n').encode()
    suffix = f'\r\n--{boundary}--\r\n'.encode()
    response = client.post('/api/v1/profile/avatar', headers={**headers, 'Content-Type': f'multipart/form-data; boundary={boundary}'},
                           content=iter([prefix, b'x' * (m.AVATAR_LIMIT + 65537), suffix]))
    assert response.status_code == 413
    assert client.patch('/api/v1/profile', headers=headers, content=b'x' * 262145).status_code == 413


def test_organizer_accepts_only_existing_controlled_paths(client, tmp_path):
    admin, headers, person = account(client)
    url = upload(client, headers).json()['avatar_url']
    endpoint = '/admin/api/participants/' + person['id']
    assert client.patch(endpoint, headers=admin, json={'avatar_url': url}).status_code == 200
    for value in ['https://example.org/a.png', '//example.org/a.png', '/media/avatars/../secret',
                  '/media/avatars/' + 'f' * 64 + '.png', url + '?secret', url + '/extra',
                  '/media/avatars/%2e%2e/secret', '/data/uploads/avatar.png']:
        assert client.patch(endpoint, headers=admin, json={'avatar_url': value}).status_code == 422
        assert client.post('/admin/api/participants', headers=admin, json={'name': 'Invalid', 'avatar_url': value}).status_code == 422
    created = client.post('/admin/api/participants', headers=admin, json={'name': 'Another Test Guest', 'avatar_url': url})
    assert created.status_code == 201 and created.json()['profile']['avatar_url'] == url
    cleared = client.patch(endpoint, headers=admin, json={'avatar_url': ''})
    assert cleared.status_code == 200 and cleared.json()['avatar_url'] == ''
    outside = tmp_path / 'secret.png'
    outside.write_bytes(picture())
    link = m.AVATAR_DIR / ('e' * 64 + '.png')
    link.symlink_to(outside)
    assert client.get('/media/avatars/' + link.name).status_code == 404
    assert client.patch(endpoint, headers=admin, json={'avatar_url': '/media/avatars/' + link.name}).status_code == 422
    assert client.get('/media/avatars/' + 'f' * 64 + '.png').status_code == 404
    assert client.get('/media/avatars/%2e%2e%2fsecret.png').status_code == 404


def test_additive_migration_preserves_existing_people(tmp_path, monkeypatch):
    database = tmp_path / 'old.sqlite3'
    with sqlite3.connect(database) as c:
        c.execute("CREATE TABLE people(id TEXT PRIMARY KEY,name TEXT NOT NULL,role TEXT NOT NULL,bio TEXT NOT NULL DEFAULT '',company TEXT NOT NULL DEFAULT '',position TEXT NOT NULL DEFAULT '',code_hash TEXT,code_key TEXT UNIQUE,active INTEGER NOT NULL DEFAULT 1)")
        c.execute("INSERT INTO people(id,name,role,bio) VALUES('old','Existing Test Guest','attendee','Keep bio')")
    monkeypatch.setenv('ADMIN_CODE', secrets.token_urlsafe(32))
    monkeypatch.setattr(m, 'DB', str(database))
    for _ in range(2):
        with TestClient(m.app):
            with m.db() as c:
                row = c.execute("SELECT * FROM people WHERE id='old'").fetchone()
                assert m.profile(row)['avatar_url'] == ''
                assert row['name'] == 'Existing Test Guest' and row['bio'] == 'Keep bio'
                assert row['active'] == 1


def test_legacy_avatar_alias_resolves_new_format(client):
    _, headers, _ = account(client)
    result=upload(client,headers).json();new=result['avatar_url'].rsplit('/',1)[1]
    old='a'*64+'.png'; aliases=m.AVATAR_DIR/'aliases';aliases.mkdir(exist_ok=True)
    (aliases/(old+'.alias')).write_text(new,encoding='ascii')
    response=client.get('/media/avatars/'+old)
    assert response.status_code==200 and response.headers['content-type']=='image/webp'
    assert response.content==client.get(result['avatar_url']).content

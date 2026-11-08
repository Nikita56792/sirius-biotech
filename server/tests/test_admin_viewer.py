import os
import secrets
from fastapi.testclient import TestClient
from test_api import m


def test_viewer_reads_but_cannot_write_or_enter_app(tmp_path, monkeypatch):
    admin_code, viewer_code = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    monkeypatch.setenv('ADMIN_CODE', admin_code)
    monkeypatch.setenv('ADMIN_VIEWER_CODE', viewer_code)
    monkeypatch.setenv('COOKIE_SECURE', 'false')
    monkeypatch.setattr(m, 'DB', str(tmp_path / 'db.sqlite3'))
    with TestClient(m.app) as c:
        login = c.post('/admin/login', json={'code':viewer_code})
        assert login.status_code == 200 and login.json()['profile']['role'] == 'viewer'
        headers = {'X-CSRF-Token':login.json()['csrf_token']}
        for path in ('sessions', 'posts', 'partners', 'participants', 'recovery', 'questions', 'ratings'):
            assert c.get('/admin/api/'+path).status_code == 200
        assert c.post('/admin/api/posts', headers=headers, json={'title':'test'}).status_code == 403
        assert c.post('/admin/api/participants', headers=headers, json={'name':'test'}).status_code == 403
        assert c.patch('/admin/api/participants/admin', headers=headers, json={'name':'test'}).status_code == 403
        assert c.delete('/admin/api/posts/anything', headers=headers).status_code == 403
        assert c.post('/admin/api/participant-avatar', headers=headers).status_code in (403, 422)
        assert c.post('/api/v1/auth/login', json={'code':viewer_code}).status_code == 403
        assert c.post('/admin/logout', headers=headers).status_code == 200
        assert c.post('/admin/login', json={'code':admin_code}).status_code == 200

import sqlite3

import pytest
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

from fastapi.testclient import TestClient

from test_api import admin, client, m, participant


def test_message_idempotency_and_scope(client):
    a = admin(client)
    p1, h1, _ = participant(client, a, 'One')
    p2, h2, _ = participant(client, a, 'Two')
    p3, h3, _ = participant(client, a, 'Three')
    chat = client.post('/api/v1/chats', headers=h1, json={'person_id': p2['id']}).json()
    url = '/api/v1/chats/' + chat['id'] + '/messages'
    key = str(uuid4())
    payload = {'text': 'Retry safely', 'client_message_id': key}
    first = client.post(url, headers=h1, json=payload)
    assert first.status_code == 201
    assert first.json()['client_message_id'] == key
    assert client.post(url, headers=h1, json=payload).json() == first.json()
    assert client.post(url, headers=h1, json={**payload, 'client_message_id': key.upper(), 'text': '  Retry safely  '}).json() == first.json()
    assert client.post(url, headers=h1, json={**payload, 'text': 'Changed'}).status_code == 409
    assert client.post(url, headers=h3, json=payload).status_code == 404
    assert client.post(url, headers=h2, json=payload).json()['id'] != first.json()['id']
    other = client.post('/api/v1/chats', headers=h1, json={'person_id': p3['id']}).json()
    assert client.post('/api/v1/chats/' + other['id'] + '/messages', headers=h1, json=payload).status_code == 201
    assert client.post(url, headers=h1, json={**payload, 'client_message_id': 'invalid'}).status_code == 422
    legacy1 = client.post(url, headers=h1, json={'text': 'Legacy'}).json()
    legacy2 = client.post(url, headers=h1, json={'text': 'Legacy', 'client_message_id': None}).json()
    assert legacy1['id'] != legacy2['id'] and legacy1['client_message_id'] is None
    concurrent_payload = {'text': 'Concurrent', 'client_message_id': str(uuid4())}
    with ThreadPoolExecutor(max_workers=4) as pool:
        responses = list(pool.map(lambda _: client.post(url, headers=h1, json=concurrent_payload), range(4)))
    assert all(r.status_code == 201 for r in responses)
    assert len({r.json()['id'] for r in responses}) == 1
    assert len(client.get(url, headers=h1).json()) == 5


def test_feedback_privacy_moderation_csrf_and_roles(client):
    assert client.get('/admin/api/questions').status_code == 401
    assert client.get('/admin/api/ratings').status_code == 401
    a = admin(client)
    p1, h1, _ = participant(client, a, 'One')
    _, h2, _ = participant(client, a, 'Two')
    _, _, organizer_code = participant(client, a, 'Organizer', 'organizer')
    sid = client.get('/api/v1/bootstrap', headers=h1).json()['sessions'][0]['id']
    url = '/api/v1/sessions/' + sid
    assert client.get(url + '/questions').status_code == 401
    question = client.post(url + '/questions', headers=h1, json={'text': '<script>Question?</script>'}).json()
    assert question['status'] == 'pending' and question['response'] == ''
    assert client.get(url + '/questions', headers=h2).json() == []
    assert client.get(url + '/questions', headers=h1).json() == [question]
    assert client.get(url + '/rating', headers=h2).json()['value'] is None
    for value in (2, 5):
        assert client.put(url + '/rating', headers=h1, json={'value': value}).status_code == 200
    assert client.get(url + '/rating', headers=h1).json()['value'] == 5
    assert client.get(url + '/rating', headers=h2).json()['value'] is None
    qurl = '/admin/api/questions/' + question['id']
    assert client.patch(qurl, json={'status': 'hidden'}).status_code == 403
    assert client.patch(qurl, headers={'X-CSRF-Token': 'bad'}, json={'status': 'hidden'}).status_code == 403
    assert client.patch(qurl, headers=a, json={'status': 'answered'}).status_code == 422
    assert client.patch(qurl, headers=a, json={'status': 'answered', 'response': '  '}).status_code == 422
    assert client.patch(qurl, headers=a, json={'status': 'published'}).status_code == 422
    assert client.patch(qurl, headers=a, json={'status': 'answered', 'response': 'Organizer response'}).status_code == 200
    assert client.get(url + '/questions', headers=h1).json()[0]['response'] == 'Organizer response'
    assert client.get(url + '/questions', headers=h2).json() == []
    assert client.get('/admin/api/questions?status=pending').json() == []
    assert len(client.get('/admin/api/questions?status=answered&session_id=' + sid).json()) == 1
    assert client.get('/admin/api/questions?session_id=unknown').json() == []
    assert client.get('/admin/api/questions?limit=101').status_code == 422
    assert client.get('/admin/api/questions?status=bad').status_code == 422
    ratings = client.get('/admin/api/ratings?session_id=' + sid).json()
    assert len(ratings) == 1 and ratings[0]['value'] == 5 and ratings[0]['person_id'] == p1['id']
    assert not any('code' in key for key in ratings[0])
    rating_url = '/admin/api/ratings/' + sid + '/' + p1['id']
    assert client.delete(rating_url).status_code == 403
    draft = client.post('/admin/api/sessions', headers=a, json={'title': 'Private draft'}).json()
    draft_url = '/api/v1/sessions/' + draft['id']
    assert client.get(draft_url + '/questions', headers=h1).status_code == 404
    assert client.get(draft_url + '/rating', headers=h1).status_code == 404
    assert client.post(draft_url + '/questions', headers=h1, json={'text': 'Private?'}).status_code == 404
    assert client.put(draft_url + '/rating', headers=h1, json={'value': 4}).status_code == 404
    organizer = client.post('/admin/login', json={'code': organizer_code}).json()
    oh = {'X-CSRF-Token': organizer['csrf_token']}
    assert client.get('/admin/api/questions').status_code == 200
    assert client.get('/admin/api/ratings').status_code == 200
    assert client.delete(rating_url, headers=oh).status_code == 200
    assert client.get(url + '/rating', headers=h1).json()['value'] is None
    assert client.delete(rating_url, headers=oh).status_code == 404
    assert client.patch(qurl, headers=oh, json={'status': 'hidden'}).json()['status'] == 'hidden'
    assert client.get(url + '/questions', headers=h1).json()[0]['status'] == 'hidden'
    assert client.patch('/admin/api/questions/missing', headers=oh, json={'status': 'pending'}).status_code == 404
    # Even a valid web token for an attendee cannot read or moderate the inbox.
    token, csrf = m.issue(p1['id'], 'web')
    client.cookies.set(m.COOKIE, token, path='/admin')
    assert client.get('/admin/api/questions').status_code == 403
    assert client.get('/admin/api/ratings').status_code == 403
    assert client.delete(rating_url, headers={'X-CSRF-Token': csrf}).status_code == 403
    assert client.patch(qurl, headers={'X-CSRF-Token': csrf}, json={'status': 'pending'}).status_code == 403
    client.cookies.clear()
    assert client.get('/admin/api/questions', headers=h1).status_code == 401
    assert client.get('/admin/api/ratings', headers=h1).status_code == 401
    assert client.get('/api/v1/sessions/missing/questions', headers=h1).status_code == 404
    assert client.get('/api/v1/sessions/missing/rating', headers=h1).status_code == 404


def test_legacy_database_migration_is_additive_and_repeatable(tmp_path, monkeypatch):
    import secrets
    monkeypatch.setenv('ADMIN_CODE', secrets.token_urlsafe(32))
    database = tmp_path / 'legacy.sqlite3'
    monkeypatch.setattr(m, 'DB', str(database))
    with sqlite3.connect(database) as c:
        c.executescript("""
            CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,chat_id TEXT,sender_id TEXT,text TEXT,created_at TEXT);
            CREATE TABLE questions(id TEXT PRIMARY KEY,person_id TEXT,session_id TEXT,text TEXT);
            CREATE TABLE ratings(person_id TEXT,session_id TEXT,value INTEGER,PRIMARY KEY(person_id,session_id));
            INSERT INTO messages VALUES(7,'chat','person','Old message','old-time');
            INSERT INTO questions VALUES('question','person','session','Old question');
            INSERT INTO ratings VALUES('person','session',4);
        """)
    for _ in range(2):
        with TestClient(m.app):
            with m.db() as c:
                message = dict(c.execute('SELECT * FROM messages').fetchone())
                assert message['id'] == 7 and message['text'] == 'Old message' and message['client_message_id'] is None
                question = dict(c.execute('SELECT * FROM questions').fetchone())
                assert question['text'] == 'Old question' and question['status'] == 'pending'
                assert question['response'] == question['created_at'] == question['updated_at'] == ''
                assert dict(c.execute('SELECT * FROM ratings').fetchone())['value'] == 4
                c.execute("INSERT INTO messages(chat_id,sender_id,text,client_message_id) VALUES('chat','person','New','key')")
                with pytest.raises(sqlite3.IntegrityError):
                    c.execute("INSERT INTO messages(chat_id,sender_id,text,client_message_id) VALUES('chat','person','Duplicate','key')")
                c.execute("DELETE FROM messages WHERE client_message_id='key'")

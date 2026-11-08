"""Own-server recovery: no external delivery is performed by these tests."""
from concurrent.futures import ThreadPoolExecutor
import sqlite3
import time

import pytest
from fastapi.testclient import TestClient

from test_api import m, client, admin

ORIGINAL_DELIVERY = m.deliver_pin


@pytest.fixture
def setup(client, monkeypatch):
    headers = admin(client)
    created = client.post('/admin/api/participants', headers=headers, json={
        'name': 'Display Name', 'last_name': '  СмИрНоВ  ',
        'recovery_email': 'private.person@example.org',
    }).json()
    monkeypatch.setenv('SBS_SMTP_HOST', 'smtp.invalid')
    monkeypatch.setenv('SBS_SMTP_FROM', 'event@example.org')
    for key in ('SBS_SMTP_USER', 'SBS_SMTP_PASSWORD', 'SBS_SMTP_SECURITY', 'SBS_SMTP_PORT'):
        monkeypatch.delenv(key, raising=False)
    sent = []
    monkeypatch.setattr(m, 'deliver_pin', lambda recipient, code: sent.append((recipient, code)))
    return headers, created, sent


def search(c, surname='смирнов'):
    response = c.post('/api/v1/auth/search', json={'last_name': surname})
    assert response.status_code == 200, response.text
    return response.json()['items']


def send(c, selection):
    return c.post('/api/v1/auth/send-pin', json={'selection_id': selection})


def check(c, challenge, code):
    return c.post('/api/v1/auth/verify-pin', json={'challenge_id': challenge, 'code': code})


def start(c):
    selected = search(c)[0]['selection_id']
    response = send(c, selected)
    assert response.status_code == 200, response.text
    return selected, response.json()['challenge_id']


def test_list_always_explicit_normalization_and_opaque(client, setup):
    headers, person, sent = setup
    item = search(client, '  СМИРНОВ  ')[0]
    assert set(item) == {'selection_id', 'name', 'contact'}
    assert item['name'] == 'Display Name'
    assert item['contact'] == '***@example.org'
    assert person['profile']['id'] not in str(item)
    assert sent == []
    assert search(client, 'Name') == []
    assert search(client, 'смир') == []
    client.post('/admin/api/participants', headers=headers, json={'name': 'No Explicit Смирнов'})
    assert len(search(client)) == 1
    client.post('/admin/api/participants', headers=headers, json={'name': 'Another', 'last_name': 'СМИРНОВ'})
    assert len(search(client)) == 2
    assert search(client)[1]['selection_id'] != item['selection_id']


def test_success_hash_only_and_permanent_login_preserved(client, setup):
    headers, person, sent = setup
    old_login = client.post('/api/v1/auth/login', json={'code': person['code']}).json()
    selection, challenge = start(client)
    assert sent[0][0] == 'private.person@example.org'
    with m.db() as c:
        row = c.execute('SELECT * FROM auth_challenges').fetchone()
        assert row['hash'] == m.digest(challenge)
        assert row['pin_hash'] != sent[0][1]
        assert challenge not in str(tuple(row))
        assert selection not in str(tuple(c.execute('SELECT * FROM auth_selections').fetchone()))
        assert sent[0][1] not in str(tuple(row))
    assert client.get('/api/v1/profile', headers={'Authorization': 'Bearer ' + old_login['access_token']}).status_code == 200
    assert client.post('/api/v1/auth/login', json={'code': person['code']}).status_code == 200
    result = check(client, challenge, sent[0][1])
    assert result.status_code == 200
    assert result.json()['profile'] == person['profile']
    auth = {'Authorization': 'Bearer ' + result.json()['access_token']}
    assert client.get('/api/v1/profile', headers=auth).status_code == 200
    for endpoint in ('/api/v1/profile', '/api/v1/bootstrap'):
        text = client.get(endpoint, headers=auth).text
        assert 'recovery_email' not in text and 'last_name' not in text
        assert 'private.person' not in text
    assert check(client, challenge, sent[0][1]).status_code == 401
    assert send(client, selection).status_code == 404
    organizer = client.get('/admin/api/participants').json()
    row = next(p for p in organizer if p['id'] == person['profile']['id'])
    assert row['last_name'] == 'СмИрНоВ'
    assert row['recovery_email'] == 'private.person@example.org'


def test_wrong_code_five_attempts_and_expiry(client, setup):
    _, _, sent = setup
    selection, challenge = start(client)
    wrong = '000000' if sent[0][1] != '000000' else '111111'
    for _ in range(5):
        assert check(client, challenge, wrong).status_code == 401
    assert check(client, challenge, sent[0][1]).status_code == 401
    with m.db() as c:
        assert c.execute('SELECT attempts FROM auth_challenges').fetchone()[0] == 5
    _, challenge2 = start(client)
    with m.db() as c:
        c.execute('UPDATE auth_challenges SET expires=?', (time.time() - 1,))
    assert check(client, challenge2, sent[-1][1]).status_code == 401
    selection = search(client)[0]['selection_id']
    with m.db() as c:
        c.execute('UPDATE auth_selections SET expires=?', (time.time() - 1,))
    assert send(client, selection).status_code == 404
    assert send(client, 'x' * 43).status_code == 404
    assert check(client, 'x' * 43, '123456').status_code == 401


def test_cross_ip_and_forwarded_headers(client, setup):
    _, _, sent = setup
    selection = search(client)[0]['selection_id']
    other = TestClient(m.app, client=('other-peer', 4321))
    assert send(other, selection).status_code == 404
    challenge = send(client, selection).json()['challenge_id']
    assert check(other, challenge, sent[0][1]).status_code == 401
    result = client.post('/api/v1/auth/verify-pin', json={'challenge_id': challenge, 'code': sent[0][1]}, headers={'X-Forwarded-For': 'other-peer'})
    assert result.status_code == 200


@pytest.mark.parametrize('change', [{'active': False}, {'recovery_email': 'new@example.org'}, {'recovery_email': ''}, {'last_name': ''}])
def test_admin_lifecycle_invalidates_handles(client, setup, change):
    headers, person, sent = setup
    selection, challenge = start(client)
    unconsumed = search(client)[0]['selection_id']
    assert client.patch('/admin/api/participants/' + person['profile']['id'], headers=headers, json=change).status_code == 200
    assert send(client, unconsumed).status_code == 404
    assert check(client, challenge, sent[0][1]).status_code == 401
    if change.get('active') is False or 'last_name' in change:
        assert search(client) == []


def test_deleted_account_cannot_recover(client, setup):
    headers, person, sent = setup
    _, challenge = start(client)
    assert client.delete('/admin/api/participants/' + person['profile']['id'], headers=headers).status_code == 200
    assert check(client, challenge, sent[0][1]).status_code == 401
    assert search(client) == []


def test_unconfigured_and_missing_contact_fail_closed(client, setup, monkeypatch):
    headers, person, sent = setup
    selection = search(client)[0]['selection_id']
    monkeypatch.delenv('SBS_SMTP_HOST')
    assert send(client, selection).status_code == 503
    assert sent == []
    with m.db() as c:
        assert c.execute('SELECT COUNT(*) FROM auth_challenges').fetchone()[0] == 0
    client.patch('/admin/api/participants/' + person['profile']['id'], headers=headers, json={'recovery_email': ''})
    item = search(client)[0]
    assert item['contact'] is None
    assert send(client, item['selection_id']).status_code == 503


def test_provider_failure_no_success_or_challenge(client, setup, monkeypatch):
    def failed(recipient, code):
        raise RuntimeError('provider credentials/private details must not leak')
    monkeypatch.setattr(m, 'deliver_pin', failed)
    response = send(client, search(client)[0]['selection_id'])
    assert response.status_code == 503
    assert 'provider credentials' not in response.text
    with m.db() as c:
        assert c.execute('SELECT COUNT(*) FROM auth_challenges').fetchone()[0] == 0


def test_concurrent_one_use_verification(client, setup):
    _, _, sent = setup
    _, challenge = start(client)
    with ThreadPoolExecutor(max_workers=4) as pool:
        statuses = list(pool.map(lambda _: check(client, challenge, sent[0][1]).status_code, range(4)))
    assert sorted(statuses) == [200, 401, 401, 401]


def test_concurrent_selection_only_sends_once(client, setup):
    _, _, sent = setup
    selection = search(client)[0]['selection_id']
    with ThreadPoolExecutor(max_workers=2) as pool:
        statuses = list(pool.map(lambda _: send(client, selection).status_code, range(2)))
    assert sorted(statuses) == [200, 404]
    assert len(sent) == 1


def test_person_recipient_and_ip_limits(client, setup):
    for _ in range(3):
        start(client)
    assert send(client, search(client)[0]['selection_id']).status_code == 429
    with m.db() as c:
        c.execute("DELETE FROM rate_limits WHERE key LIKE 'pin-person:%'")
    assert send(client, search(client)[0]['selection_id']).status_code == 429
    with m.db() as c:
        c.execute("DELETE FROM rate_limits WHERE key LIKE 'pin-recipient:%'")
        c.execute("INSERT OR REPLACE INTO rate_limits VALUES(?,?,?)", ('send-pin:testclient', time.time(), 10))
    response = send(client, search(client)[0]['selection_id'])
    assert response.status_code == 429
    assert int(response.headers['retry-after']) > 0


@pytest.mark.parametrize('email', ['x\r\nBcc: victim@example.org', 'Name <x@example.org>', 'x@example.org,evil@example.org', 'x@localhost', 'x..y@example.org'])
def test_admin_rejects_header_injection_and_ambiguous_addresses(client, setup, email):
    headers, person, _ = setup
    assert client.patch('/admin/api/participants/' + person['profile']['id'], headers=headers, json={'recovery_email': email}).status_code == 422
    assert client.post('/admin/api/participants', headers=headers, json={'name': 'No', 'recovery_email': email}).status_code == 422


def test_smtp_tls_and_acceptance(client, setup, monkeypatch):
    events = []
    class SMTP:
        def __init__(self, host, port, **kwargs):
            events.append(('connect', host, port, kwargs))
        def __enter__(self):
            return self
        def __exit__(self, *args):
            pass
        def ehlo(self):
            events.append(('ehlo',))
        def starttls(self, context):
            import ssl
            assert context.verify_mode == ssl.CERT_REQUIRED and context.check_hostname
            events.append(('tls',))
        def login(self, user, password):
            events.append(('login',))
        def send_message(self, message):
            assert message['Subject'] == 'SBS sign-in code'
            assert message['To'] == 'private.person@example.org'
            assert '123456' in message.get_content()
            events.append(('send',))
            return {}
    monkeypatch.setattr(m.smtplib, 'SMTP', SMTP)
    monkeypatch.setattr(m.smtplib, 'SMTP_SSL', SMTP)
    monkeypatch.setenv('SBS_SMTP_USER', 'organizer')
    monkeypatch.setenv('SBS_SMTP_PASSWORD', 'not-a-real-password')
    ORIGINAL_DELIVERY('private.person@example.org', '123456')
    assert [e[0] for e in events] == ['connect', 'ehlo', 'tls', 'ehlo', 'login', 'send']
    assert events[0][3]['timeout'] == 10
    events.clear()
    monkeypatch.setenv('SBS_SMTP_SECURITY', 'ssl')
    ORIGINAL_DELIVERY('private.person@example.org', '123456')
    assert [e[0] for e in events] == ['connect', 'login', 'send']
    assert events[0][3]['context'].check_hostname


@pytest.mark.parametrize('key,value', [
    ('SBS_SMTP_SECURITY', 'plain'), ('SBS_SMTP_PORT', 'not-a-port'),
    ('SBS_SMTP_FROM', 'injected\r\nBcc: evil@example.org'),
    ('SBS_SMTP_USER', 'user-without-password'),
])
def test_invalid_smtp_configuration_fails_closed(client, setup, monkeypatch, key, value):
    _, _, sent = setup
    monkeypatch.setenv(key, value)
    assert send(client, search(client)[0]['selection_id']).status_code == 503
    assert sent == []


def test_contact_changed_during_delivery_never_reports_success(client, setup, monkeypatch):
    headers, person, sent = setup
    def changed(recipient, code):
        result = client.patch('/admin/api/participants/' + person['profile']['id'], headers=headers,
                              json={'recovery_email': 'updated@example.org'})
        assert result.status_code == 200
    monkeypatch.setattr(m, 'deliver_pin', changed)
    assert send(client, search(client)[0]['selection_id']).status_code == 503
    with m.db() as c:
        assert c.execute('SELECT COUNT(*) FROM auth_challenges').fetchone()[0] == 0


def test_additive_migration_retains_existing_database(tmp_path, monkeypatch):
    path = tmp_path / 'old.sqlite3'
    with sqlite3.connect(path) as c:
        c.execute("CREATE TABLE people(id TEXT PRIMARY KEY,name TEXT NOT NULL,role TEXT NOT NULL,bio TEXT NOT NULL DEFAULT '',company TEXT NOT NULL DEFAULT '',position TEXT NOT NULL DEFAULT '',code_hash TEXT,code_key TEXT UNIQUE,active INTEGER NOT NULL DEFAULT 1)")
        c.execute("INSERT INTO people(id,name,role) VALUES('existing','Existing','attendee')")
    monkeypatch.setenv('ADMIN_CODE', 'migration-only-random-secret-value')
    monkeypatch.setattr(m, 'DB', str(path))
    with TestClient(m.app):
        with m.db() as c:
            row = c.execute("SELECT * FROM people WHERE id='existing'").fetchone()
            assert row['name'] == 'Existing'
            assert row['last_name'] == row['recovery_email'] == ''
            assert c.execute("SELECT COUNT(*) FROM auth_challenges").fetchone()[0] == 0

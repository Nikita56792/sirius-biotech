import importlib.util
import os
import secrets
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

spec = importlib.util.spec_from_file_location('sbs_main', Path(__file__).parents[1] / 'main.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setenv('ADMIN_CODE', secrets.token_urlsafe(32))
    monkeypatch.setenv('COOKIE_SECURE', 'false')
    monkeypatch.setattr(m, 'DB', str(tmp_path / 'test.sqlite3'))
    with TestClient(m.app) as c:
        yield c


def admin(c):
    response = c.post('/admin/login', json={'code':os.environ['ADMIN_CODE']})
    assert response.status_code == 200
    return {'X-CSRF-Token':response.json()['csrf_token']}


def participant(c, headers, name='Participant', role='attendee'):
    r = c.post('/admin/api/participants', headers=headers,json={'name':name,'role':role})
    assert r.status_code == 201, r.text
    p = r.json()
    assert len(p['code']) == 6 and p['code'].isdigit()
    login = c.post('/api/v1/auth/login',json={'code':p['code']})
    assert login.status_code == 200
    return p['profile'], {'Authorization':'Bearer '+login.json()['access_token']}, p['code']


def test_startup_requires_secret(monkeypatch,tmp_path):
    monkeypatch.delenv('ADMIN_CODE',raising=False)
    monkeypatch.setattr(m,'DB',str(tmp_path/'absent.sqlite3'))
    with pytest.raises(RuntimeError,match='ADMIN_CODE'):
        with TestClient(m.app):
            pass
    assert not (tmp_path/'absent.sqlite3').exists()


def test_auth_seed_and_hashes(client):
    assert client.get('/api/v1/bootstrap').status_code == 401
    assert client.post('/api/v1/auth/login',json={'code':'unknown'}).status_code == 401
    a = admin(client)
    p,h,code = participant(client,a)
    data = client.get('/api/v1/bootstrap',headers=h).json()
    assert set(data)=={'sessions','people','posts','partners'}
    assert len(data['sessions'])==62 and all(s['year']==2027 and s['source_year']==2026 and s['label']=='ADAPTED2027' for s in data['sessions'])
    assert len(data['posts'])==3 and len(data['partners'])==3
    assert all(x['label']=='ARCHIVE2026' and x['source_url'] for x in data['posts']+data['partners'])
    archived=[x for x in data['people'] if x.get('archive')]
    assert len(archived)==12 and all(x['year']==2026 for x in archived)
    with m.db() as conn:
        assert all(conn.execute('SELECT 1 FROM people WHERE id=?',(x['id'],)).fetchone() is None for x in archived)
    assert all('code_hash' not in person for person in data['people'])
    with m.db() as conn:
        row=conn.execute('SELECT * FROM people WHERE id=?',(p['id'],)).fetchone()
        assert row['code_hash'] != code and ':' in row['code_hash']
        assert row['code_key'] != m.digest(code)
        assert conn.execute("SELECT hash FROM tokens WHERE kind='api'").fetchone()['hash'] != h['Authorization'][7:]
        conn.execute('UPDATE tokens SET expires=? WHERE person_id=?',(time.time()-1,p['id']))
    assert client.get('/api/v1/profile',headers=h).status_code == 401


def test_csrf_and_roles(client):
    assert client.get('/admin/api/participants').status_code == 401
    a=admin(client)
    assert 'HttpOnly' in client.post('/admin/login',json={'code':os.environ['ADMIN_CODE']}).headers['set-cookie']
    a=admin(client)
    assert client.post('/admin/api/participants',json={'name':'No CSRF'}).status_code == 403
    assert client.post('/admin/login',headers={'Origin':'https://evil.invalid'},json={'code':os.environ['ADMIN_CODE']}).status_code == 403
    p,h,code=participant(client,a)
    assert client.post('/admin/login',json={'code':code}).status_code == 403
    assert client.patch('/api/v1/profile',headers=h,json={'role':'admin'}).status_code == 422
    org,_,org_code=participant(client,a,'Organizer','organizer')
    o=client.post('/admin/login',json={'code':org_code}).json()
    oh={'X-CSRF-Token':o['csrf_token']}
    assert client.post('/admin/api/participants',headers=oh,json={'name':'Escalate','role':'organizer'}).status_code == 403
    assert client.post('/admin/api/participants/admin/code',headers=oh,json={}).status_code == 403


def test_chat_ownership_and_pagination(client):
    a=admin(client)
    p1,h1,_=participant(client,a,'One')
    p2,h2,_=participant(client,a,'Two')
    p3,h3,_=participant(client,a,'Three')
    chat=client.post('/api/v1/chats',headers=h1,json={'person_id':p2['id']}).json()
    url='/api/v1/chats/'+chat['id']+'/messages'
    assert client.post('/api/v1/chats',headers=h2,json={'person_id':p1['id']}).json()['id']==chat['id']
    assert client.get(url,headers=h3).status_code==404
    assert client.post(url,headers=h3,json={'text':'Forbidden'}).status_code==404
    assert client.get('/api/v1/chats',headers=h3).json()==[]
    assert client.post(url,headers=h1,json={'text':'   '}).status_code==422
    assert client.post(url,headers=h1,json={'text':'First'}).status_code==201
    assert client.post(url,headers=h2,json={'text':'Second'}).status_code==201
    assert client.get(url+'?limit=1&offset=1',headers=h1).json()[0]['text']=='Second'
    assert client.get(url+'?limit=101',headers=h1).status_code==422


def test_meetings_favorites_questions_rating(client):
    a=admin(client)
    p1,h1,_=participant(client,a,'One')
    p2,h2,_=participant(client,a,'Two')
    _,h3,_=participant(client,a,'Three')
    meet=client.post('/api/v1/meetings',headers=h1,json={'person_id':p2['id'],'starts_at':'2027-05-20T12:00:00+03:00','title':'Discussion','location':'Foyer'})
    assert meet.status_code==201
    url='/api/v1/meetings/'+meet.json()['id']
    assert client.patch(url,headers=h3,json={'status':'accepted'}).status_code==404
    assert client.patch(url,headers=h1,json={'status':'accepted'}).status_code==403
    assert client.patch(url,headers=h2,json={'status':'accepted'}).status_code==200
    assert client.get('/api/v1/meetings',headers=h3).json()==[]
    sid=client.get('/api/v1/bootstrap',headers=h1).json()['sessions'][0]['id']
    fav={'items':[{'kind':'sessions','item_id':sid}]}
    assert client.put('/api/v1/favorites',headers=h1,json=fav).json()==fav
    assert client.get('/api/v1/favorites',headers=h2).json()=={'items':[]}
    assert client.put('/api/v1/favorites',headers=h1,json={'items':[{'kind':'sessions','item_id':'missing'}]}).status_code==422
    assert client.post('/api/v1/sessions/'+sid+'/questions',headers=h1,json={'text':'Question?'}).status_code==201
    assert client.put('/api/v1/sessions/'+sid+'/rating',headers=h1,json={'value':6}).status_code==422
    assert client.put('/api/v1/sessions/'+sid+'/rating',headers=h1,json={'value':4}).status_code==200
    assert client.put('/api/v1/sessions/missing/rating',headers=h1,json={'value':4}).status_code==404


def test_rate_limit_bounded_and_persistent(client):
    for _ in range(10):
        assert client.post('/api/v1/auth/login',json={'code':'invalid'}).status_code==401
    assert client.post('/api/v1/auth/login',headers={'X-Forwarded-For':'different'},json={'code':'invalid'}).status_code==429
    with m.db() as c:
        c.executemany('INSERT OR REPLACE INTO rate_limits VALUES(?,?,1)',[(f'key{i}',time.time()) for i in range(4200)])
    assert client.post('/admin/login',json={'code':'invalid'}).status_code==401
    with m.db() as c:
        assert c.execute('SELECT COUNT(*) FROM rate_limits').fetchone()[0]<=4096


def test_admin_crud_and_revoke(client):
    a=admin(client)
    p,h,code=participant(client,a)
    item=client.post('/admin/api/posts',headers=a,json={'title':'Draft news'}).json()
    assert client.put('/admin/api/posts/'+item['id'],headers=a,json={'title':'Updated'}).status_code==200
    assert client.delete('/admin/api/posts/'+item['id'],headers=a).status_code==200
    assert client.post('/admin/api/participants/'+p['id']+'/code',headers=a,json={}).status_code==200
    assert client.get('/api/v1/profile',headers=h).status_code==401
    assert client.post('/api/v1/auth/login',json={'code':code}).status_code==401
    assert client.patch('/admin/api/participants/'+p['id'],headers=a,json={'active':False}).status_code==200
    assert client.get('/health').json()=={'status':'ok'}
    assert client.get('/openapi.json').status_code==200
    assert client.get('/admin').status_code==200


def test_recovery_accepts_original_nonempty_surname_without_disclosure(client):
    assert client.post('/api/v1/auth/recovery', json={'surname':' ', 'contact':'test@example.invalid'}).status_code == 422
    response = client.post('/api/v1/auth/recovery', json={'surname':' X ', 'contact':' test@example.invalid '})
    assert response.status_code == 202
    assert response.json() == {'status':'pending', 'message':'Organizer review requested'}
    assert client.get('/admin/api/recovery').status_code == 401
    rows = client.get('/admin/api/recovery', headers=admin(client)).json()
    assert len(rows) == 1
    assert rows[0]['surname'] == 'X'
    assert rows[0]['contact'] == 'test@example.invalid'


def test_delete_removes_from_admin_list_and_retains_conversation(client):
    headers=admin(client)
    first,first_auth,first_code=participant(client,headers,name='Delete test')
    second,second_auth,_=participant(client,headers,name='Closed access test')
    chat=client.post('/api/v1/chats',headers=first_auth,json={'person_id':second['id']}).json()
    message=client.post('/api/v1/chats/'+chat['id']+'/messages',headers=first_auth,json={'text':'Preserved history'})
    assert message.status_code==201
    assert client.patch('/admin/api/participants/'+second['id'],headers=headers,json={'active':False}).status_code==200
    assert second['id'] in {p['id'] for p in client.get('/admin/api/participants',headers=headers).json()}
    assert client.delete('/admin/api/participants/'+first['id'],headers=headers).status_code==200
    assert first['id'] not in {p['id'] for p in client.get('/admin/api/participants',headers=headers).json()}
    assert client.patch('/admin/api/participants/'+first['id'],headers=headers,json={'active':True}).status_code==404
    assert client.post('/admin/api/participants/'+first['id']+'/code',headers=headers,json={}).status_code==404
    assert client.post('/api/v1/auth/login',json={'code':first_code}).status_code==401
    with m.db() as c:
        assert c.execute('SELECT deleted FROM people WHERE id=?',(first['id'],)).fetchone()[0]==1
        assert c.execute('SELECT text FROM messages WHERE chat_id=?',(chat['id'],)).fetchone()[0]=='Preserved history'
    assert client.delete('/admin/api/participants/admin',headers=headers).status_code==403

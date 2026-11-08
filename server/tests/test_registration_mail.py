import json
import os
import smtplib
import time
from io import BytesIO
from email import policy
from email.parser import BytesParser
from PIL import Image
from test_api import m, client, admin


def create(c, headers, **values):
    data={'name':'Смирнов Иван Сергеевич','last_name':'Смирнов','first_name':'Иван','patronymic':'Сергеевич','recovery_email':'ivan@example.org',**values}
    response=c.post('/admin/api/participants',headers=headers,json=data)
    assert response.status_code==201,response.text
    return response.json()


def test_register_view_send_without_rotating(client,monkeypatch):
    headers=admin(client)
    result=create(client,headers,send_email=True)
    pid=result['profile']['id']
    assert result['mail_id']
    assert client.post(f'/admin/api/participants/{pid}/code/view',headers=headers,json={}).json()['code']==result['code']
    assert client.post(f'/admin/api/participants/{pid}/invitation',headers=headers,json={}).json()['id']==result['mail_id']
    sent=[]
    monkeypatch.setattr(m.mail,'delivery_ready',lambda:True)
    monkeypatch.setattr(m.mail,'deliver',lambda message:sent.append(message))
    monkeypatch.setenv('SBS_SMTP_FROM','noreply@example.org')
    assert m.mail.process_one(m.db)
    assert len(sent)==1
    msg=sent[0]
    assert msg['Subject']=='Вход в приложение Sirius Biotech Summit'
    assert m.mail.SENDER_NAME in str(msg['From'])
    html=msg.get_body(preferencelist=('html',)).get_content()
    assert 'Иван Сергеевич, добрый день!' in html
    assert result['code'] in html and 'https://nikita56792.github.io/SBS/' in html
    assert 'Moscow' not in html and 'sendsay' not in html and 'mailtracking' not in html
    assert 'cid:sbs-banner' in html
    parsed=BytesParser(policy=policy.default).parsebytes(msg.as_bytes())
    image=next(part for part in parsed.walk() if part.get_content_type()=='image/png')
    assert image['Content-ID']=='<sbs-banner>'
    with m.db() as c:
        row=c.execute('SELECT * FROM mail_outbox').fetchone()
        assert row['status']=='sent' and row['payload']==''
        person=c.execute('SELECT * FROM people WHERE id=?',(pid,)).fetchone()
        assert person['access_code_cipher']!=result['code']
    assert client.post('/api/v1/auth/login',json={'code':result['code']}).status_code==200


def test_unsubscribe_preserves_access(client,monkeypatch):
    headers=admin(client);result=create(client,headers,send_email=True);pid=result['profile']['id']
    with m.db() as c:token=c.execute('SELECT token FROM mail_subscriptions').fetchone()[0]
    url='/mail/unsubscribe/'+token
    assert client.get(url).status_code==200
    with m.db() as c:assert not c.execute('SELECT unsubscribed FROM mail_subscriptions').fetchone()[0]
    assert client.post(url,data={'List-Unsubscribe':'One-Click'}).status_code==200
    assert client.post(f'/admin/api/participants/{pid}/invitation',headers=headers,json={}).status_code==422
    assert client.post('/api/v1/auth/login',json={'code':result['code']}).status_code==200
    with m.db() as c:assert c.execute('SELECT status FROM mail_outbox').fetchone()[0]=='cancelled'


def test_changed_code_or_email_cancels_queued(client,monkeypatch):
    headers=admin(client);result=create(client,headers,send_email=True);pid=result['profile']['id']
    updated=client.post(f'/admin/api/participants/{pid}/code',headers=headers,json={})
    assert updated.status_code==200
    with m.db() as c:assert c.execute('SELECT status FROM mail_outbox').fetchone()[0]=='cancelled'
    assert client.post(f'/admin/api/participants/{pid}/invitation',headers=headers,json={}).status_code==202
    client.patch(f'/admin/api/participants/{pid}',headers=headers,json={'recovery_email':'changed@example.org'})
    monkeypatch.setattr(m.mail,'delivery_ready',lambda:True)
    monkeypatch.setattr(m.mail,'deliver',lambda message:(_ for _ in ()).throw(AssertionError('Must not send stale recipient')))
    assert m.mail.process_one(m.db)
    with m.db() as c:assert all(r[0]=='cancelled' for r in c.execute('SELECT status FROM mail_outbox'))


def test_registration_with_email_requires_address_and_is_atomic(client):
    headers=admin(client)
    response=client.post('/admin/api/participants',headers=headers,json={'name':'No address','send_email':True})
    assert response.status_code==422
    with m.db() as c:assert c.execute("SELECT COUNT(*) FROM people WHERE name='No address'").fetchone()[0]==0


def test_uncertain_send_not_automatically_duplicated(client,monkeypatch):
    headers=admin(client);result=create(client,headers,send_email=True)
    monkeypatch.setattr(m.mail,'delivery_ready',lambda:True)
    monkeypatch.setenv('SBS_SMTP_FROM','noreply@example.org')
    calls=[]
    def fail(message):calls.append(message);raise smtplib.SMTPServerDisconnected('test')
    monkeypatch.setattr(m.mail,'deliver',fail)
    assert m.mail.process_one(m.db)
    assert not m.mail.process_one(m.db)
    assert len(calls)==1
    with m.db() as c:assert c.execute('SELECT status FROM mail_outbox').fetchone()[0]=='unknown'


def test_photo_upload_and_role_guards(client,tmp_path,monkeypatch):
    headers=admin(client);monkeypatch.setattr(m,'AVATAR_DIR',tmp_path/'avatars')
    image=BytesIO();Image.new('RGB',(60,80),'red').save(image,format='PNG')
    data=image.getvalue()
    result=client.post('/admin/api/participant-avatar',headers=headers,files={'file':('photo.png',data,'image/png')})
    assert result.status_code==200,result.text
    person=create(client,headers,avatar_url=result.json()['avatar_url'])
    token=client.post('/api/v1/auth/login',json={'code':person['code']}).json()['access_token']
    client.post('/admin/logout',headers=headers,json={})
    assert client.post('/admin/api/participant-avatar',files={'file':('photo.png',data,'image/png')}).status_code==401
    assert client.post(f"/admin/api/participants/{person['profile']['id']}/code/view",headers={'Authorization':'Bearer '+token},json={}).status_code==401


def test_template_escapes_personal_data():
    message=m.mail.invitation('test@example.org','001234','<b>Иван</b>','test-token','<test@example.org>',sender='noreply@example.org')
    html=message.get_body(preferencelist=('html',)).get_content()
    assert '&lt;b&gt;Иван&lt;/b&gt;' in html
    assert '001234' in html


def test_registration_retry_does_not_duplicate(client):
    import uuid
    headers=admin(client)
    key=str(uuid.uuid4())
    first=create(client,headers,send_email=True,registration_key=key)
    second=create(client,headers,send_email=True,registration_key=key)
    assert first==second
    with m.db() as c:
        assert c.execute('SELECT COUNT(*) FROM mail_outbox').fetchone()[0]==1
        assert c.execute('SELECT COUNT(*) FROM people WHERE registration_key=?',(key,)).fetchone()[0]==1
    response=client.post('/admin/api/participants',headers=headers,json={'registration_key':key,'name':'Other person'})
    assert response.status_code==409


def test_corrupt_job_does_not_block_queue(client,monkeypatch):
    headers=admin(client)
    first=create(client,headers,send_email=True)
    second=create(client,headers,name='Петров Пётр Петрович',recovery_email='petr@example.org',send_email=True)
    with m.db() as c:c.execute('UPDATE mail_outbox SET payload=? WHERE id=?',('corrupt',first['mail_id']))
    monkeypatch.setattr(m.mail,'delivery_ready',lambda:True)
    monkeypatch.setenv('SBS_SMTP_FROM','noreply@example.org')
    sent=[];monkeypatch.setattr(m.mail,'deliver',lambda message:sent.append(message))
    assert m.mail.process_one(m.db)
    assert m.mail.process_one(m.db)
    assert len(sent)==1 and sent[0]['To']=='petr@example.org'

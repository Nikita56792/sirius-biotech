from io import BytesIO
from uuid import uuid4
from PIL import Image
from test_api import admin, client, m, participant


def test_private_photo_attachment_and_retry(client, tmp_path, monkeypatch):
    monkeypatch.setattr(m, 'CHAT_IMAGE_DIR', tmp_path / 'private-images')
    a = admin(client)
    p1,h1,_ = participant(client,a,'Photo sender')
    p2,h2,_ = participant(client,a,'Photo recipient')
    _,h3,_ = participant(client,a,'Other')
    chat = client.post('/api/v1/chats',headers=h1,json={'person_id':p2['id']}).json()
    base = '/api/v1/chats/'+chat['id']
    out = BytesIO(); Image.new('RGB',(64,32),'red').save(out,format='PNG')
    payload = {'file':('picture.png',out.getvalue(),'image/png')}
    assert client.post(base+'/attachments',headers=h3,files=payload).status_code == 404
    assert client.post(base+'/attachments',headers=h1,files={'file':('fake.png',b'not a photo','image/png')}).status_code == 415
    upload = client.post(base+'/attachments',headers=h1,files=payload)
    assert upload.status_code == 201, upload.text
    image_id=upload.json()['attachment_id']; url=base+'/attachments/'+image_id
    assert client.get(url,headers=h1).status_code == 404
    assert client.post(base+'/messages',headers=h2,json={'attachment_id':image_id}).status_code == 404
    message={'text':'','attachment_id':image_id,'client_message_id':str(uuid4())}
    first=client.post(base+'/messages',headers=h1,json=message)
    assert first.status_code == 201, first.text
    assert client.post(base+'/messages',headers=h1,json=message).json() == first.json()
    assert client.post(base+'/messages',headers=h1,json={**message,'text':'different'}).status_code == 409
    assert client.get(url).status_code == 401
    assert client.get(url,headers=h3).status_code == 404
    for h in [h1,h2]:
        response=client.get(url,headers=h)
        assert response.status_code == 200 and response.headers['content-type'] == 'image/jpeg'
        with Image.open(BytesIO(response.content)) as photo:
            assert photo.size == (64,32)
    assert client.get('/api/v1/chats',headers=h2).json()[0]['last_message'] == 'Фото'


def test_archive_speaker_can_be_saved(client):
    a=admin(client)
    _,headers,_=participant(client,a,'Archive reviewer')
    archive=next(person for person in client.get('/api/v1/bootstrap',headers=headers).json()['people'] if person.get('archive'))
    result=client.put('/api/v1/favorites',headers=headers,json={'items':[{'kind':'people','item_id':archive['id']}]})
    assert result.status_code==200 and result.json()['items'][0]['item_id']==archive['id']

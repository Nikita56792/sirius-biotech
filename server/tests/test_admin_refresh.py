from test_api import m, client, admin, participant


def test_admin_snapshot_revision_tracks_data_changes(client):
    headers=admin(client)
    first=client.get('/admin/api/posts',headers=headers)
    revision=int(first.headers['X-SBS-Revision'])
    made=client.post('/admin/api/posts',headers=headers,json={'title':'New item'})
    assert made.status_code==201
    second=client.get('/admin/api/posts',headers=headers)
    assert int(second.headers['X-SBS-Revision'])>revision
    assert made.json()['id'] in {item['id'] for item in second.json()}
    assert client.get('/admin/api/revision',headers=headers).json()['revision']==second.headers['X-SBS-Revision']
    client.cookies.clear()
    assert client.get('/admin/api/revision').status_code==401


def test_deleted_test_feedback_does_not_show_in_admin(client):
    headers=admin(client)
    person,auth,_=participant(client,headers,name='Integration Check temporary')
    session=client.post('/admin/api/sessions',headers=headers,json={'title':'Session','label':'PUBLISHED'}).json()
    url='/api/v1/sessions/'+session['id']
    assert client.put(url+'/rating',headers=auth,json={'value':5}).status_code==200
    assert client.post(url+'/questions',headers=auth,json={'text':'Question'}).status_code==201
    assert len(client.get('/admin/api/ratings',headers=headers).json())==1
    assert client.delete('/admin/api/participants/'+person['id'],headers=headers).status_code==200
    assert client.get('/admin/api/ratings',headers=headers).json()==[]
    assert client.get('/admin/api/questions',headers=headers).json()==[]


def test_speaker_photos_are_same_origin(client):
    headers=admin(client);_,auth,_=participant(client,headers)
    people=client.get('/api/v1/bootstrap',headers=auth).json()['people']
    speaker=next(p for p in people if 'КОСЕНКО' in p['name'])
    assert speaker['avatar_url'].startswith('/static/assets/speakers/')
    photo=client.get(speaker['avatar_url'])
    assert photo.status_code==200
    assert photo.headers['content-type'].startswith('image/')

from test_api import m,client,admin,participant


def test_chat_state_is_private_and_read_marker_is_monotonic(client):
    a=admin(client);p,h,_=participant(client,a,'One');peer,h2,_=participant(client,a,'Two');_,h3,_=participant(client,a,'Three')
    chat=client.post('/api/v1/chats',headers=h,json={'person_id':peer['id']}).json();url='/api/v1/chats/'+chat['id']
    message=client.post(url+'/messages',headers=h2,json={'text':'Hello'}).json()
    assert client.get('/api/v1/chats',headers=h).json()[0]['unread']==1
    changed=client.put(url+'/state',headers=h,json={'hidden':True,'muted':True,'last_read':message['id']})
    assert changed.status_code==200 and changed.json()['hidden'] and changed.json()['muted'] and changed.json()['unread']==0
    assert client.put(url+'/state',headers=h,json={'last_read':0}).json()['unread']==0
    other=client.get('/api/v1/chats',headers=h2).json()[0]
    assert not other['hidden'] and not other['muted']
    assert client.put(url+'/state',headers=h3,json={'hidden':True}).status_code==404
    assert client.put(url+'/state',headers=h,json={'last_read':999999}).status_code==422
    assert client.put(url+'/state',headers=h,json={'last_read':-1}).status_code==422
    client.post(url+'/messages',headers=h2,json={'text':'New message reopens hidden conversation'})
    reopened=client.get('/api/v1/chats',headers=h).json()[0]
    assert not reopened['hidden'] and reopened['muted'] and reopened['unread']==1


def test_meeting_availability_options_and_booking_conflict(client):
    a=admin(client);p,h,_=participant(client,a,'One');peer,h2,_=participant(client,a,'Two');third,h3,_=participant(client,a,'Three')
    availability={'items':[{'start':'2027-05-20T09:00:00+03:00','end':'2027-05-20T12:00:00+03:00'}]}
    assert client.put('/api/v1/meeting-availability',headers=h2,json=availability).status_code==200
    assert client.get('/api/v1/meeting-availability',headers=h2).json()==availability
    assert client.get('/api/v1/meeting-availability',headers=h).json()=={'items':[]}
    data={'person_id':peer['id'],'starts_at':'2027-05-20T10:00:00+03:00','title':'Private title','location':'Private location'}
    made=client.post('/api/v1/meetings',headers=h,json=data);assert made.status_code==201
    options=client.get('/api/v1/meeting-options',params={'person_id':peer['id']},headers=h3).json()
    assert len(options['busy'])==1 and options['available']['peer']==availability['items']
    assert 'Private' not in str(options)
    assert client.post('/api/v1/meetings',headers=h3,json=data).status_code==409
    assert client.post('/api/v1/meetings',headers=h3,json={**data,'starts_at':'2027-05-20T12:00:00+03:00'}).status_code==201
    assert client.patch('/api/v1/meetings/'+made.json()['id'],headers=h,json={'status':'cancelled'}).status_code==200
    assert client.post('/api/v1/meetings',headers=h3,json=data).status_code==201
    assert client.get('/api/v1/meeting-options',params={'person_id':p['id']},headers=h).status_code==422
    assert client.put('/api/v1/meeting-availability',headers=h,json={'items':[{'start':'2027-05-20T12:00:00','end':'2027-05-20T10:00:00'}]}).status_code==422

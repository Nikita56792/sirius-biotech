"""Organizer drafts must never enter the attendee bootstrap payload."""
from test_api import client, admin, participant


def test_draft_visibility_and_publish(client):
    organizer=admin(client)
    _,attendee,_=participant(client,organizer)
    draft=client.post('/admin/api/posts',headers=organizer,json={'title':'Editorial draft','description':'Not ready'})
    assert draft.status_code==201
    item=draft.json()
    payload=client.get('/api/v1/bootstrap',headers=attendee).json()
    assert item['id'] not in {x['id'] for x in payload['posts']}
    assert item['id'] in {x['id'] for x in client.get('/admin/api/posts',headers=organizer).json()}
    updated=client.put('/admin/api/posts/'+item['id'],headers=organizer,json={'title':'Editorial draft','description':'Ready','label':'PUBLISHED'})
    assert updated.status_code==200
    payload=client.get('/api/v1/bootstrap',headers=attendee).json()
    assert item['id'] in {x['id'] for x in payload['posts']}

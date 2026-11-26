"""Institution Google Drive OAuth and encrypted backups.

OAuth is authorized ONCE by the central admin. Staff never handle Google
passwords. Backups are server-side AES-encrypted before upload to Drive.
"""
import base64, hashlib, json, os, secrets, sqlite3, time, urllib.parse, urllib.request
from datetime import datetime
from zoneinfo import ZoneInfo
from cryptography.fernet import Fernet

SCOPE='https://www.googleapis.com/auth/drive.file'
UTC='Asia/Colombo'

def settings():
    return {k:os.getenv(k,'') for k in ('SPA_GOOGLE_CLIENT_ID','SPA_GOOGLE_CLIENT_SECRET','SPA_PUBLIC_URL','SPA_TOKEN_KEY')}

def crypt():
    value=settings()['SPA_TOKEN_KEY']
    if not value:raise ValueError('SPA_TOKEN_KEY not configured; use Fernet.generate_key().')
    return Fernet(value.encode())

def schema(db):
    db.executescript('''
    CREATE TABLE IF NOT EXISTS oauth_pending(state_hash TEXT PRIMARY KEY,admin TEXT NOT NULL,expires_at INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS drive_auth(only_id INTEGER PRIMARY KEY CHECK(only_id=1),refresh_token BLOB NOT NULL, connected_at INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS drive_backups(day TEXT PRIMARY KEY,file_id TEXT NOT NULL,file_name TEXT NOT NULL,created_at INTEGER NOT NULL);
    ''')

def redirect_uri():return settings()['SPA_PUBLIC_URL'].rstrip('/')+'/oauth/drive/callback'

def begin(db,admin_id):
    opts=settings();
    if any(not opts[k] for k in opts):raise ValueError('Configure SPA_GOOGLE_CLIENT_ID, SPA_GOOGLE_CLIENT_SECRET, SPA_PUBLIC_URL and SPA_TOKEN_KEY on server.')
    state=secrets.token_urlsafe(32)
    with db:db.execute('INSERT INTO oauth_pending VALUES(?,?,?)',(hashlib.sha256(state.encode()).hexdigest(),admin_id,int(time.time())+600))
    q=urllib.parse.urlencode(dict(client_id=opts['SPA_GOOGLE_CLIENT_ID'],redirect_uri=redirect_uri(),response_type='code',scope=SCOPE,
        access_type='offline',prompt='consent',state=state))
    return {'authorization_url':'https://accounts.google.com/o/oauth2/v2/auth?'+q}

def post_form(url,values):
    req=urllib.request.Request(url,urllib.parse.urlencode(values).encode(),headers={'Content-Type':'application/x-www-form-urlencoded'},method='POST')
    with urllib.request.urlopen(req,timeout=30) as r:return json.load(r)

def callback(db,query):
    if query.get('error'):raise ValueError('Google authorization refused: '+query['error'][0])
    state=query.get('state',[''])[0];code=query.get('code',[''])[0]
    if not state or not code:raise ValueError('Missing OAuth response')
    digest=hashlib.sha256(state.encode()).hexdigest()
    row=db.execute('SELECT * FROM oauth_pending WHERE state_hash=? AND expires_at>?',(digest,int(time.time()))).fetchone()
    if not row:raise ValueError('Authorization expired or already used')
    opts=settings()
    token=post_form('https://oauth2.googleapis.com/token',dict(code=code,client_id=opts['SPA_GOOGLE_CLIENT_ID'],client_secret=opts['SPA_GOOGLE_CLIENT_SECRET'],redirect_uri=redirect_uri(),grant_type='authorization_code'))
    refresh=token.get('refresh_token')
    if not refresh:raise ValueError('Google did not grant offline access. Reauthorize with consent.')
    encoded=crypt().encrypt(refresh.encode())
    with db:
        db.execute('DELETE FROM oauth_pending WHERE state_hash=?',(digest,))
        db.execute('INSERT OR REPLACE INTO drive_auth VALUES(1,?,?)',(encoded,int(time.time())))
    return {'connected':True,'message':'Institution Google Drive authorized. You may return to the Android app.'}

def status(db):
    a=db.execute('SELECT connected_at FROM drive_auth WHERE only_id=1').fetchone()
    last=db.execute('SELECT * FROM drive_backups ORDER BY day DESC LIMIT 1').fetchone()
    return {'configured':all(settings().values()),'connected':bool(a), 'last_backup':dict(last) if last else None}

def token(db):
    row=db.execute('SELECT refresh_token FROM drive_auth WHERE only_id=1').fetchone()
    if not row:raise ValueError('Administrator has not connected Google Drive')
    opts=settings()
    refresh=crypt().decrypt(row['refresh_token']).decode()
    result=post_form('https://oauth2.googleapis.com/token',dict(client_id=opts['SPA_GOOGLE_CLIENT_ID'],client_secret=opts['SPA_GOOGLE_CLIENT_SECRET'],refresh_token=refresh,grant_type='refresh_token'))
    return result['access_token']

def backup_payload(db):
    tables={
      'students':'SELECT id,admission,name,group_name,enrolled,active,fingerprint_number FROM students',
      'prayer_schedule':'SELECT * FROM prayer_schedule',
      'special_events':'SELECT * FROM special_events',
      'attendance':'SELECT * FROM attendance',
      'scan_history':'SELECT * FROM device_scans',
      'audit':'SELECT * FROM audit'
    }
    return dict(version=1,generated_utc=datetime.utcnow().isoformat()+'Z',timezone=UTC,
                tables={key:[dict(r) for r in db.execute(sql)] for key,sql in tables.items()})

def upload_file(access_token,name,data):
    # Multipart upload to Drive, app-created files only (drive.file scope).
    boundary='spabackup'+secrets.token_hex(8)
    body=(('--'+boundary+'\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n'+json.dumps({'name':name,'mimeType':'application/octet-stream'})+'\r\n--'+boundary+'\r\nContent-Type: application/octet-stream\r\n\r\n').encode()+data+('\r\n--'+boundary+'--').encode())
    if len(data)>5*1024*1024:
        url='https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name'
        metadata=json.dumps({'name':name,'mimeType':'application/octet-stream'}).encode()
        init=urllib.request.Request(url,metadata,headers={'Authorization':'Bearer '+access_token,
            'Content-Type':'application/json; charset=utf-8','X-Upload-Content-Type':'application/octet-stream',
            'X-Upload-Content-Length':str(len(data))},method='POST')
        with urllib.request.urlopen(init,timeout=60) as response:
            location=response.headers['Location']
        if not location.startswith('https://www.googleapis.com/'):
            raise ValueError('Unexpected resumable upload URL')
        put=urllib.request.Request(location,data,headers={'Content-Type':'application/octet-stream',
            'Content-Length':str(len(data))},method='PUT')
        with urllib.request.urlopen(put,timeout=180) as response:return json.load(response)
    url='https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name'
    request=urllib.request.Request(url,body,headers={'Authorization':'Bearer '+access_token,'Content-Type':'multipart/related; boundary='+boundary},method='POST')
    with urllib.request.urlopen(request,timeout=90) as response:return json.load(response)

def backup(db):
    day=datetime.now(ZoneInfo(UTC)).strftime('%Y-%m-%d')
    if db.execute('SELECT 1 FROM drive_backups WHERE day=?',(day,)).fetchone():
        return {'already_backed_up':True,'day':day}
    db.execute('BEGIN')
    try: contents=json.dumps(backup_payload(db),separators=(',',':'),ensure_ascii=False).encode()
    finally: db.execute('COMMIT')
    encrypted=crypt().encrypt(contents)
    filename='SmartPrayer_'+day+'_encrypted.spabackup'
    file=upload_file(token(db),filename,encrypted)
    with db:db.execute('INSERT INTO drive_backups(day,file_id,file_name,created_at) VALUES(?,?,?,?)',(day,file['id'],filename,int(time.time())))
    return {'uploaded':True,'file_id':file['id'],'filename':filename,'records_bytes':len(contents)}

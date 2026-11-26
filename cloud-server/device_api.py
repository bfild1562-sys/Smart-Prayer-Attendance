"""Device-side attendance API for ESP32/R503Pro scanners.

The device records every scan on microSD before posting. Server preserves a
separate immutable scan event, computes the prayer's attendance window from
its own saved schedule, and never downgrades a PRESENT mark to RED_MARK.
"""
import hashlib, hmac, secrets, sqlite3, json, time
from datetime import datetime
from zoneinfo import ZoneInfo

ZONE = ZoneInfo('Asia/Colombo')

def create_schema(db):
    cols = {x[1] for x in db.execute('PRAGMA table_info(students)')}
    if 'fingerprint_number' not in cols:
        db.execute('ALTER TABLE students ADD COLUMN fingerprint_number INTEGER')
    db.executescript('''
    CREATE UNIQUE INDEX IF NOT EXISTS idx_fp_num ON students(fingerprint_number) WHERE fingerprint_number IS NOT NULL;
    CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, name TEXT NOT NULL,
       key_hash TEXT NOT NULL UNIQUE, enabled INTEGER NOT NULL DEFAULT 1, last_seen INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE IF NOT EXISTS device_scans(event_id TEXT PRIMARY KEY,device_id TEXT NOT NULL,
       sensor_slot INTEGER NOT NULL,confidence INTEGER NOT NULL,at_ms INTEGER NOT NULL,
       student_id TEXT,status TEXT NOT NULL,session_key TEXT,
       session_date TEXT,received_at INTEGER NOT NULL);
    CREATE INDEX IF NOT EXISTS idx_scans_time ON device_scans(at_ms);
    ''')

def _sha(s): return hashlib.sha256(s.encode('utf8')).hexdigest()

def create_device(db, name):
    if not isinstance(name,str) or not 2 <= len(name.strip()) <= 100:
        raise ValueError('Device name must be 2-100 characters')
    token=secrets.token_urlsafe(38);ident=secrets.token_hex(12)
    with db:
        db.execute('INSERT INTO devices(id,name,key_hash) VALUES(?,?,?)',(ident,name.strip(),_sha(token)))
    return dict(device_id=ident,device_key=token,message='Save this secret once; it is not shown again.')

def device_auth(db, header):
    if not header or not header.startswith('Device '): raise PermissionError('Device token required')
    digest=_sha(header[7:]); device=db.execute('SELECT * FROM devices WHERE key_hash=? AND enabled=1',(digest,)).fetchone()
    if not device: raise PermissionError('Invalid device credentials')
    db.execute('UPDATE devices SET last_seen=? WHERE id=?',(int(time.time()),device['id']))
    return device

def current_config(db):
    return dict(
        timezone='Asia/Colombo',server_time_ms=int(time.time()*1000),
        schedules=[dict(x) for x in db.execute('SELECT prayer,opens,deadline,ends,late_minutes,updated_at FROM prayer_schedule')],
        events=[dict(x) for x in db.execute('SELECT id,title,event_date,opens,deadline,ends,late_minutes,group_name FROM special_events WHERE active=1')],
        students=[dict(x) for x in db.execute('SELECT id,admission,name,group_name,fingerprint_number FROM students WHERE active=1 AND fingerprint_number IS NOT NULL')])

def _minute(v):return int(v[:2])*60+int(v[3:])

def pick_session(db, at_ms, student, chosen=None):
    t=datetime.fromtimestamp(at_ms/1000,ZONE);day=t.strftime('%Y-%m-%d')
    minutes=t.hour*60+t.minute+ t.second/60
    candidates=[]
    for s in db.execute('SELECT prayer,opens,deadline,ends,late_minutes FROM prayer_schedule WHERE opens!=""'):
        start=_minute(s['opens']);normal=_minute(s['deadline']);finish=normal+s['late_minutes']
        if start<=minutes<=finish:
            candidates.append((f'prayer:{s["prayer"]}', 'PRESENT' if minutes<=normal else 'RED_MARK'))
    for e in db.execute('SELECT * FROM special_events WHERE active=1 AND event_date=?',(day,)):
        if e['group_name'] and e['group_name'] != student['group_name']: continue
        start=_minute(e['opens']);normal=_minute(e['deadline']);finish=normal+e['late_minutes']
        if start<=minutes<=finish:
            candidates.append((f'event:{e["id"]}', 'PRESENT' if minutes<=normal else 'RED_MARK'))
    if chosen:
        candidates=[(k,v) for k,v in candidates if k==chosen]
    if not candidates:return (None,None,day,'NO_OPEN_SESSION')
    if len(candidates)>1:return (None,None,day,'AMBIGUOUS_SESSION')
    k,v=candidates[0]
    return k,v,day,None

def accept_scans(db,device,events):
    if not isinstance(events,list) or not 1<=len(events)<=80: raise ValueError('Send 1-80 scan events')
    replies=[]
    with db:
        for x in events:
            if not isinstance(x,dict):raise ValueError('Invalid scan event')
            ident=x.get('event_id');slot=x.get('slot');stamp=x.get('at_ms');score=x.get('confidence',0)
            if not isinstance(ident,str) or not 8<=len(ident)<=96:raise ValueError('Missing event ID')
            if not isinstance(slot,int) or not 1<=slot<=1500:raise ValueError('Invalid fingerprint slot')
            if not isinstance(stamp,int) or stamp<1_600_000_000_000 or stamp>int(time.time()*1000)+300_000:
                raise ValueError('Invalid device timestamp; check RTC/timezone')
            if not isinstance(score,int) or score<0 or score>65535:raise ValueError('Invalid confidence')
            old=db.execute('SELECT status,student_id FROM device_scans WHERE event_id=?',(ident,)).fetchone()
            if old:
                replies.append({'event_id':ident,'status':old['status'],'student_id':old['student_id'],'duplicate':True});continue
            num=(slot+1)//2
            student=db.execute('SELECT * FROM students WHERE fingerprint_number=? AND active=1',(num,)).fetchone()
            day=datetime.fromtimestamp(stamp/1000,ZONE).strftime('%Y-%m-%d')
            session,status,day,note=(None,None,day,'UNMAPPED_FINGERPRINT') if not student else pick_session(db,stamp,student,x.get('session_key'))
            if student and not note:
                existing=db.execute('SELECT * FROM attendance WHERE student_id=? AND session_key=? AND session_date=?',
                          (student['id'],session,day)).fetchone()
                if existing:
                    status='ALREADY_MARKED'
                else:
                    db.execute('INSERT INTO attendance(id,student_id,session_key,session_date,status,source,changed_by,recorded_at,note) VALUES(?,?,?,?,?,?,?,?,?)',
                          (ident,student['id'],session,day,status,'FINGERPRINT',device['name'],stamp,'fingerprint slot '+str(slot)))
            else:
                status=note or 'NO_OPEN_SESSION'
            db.execute('INSERT INTO device_scans(event_id,device_id,sensor_slot,confidence,at_ms,student_id,status,session_key,session_date,received_at) VALUES(?,?,?,?,?,?,?,?,?,?)',
                       (ident,device['id'],slot,score,stamp,student['id'] if student else None,status,session,day,int(time.time()*1000)))
            replies.append({'event_id':ident,'status':status,'student_id':student['id'] if student else None,
                            'student_name':student['name'] if student else None,'session_key':session,'duplicate':False})
    return {'events':replies}

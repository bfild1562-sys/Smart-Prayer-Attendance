#!/usr/bin/env python3
"""Smart Prayer central sync service (Python 3.10+ stdlib).
Run behind a TLS reverse proxy for network access. No biometric templates here.
"""
import argparse
import base64
import contextlib
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import sys
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs

DATABASE = os.getenv('SPA_DATABASE', os.path.join(os.path.dirname(__file__), 'central.sqlite3'))
SESSION_TTL = 12 * 3600
PRAYERS = ('Fajr', 'Dhuhr', 'Asr', 'Maghrib', 'Isha')
STATUSES = ('PRESENT','RED_MARK','ABSENT','EXCUSED','NOT_RECORDED')

class AppError(Exception):
    def __init__(self, status, message):
        self.status = status
        self.message = message
        super().__init__(message)

def fail(msg, code=400):
    raise AppError(code, msg)

def connect(path=None):
    db = sqlite3.connect(path or DATABASE, timeout=20, isolation_level=None)
    db.row_factory = sqlite3.Row
    db.execute('PRAGMA foreign_keys=ON')
    db.execute('PRAGMA busy_timeout=10000')
    return db

def init_db(db):
    db.executescript('''
    PRAGMA journal_mode=WAL;
    CREATE TABLE IF NOT EXISTS staff(id TEXT PRIMARY KEY, username TEXT NOT NULL,
        normalized TEXT NOT NULL UNIQUE, role TEXT NOT NULL CHECK(role IN ('admin','staff')),
        salt TEXT NOT NULL, password_hash TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1);
    CREATE TABLE IF NOT EXISTS invitations(code_hash TEXT PRIMARY KEY,role TEXT NOT NULL,
        expires_at INTEGER NOT NULL,created_by TEXT NOT NULL,used_by TEXT,created_at INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS tokens(token_hash TEXT PRIMARY KEY,staff_id TEXT NOT NULL REFERENCES staff(id),
        expires_at INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS students(id TEXT PRIMARY KEY, admission TEXT NOT NULL UNIQUE,
        name TEXT NOT NULL,group_name TEXT NOT NULL,enrolled TEXT NOT NULL,active INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS prayer_schedule(prayer TEXT PRIMARY KEY,opens TEXT NOT NULL,
        deadline TEXT NOT NULL,ends TEXT NOT NULL,late_minutes INTEGER NOT NULL,updated_at INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS special_events(id TEXT PRIMARY KEY,title TEXT NOT NULL,event_date TEXT NOT NULL,
        opens TEXT NOT NULL, deadline TEXT NOT NULL,ends TEXT NOT NULL,late_minutes INTEGER NOT NULL,
        group_name TEXT NOT NULL,active INTEGER NOT NULL,updated_at INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS attendance(id TEXT PRIMARY KEY,student_id TEXT NOT NULL REFERENCES students(id),
        session_key TEXT NOT NULL,session_date TEXT NOT NULL,status TEXT NOT NULL,source TEXT NOT NULL,
        changed_by TEXT NOT NULL,recorded_at INTEGER NOT NULL,note TEXT NOT NULL DEFAULT '',
        UNIQUE(student_id,session_key,session_date));
    CREATE INDEX IF NOT EXISTS idx_attendance_date ON attendance(session_date,session_key);
    CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT,at INTEGER NOT NULL,
        actor TEXT NOT NULL,operation TEXT NOT NULL,detail TEXT NOT NULL);
    ''')

def now(): return int(time.time())
def sha(v): return hashlib.sha256(v.encode('utf8')).hexdigest()

def password_hash(password, salt=None):
    salt = salt or secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac('sha256', password.encode('utf8'), salt, 310_000)
    return base64.b64encode(salt).decode(),base64.b64encode(digest).decode()

def check_password(password, salt, digest):
    computed = password_hash(password, base64.b64decode(salt))[1]
    return hmac.compare_digest(computed, digest)

def username(value):
    if not isinstance(value,str) or not re.fullmatch(r'[A-Za-z0-9_.-]{3,48}',value):
        fail('Username must be 3–48 characters: letters, numbers, _, . or -.')
    return value

def password(value):
    if not isinstance(value,str) or len(value)<10 or len(value)>128:
        fail('Password must be 10–128 characters.')
    return value

def create_admin(db, user, pwd):
    user=username(user);password(pwd)
    if db.execute('SELECT 1 FROM staff LIMIT 1').fetchone(): fail('Users already exist; use administrator invitations.')
    salt,digest=password_hash(pwd)
    with db:
        db.execute('INSERT INTO staff(id,username,normalized,role,salt,password_hash) VALUES(?,?,?,?,?,?)',
            (secrets.token_hex(16),user,user.lower(),'admin',salt,digest))

def audit(db, actor, operation, detail):
    db.execute('INSERT INTO audit(at,actor,operation,detail) VALUES(?,?,?,?)',(now(),actor,operation,detail[:500]))

def authenticate(db, auth):
    if not auth.startswith('Bearer '): fail('Login required.',401)
    row=db.execute('SELECT s.* FROM tokens t JOIN staff s ON s.id=t.staff_id WHERE t.token_hash=? AND t.expires_at>? AND s.active=1',
                   (sha(auth[7:]),now())).fetchone()
    if not row: fail('Session expired; log in again.',401)
    return row

def need_admin(actor):
    if actor['role'] != 'admin': fail('Central administrator permission required.',403)

def text(v, name, limit=128, empty=False):
    if not isinstance(v,str) or len(v)>limit or (not v.strip() and not empty): fail('Invalid '+name)
    return v.strip()

def num(v, name, low=0,high=10**13):
    if isinstance(v,bool): fail('Invalid '+name)
    try: n=int(v)
    except (ValueError,TypeError): fail('Invalid '+name)
    if n<low or n>high: fail('Invalid '+name)
    return n

def date(v):
    text(v,'date',10)
    try:
        if datetime.strptime(v,'%Y-%m-%d').strftime('%Y-%m-%d') != v: fail('Invalid date')
    except ValueError: fail('Invalid YYYY-MM-DD date')
    return v

def clock(v, empty=False):
    if v=='' and empty: return v
    if not isinstance(v,str) or not re.fullmatch(r'(?:[01][0-9]|2[0-3]):[0-5][0-9]',v): fail('Invalid HH:MM time')
    return v

def normalize_student(x):
    return dict(id=text(x.get('id'),'student id',80),admission=text(x.get('admission'),'admission',60),
       name=text(x.get('name'),'student name',120),group_name=text(x.get('group_name',''),'group',80,True),
       enrolled=date(x.get('enrolled')),active=int(x.get('active',True) in (True,1,'1')))

def normalize_schedule(x):
    prayer=x.get('prayer')
    if prayer not in PRAYERS: fail('Invalid prayer')
    opens=clock(x.get('opens',''),True);deadline=clock(x.get('deadline',''),True);ends=clock(x.get('ends',''),True)
    late=num(x.get('late_minutes',0),'late minutes',0,180)
    if any((opens,deadline,ends)):
        if not all((opens,deadline,ends)): fail('Incomplete prayer schedule')
        m=lambda v:int(v[:2])*60+int(v[3:])
        if not (m(opens)<m(deadline)<=m(deadline)+late<=m(ends)): fail('Invalid prayer schedule order')
    return dict(prayer=prayer,opens=opens,deadline=deadline,ends=ends,late_minutes=late,updated_at=num(x.get('updated_at',0),'updated_at'))

def normalize_event(x):
    opens=clock(x.get('opens'));deadline=clock(x.get('deadline'));ends=clock(x.get('ends'))
    late=num(x.get('late_minutes',0),'late minutes',0,180)
    m=lambda v:int(v[:2])*60+int(v[3:])
    if not (m(opens)<m(deadline)<=m(deadline)+late<=m(ends)): fail('Invalid event schedule')
    return dict(id=text(x.get('id'),'event id',80),title=text(x.get('title'),'event title',120),
      event_date=date(x.get('event_date')),opens=opens,deadline=deadline,ends=ends,
      late_minutes=late,group_name=text(x.get('group_name',''),'group',80,True),
      active=int(x.get('active',1) in (True,1,'1')),updated_at=num(x.get('updated_at',0),'updated_at'))

def normalize_attendance(x):
    status=text(x.get('status'),'status',24)
    if status not in STATUSES: fail('Invalid attendance status')
    key=text(x.get('session_key'),'session key',120)
    if not (key.startswith('prayer:') and key[7:] in PRAYERS or key.startswith('event:') and len(key)>6):fail('Invalid session key')
    return dict(id=text(x.get('id'),'event id',80),student_id=text(x.get('student_id'),'student id',80),
      session_key=key,session_date=date(x.get('session_date')),
      status=status,source=text(x.get('source','MANUAL'),'source',40),
      changed_by=text(x.get('changed_by','local'),'local actor',80),recorded_at=num(x.get('recorded_at',0),'recorded_at'),
      note=text(x.get('note',''),'note',500,True))

def upsert_strict(db, table, row, pk, compare, actor):
    cols=list(row);value=row[pk]
    old=db.execute('SELECT * FROM '+table+' WHERE '+pk+'=?',(value,)).fetchone()
    if old:
        if any(old[k] != row[k] for k in compare):
            fail('Cloud conflict for '+table+' '+str(value)+'. Manual reconciliation required.',409)
        return False
    keys=','.join(cols);marks=','.join('?' for k in cols)
    try:db.execute('INSERT INTO '+table+'('+keys+') VALUES('+marks+')',[row[c] for c in cols])
    except sqlite3.IntegrityError: fail('Cloud duplicate or missing reference in '+table+': '+str(value),409)
    return True

def sync_batch(db,actor,payload):
    if not isinstance(payload,dict):fail('Expected JSON object')
    permitted=('students','schedules','events','attendance')
    for k in payload:
        if k not in permitted:fail('Unknown sync collection: '+k)
    if actor['role']!='admin' and any(payload.get(k) for k in ('students','schedules','events')):
        fail('Only central admins may sync students and schedules.',403)
    data={};total=0
    for k in permitted:
        a=payload.get(k,[])
        if not isinstance(a,list) or len(a)>250:fail('Use arrays of at most 250 rows per collection')
        total+=len(a)
        normal={'students':normalize_student,'schedules':normalize_schedule,'events':normalize_event,'attendance':normalize_attendance}[k]
        data[k]=[normal(x) for x in a]
    if total>500:fail('Too many rows')
    counts={k:0 for k in permitted}
    mappings={'students':('students','id',('admission','name','group_name','enrolled','active')),
      'schedules':('prayer_schedule','prayer',('opens','deadline','ends','late_minutes')),
      'events':('special_events','id',('title','event_date','opens','deadline','ends','late_minutes','group_name','active')),
      'attendance':('attendance','id',('student_id','session_key','session_date','status','source','recorded_at','note'))}
    try:
        db.execute('BEGIN IMMEDIATE')
        for label in permitted:
            table,pk,compare=mappings[label]
            for row in data[label]:
                if upsert_strict(db,table,row,pk,compare,actor): counts[label]+=1
        audit(db,actor['username'],'sync_batch',json.dumps(counts))
        db.execute('COMMIT')
    except Exception:
        if db.in_transaction:db.execute('ROLLBACK')
        raise
    return dict(imported=counts,note='Already-uploaded IDs were skipped; conflicts stop the batch.')

def summary(db):
    return {
     'students':db.execute('SELECT COUNT(*) FROM students WHERE active=1').fetchone()[0],
     'attendance':db.execute('SELECT COUNT(*) FROM attendance').fetchone()[0],
     'staff':db.execute('SELECT COUNT(*) FROM staff WHERE active=1').fetchone()[0],
     'recent':[dict(r) for r in db.execute('''SELECT a.student_id,s.name,a.session_key,a.session_date,a.status,a.recorded_at,
          a.changed_by FROM attendance a LEFT JOIN students s ON s.id=a.student_id ORDER BY a.recorded_at DESC LIMIT 30''')],
     'schedules':[dict(r) for r in db.execute('SELECT * FROM prayer_schedule ORDER BY prayer')]
    }

def dispatch(db,method,path,body,headers):
    if method=='GET' and path=='/health':return {'status':'ok','service':'Smart Prayer Central'}
    if method=='POST' and path=='/api/v1/login':
        u=username(body.get('username'));pwd=body.get('password','')
        user=db.execute('SELECT * FROM staff WHERE normalized=?',(u.lower(),)).fetchone()
        if not user or not user['active'] or not check_password(pwd,user['salt'],user['password_hash']):fail('Incorrect username or password.',401)
        token=secrets.token_urlsafe(36)
        with db:db.execute('INSERT INTO tokens(token_hash,staff_id,expires_at) VALUES(?,?,?)',(sha(token),user['id'],now()+SESSION_TTL))
        return {'token':token,'expires_in':SESSION_TTL,'username':user['username'],'role':user['role']}
    if method=='POST' and path=='/api/v1/register':
        code=text(body.get('code'),'invitation code',128);u=username(body.get('username'));pwd=password(body.get('password'))
        invite=db.execute('SELECT * FROM invitations WHERE code_hash=? AND used_by IS NULL AND expires_at>?',(sha(code),now())).fetchone()
        if not invite:fail('Invalid, used or expired invitation code.',403)
        salt,digest=password_hash(pwd);ident=secrets.token_hex(16)
        try:
            with db:
                if db.execute('UPDATE invitations SET used_by=? WHERE code_hash=? AND used_by IS NULL',(ident,sha(code))).rowcount!=1:fail('Invitation already used',409)
                db.execute('INSERT INTO staff(id,username,normalized,role,salt,password_hash) VALUES(?,?,?,?,?,?)',(ident,u,u.lower(),invite['role'],salt,digest))
                audit(db,ident,'register',u)
        except sqlite3.IntegrityError:fail('Username already in use',409)
        return {'registered':True,'message':'Registered. Log in with your central staff account.'}
    actor=authenticate(db,headers.get('Authorization',''))
    if method=='GET' and path=='/api/v1/me':return {'username':actor['username'],'role':actor['role'],'id':actor['id']}
    if method=='POST' and path=='/api/v1/logout':
        with db:db.execute('DELETE FROM tokens WHERE token_hash=?',(sha(headers.get('Authorization','')[7:]),))
        return {'logged_out':True}
    if method=='POST' and path=='/api/v1/invitations':
        need_admin(actor);role=body.get('role','staff')
        if role not in ('staff','admin'):fail('Invalid role')
        code=secrets.token_urlsafe(24);expiry=now()+7*86400
        with db:
            db.execute('INSERT INTO invitations VALUES(?,?,?,?,?,?)',(sha(code),role,expiry,actor['id'],None,now()))
            audit(db,actor['username'],'created_invitation',role)
        return {'code':code,'role':role,'expires_at':expiry,'single_use':True}
    if method=='POST' and path=='/api/v1/sync/batch':return sync_batch(db,actor,body)
    if method=='GET' and path=='/api/v1/summary':return summary(db)
    if method=='GET' and path=='/api/v1/students':return [dict(r) for r in db.execute('SELECT * FROM students ORDER BY name LIMIT 1000')]
    if method=='POST' and path=='/api/v1/attendance/correct':
        d=body; student=text(d.get('student_id'),'student_id',80)
        session=text(d.get('session_key'),'session_key',120)
        day=date(d.get('session_date'))
        status=d.get('status');reason=text(d.get('reason'),'reason',300)
        if status not in STATUSES:fail('Invalid attendance status')
        ident=secrets.token_hex(16)
        with db:
            row=db.execute('SELECT * FROM attendance WHERE student_id=? AND session_key=? AND session_date=?',(student,session,day)).fetchone()
            if row:
                db.execute('UPDATE attendance SET status=?,note=?,changed_by=?,recorded_at=? WHERE id=?',
                    (status,reason,actor['username'],int(time.time()*1000),row['id']))
            else:
                db.execute('INSERT INTO attendance VALUES(?,?,?,?,?,?,?,?,?)',
                    (ident,student,session,day,status,'MANUAL',actor['username'],int(time.time()*1000),reason))
            audit(db,actor['username'],'attendance_correction',json.dumps({'student':student,'session':session,'date':day,'old_status':row['status'] if row else None,'new_status':status,'reason':reason}))
        return {'saved':True}
    fail('Endpoint not found',404)

class Handler(BaseHTTPRequestHandler):
    def respond(self,status,result):
        data=json.dumps(result,ensure_ascii=False,separators=(',',':')).encode('utf8')
        self.send_response(status)
        self.send_header('Content-Type','application/json; charset=utf-8')
        self.send_header('Cache-Control','no-store')
        self.send_header('X-Content-Type-Options','nosniff')
        self.send_header('Content-Length',str(len(data)))
        self.end_headers();self.wfile.write(data)
    def run_api(self):
        db=connect()
        try:
            length=num(self.headers.get('Content-Length','0'),'body size',0,3_000_000)
            payload={}
            if self.command=='POST':
                try: payload=json.loads(self.rfile.read(length).decode('utf8'))
                except (ValueError,UnicodeError):fail('Malformed JSON')
                if not isinstance(payload,dict):fail('JSON object required')
            target=urlsplit(self.path).path
            result=dispatch(db,self.command,target,payload,self.headers)
            self.respond(200,{'ok':True,'data':result})
        except AppError as e:self.respond(e.status,{'ok':False,'error':e.message})
        except sqlite3.Error as e:self.respond(500,{'ok':False,'error':'Database error'})
        except Exception as e:self.respond(500,{'ok':False,'error':'Unexpected server error'})
        finally:db.close()
    def do_GET(self):self.run_api()
    def do_POST(self):self.run_api()
    def log_message(self, format, *args):pass

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('command',nargs='?',choices=('serve','init-admin'),default='serve')
    parser.add_argument('--username',default='admin');parser.add_argument('--host',default='127.0.0.1');parser.add_argument('--port',type=int,default=int(os.getenv('PORT','8080')))
    args=parser.parse_args()
    if args.command=='init-admin':
        import getpass
        pwd=getpass.getpass('Choose central admin password (10+ chars): ')
        db=connect();init_db(db);create_admin(db,args.username,pwd);db.close();print('Central admin created.');return
    db=connect();init_db(db);db.close()
    if args.host!='127.0.0.1':print('WARNING: Serve behind HTTPS reverse proxy. Do not expose HTTP directly.')
    print('Smart Prayer server listening at',args.host,args.port)
    ThreadingHTTPServer((args.host,args.port),Handler).serve_forever()

if __name__=='__main__':main()

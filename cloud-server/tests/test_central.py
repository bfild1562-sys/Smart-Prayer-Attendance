import importlib.util
import os
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('spa_server',os.path.join(os.path.dirname(__file__),'..','server.py'))
app=importlib.util.module_from_spec(spec);spec.loader.exec_module(app)

class CentralTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.db=app.connect(os.path.join(self.temp.name,'db.sqlite'));app.init_db(self.db)
        app.create_admin(self.db,'rootadmin','securepass1234')
        self.auth={'Authorization':'Bearer '+self.call('POST','/api/v1/login',{'username':'rootadmin','password':'securepass1234'})['token']}
    def tearDown(self):self.db.close();self.temp.cleanup()
    def call(self,method,path,data=None,headers=None):return app.dispatch(self.db,method,path,data or {},headers or {})
    def test_invitation_one_time_and_auth(self):
        code=self.call('POST','/api/v1/invitations',{'role':'staff'},self.auth)['code']
        self.call('POST','/api/v1/register',{'code':code,'username':'staffA','password':'othersecure123'})
        with self.assertRaises(app.AppError):self.call('POST','/api/v1/register',{'code':code,'username':'staffB','password':'othersecure123'})
        token=self.call('POST','/api/v1/login',{'username':'staffA','password':'othersecure123'})['token']
        self.assertEqual(self.call('GET','/api/v1/me',{}, {'Authorization':'Bearer '+token})['role'],'staff')
        with self.assertRaises(app.AppError):self.call('POST','/api/v1/invitations',{}, {'Authorization':'Bearer '+token})
    def test_sync_idempotency_and_conflict(self):
        student={'id':'s01','admission':'A01','name':'Student One','group_name':'G','enrolled':'2026-11-01','active':True}
        row={'id':'r1','student_id':'s01','session_key':'prayer:Asr','session_date':'2026-11-25','status':'PRESENT','source':'MANUAL','changed_by':'offline','recorded_at':12345,'note':'staff marked'}
        batch={'students':[student],'attendance':[row]}
        self.assertEqual(self.call('POST','/api/v1/sync/batch',batch,self.auth)['imported']['attendance'],1)
        self.assertEqual(self.call('POST','/api/v1/sync/batch',batch,self.auth)['imported']['attendance'],0)
        other=dict(row,id='r2',status='RED_MARK')
        with self.assertRaises(app.AppError):self.call('POST','/api/v1/sync/batch',{'attendance':[other]},self.auth)
        self.assertEqual(self.call('GET','/api/v1/summary',{},self.auth)['attendance'],1)
        self.call('POST','/api/v1/attendance/correct',{'student_id':'s01','session_key':'prayer:Asr','session_date':'2026-11-25','status':'RED_MARK','reason':'Staff correction'},self.auth)
        self.assertEqual(self.db.execute('SELECT status FROM attendance').fetchone()[0],'RED_MARK')
    def test_snapshot_pages_include_older_attendance(self):
        self.db.execute("INSERT INTO students VALUES('s','a','Test','A','2026-01-01',1,NULL)")
        for i in range(510):
            self.db.execute("INSERT INTO attendance VALUES(?,?,?,?,?,?,?,?,?)",
                (str(i),'s','event:'+str(i),'2026-11-25','PRESENT','MANUAL','test',i,''))
        after=''
        ids=[]
        while True:
            result=self.call('POST','/api/v1/sync/page',{'collection':'attendance','after':after},self.auth)
            ids.extend(r['id'] for r in result['rows'])
            after=result['next']
            if not after:break
        self.assertEqual(len(ids),510)
        self.assertEqual(len(set(ids)),510)

    def test_roll_back_on_failure(self):
        student={'id':'s01','admission':'A01','name':'Student One','group_name':'G','enrolled':'2026-11-01','active':True}
        bogus={'id':'r','student_id':'missing','session_key':'prayer:Asr','session_date':'2026-11-25','status':'PRESENT','source':'MANUAL','changed_by':'staff','recorded_at':42,'note':''}
        with self.assertRaises(app.AppError):self.call('POST','/api/v1/sync/batch',{'students':[student],'attendance':[bogus]},self.auth)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM students').fetchone()[0],0)

if __name__=='__main__':unittest.main()

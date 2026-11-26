import os,sys,sqlite3,tempfile,unittest
from datetime import datetime
from zoneinfo import ZoneInfo
sys.path.insert(0,os.path.dirname(os.path.dirname(__file__)))
import server,device_api

class DeviceIntake(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.db=server.connect(os.path.join(self.temp.name,'test.sqlite'))
        server.init_db(self.db)
        self.db.execute("INSERT INTO students(id,admission,name,group_name,enrolled,active,fingerprint_number) VALUES('s1','A001','Student One','A','2026-11-01',1,1)")
        self.db.execute("UPDATE prayer_schedule SET opens='15:15',deadline='15:30',ends='15:50',late_minutes=10 WHERE prayer='Asr'") if self.db.execute('SELECT 1 FROM prayer_schedule WHERE prayer=?',('Asr',)).fetchone() else self.db.execute("INSERT INTO prayer_schedule VALUES('Asr','15:15','15:30','15:50',10,0)")
        self.device=device_api.create_device(self.db,'Hall A')
        self.key='Device '+self.device['device_key']
    def tearDown(self):self.db.close();self.temp.cleanup()
    def event(self,id,minute,slot=1):
        dt=datetime(2026,11,25,15,minute,tzinfo=ZoneInfo('Asia/Colombo'))
        return {'event_id':id,'slot':slot,'at_ms':int(dt.timestamp()*1000),'confidence':175}
    def test_present_late_duplicate(self):
        dev=device_api.device_auth(self.db,self.key)
        out=device_api.accept_scans(self.db,dev,[self.event('id-normal-1111',20)])
        self.assertEqual(out['events'][0]['status'],'PRESENT')
        out=device_api.accept_scans(self.db,dev,[self.event('id-late-2222',35)])
        self.assertEqual(out['events'][0]['status'],'ALREADY_MARKED')
        stored=self.db.execute("SELECT status FROM attendance WHERE student_id='s1'").fetchone()[0]
        self.assertEqual(stored,'PRESENT')
        same=device_api.accept_scans(self.db,dev,[self.event('id-normal-1111',20)])
        self.assertTrue(same['events'][0]['duplicate'])
    def test_late_first(self):
        d=device_api.device_auth(self.db,self.key)
        self.assertEqual(device_api.accept_scans(self.db,d,[self.event('id-late-3333',35)])['events'][0]['status'],'RED_MARK')
    def test_closed_and_unmapped(self):
        d=device_api.device_auth(self.db,self.key)
        self.assertEqual(device_api.accept_scans(self.db,d,[self.event('id-closed-4444',42)])['events'][0]['status'],'NO_OPEN_SESSION')
        self.assertEqual(device_api.accept_scans(self.db,d,[self.event('id-unmapped-5555',20,slot=9)])['events'][0]['status'],'UNMAPPED_FINGERPRINT')
if __name__=='__main__':unittest.main()

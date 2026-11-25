import importlib.util
import json
import os
import tempfile
import threading
import unittest
import urllib.request
from http.server import ThreadingHTTPServer

spec=importlib.util.spec_from_file_location('spa_http',os.path.join(os.path.dirname(__file__),'..','server.py'))
app=importlib.util.module_from_spec(spec);spec.loader.exec_module(app)

class HTTPIntegration(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.old=app.DATABASE;app.DATABASE=os.path.join(self.temp.name,'central.sqlite')
        conn=app.connect();app.init_db(conn);app.create_admin(conn,'admin1','verysecurepw123');conn.close()
        self.server=ThreadingHTTPServer(('127.0.0.1',0),app.Handler)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.base='http://127.0.0.1:%d'%self.server.server_port
    def tearDown(self):
        self.server.shutdown();self.server.server_close();app.DATABASE=self.old;self.temp.cleanup()
    def call(self,path,payload=None,token=''):
        data=None if payload is None else json.dumps(payload).encode()
        req=urllib.request.Request(self.base+path,data=data,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
        return json.load(urllib.request.urlopen(req))['data']
    def test_login_create_invite_new_staff(self):
        self.assertEqual(self.call('/health')['status'],'ok')
        admin=self.call('/api/v1/login',{'username':'admin1','password':'verysecurepw123'})['token']
        invite=self.call('/api/v1/invitations',{'role':'staff'},admin)['code']
        self.call('/api/v1/register',{'username':'newstaff','password':'securestaffpw123','code':invite})
        staff=self.call('/api/v1/login',{'username':'newstaff','password':'securestaffpw123'})['token']
        self.assertEqual(self.call('/api/v1/me',token=staff)['role'],'staff')
        self.assertEqual(self.call('/api/v1/summary',token=staff)['staff'],2)

if __name__=='__main__':unittest.main()

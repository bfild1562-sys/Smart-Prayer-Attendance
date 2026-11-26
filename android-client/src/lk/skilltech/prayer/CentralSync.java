package lk.skilltech.prayer;

import android.app.Activity;
import android.database.Cursor;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Manual opt-in synchronization against the Smart Prayer central server.
 * Credentials never enter the URL; bearer token remains in memory and expires on restart.
 * Does not overwrite local SQLite or claim the cloud is connected until authenticated.
 */
final class CentralSync {
    interface Callback { void report(String event, JSONObject data); }
    private final Activity activity;
    private final MainActivity.Store store;
    private final Callback callback;
    private volatile String token="", role="", username="", error="";
    private volatile boolean busy=false;
    private volatile JSONObject lastSummary;
    private volatile JSONArray devices = new JSONArray();
    private volatile JSONObject institutionDrive = new JSONObject();
    private static final String INSTITUTION_SERVER="https://smart-prayer-attendance-production.up.railway.app";
    private final String server=INSTITUTION_SERVER;

    CentralSync(Activity activity, MainActivity.Store store, Callback callback) {
        this.activity=activity; this.store=store;this.callback=callback;
    }
    JSONObject status() throws JSONException {
        JSONObject j=new JSONObject().put("server",server).put("connected",!token.isEmpty())
            .put("username",username).put("role",role).put("busy",busy).put("error",error);
        if(lastSummary!=null)j.put("summary",lastSummary);
        j.put("devices",devices);j.put("institution_drive",institutionDrive);
        return j;
    }
    private void emit(String ev,String message) {
        try { emit(ev,new JSONObject().put("message",message).put("state",status())); }
        catch(JSONException e){/* status is simple scalar values */}
    }
    private void emit(String ev,JSONObject data){activity.runOnUiThread(()->callback.report(ev,data));}
    private synchronized boolean begin(){if(busy)return false;busy=true;return true;}
    private void finish(){busy=false;}
    private interface Work { void run() throws Exception; }
    private void asynchronous(String task,Work work){
        if(!begin()){emit("error","Another central operation is in progress.");return;}
        new Thread(()->{
            try {work.run();error="";emit("success",task+" completed.");}
            catch(Exception ex){error=ex.getMessage()==null?"Network or server error":ex.getMessage();emit("error",task+": "+error);}
            finally{finish();emit("status",task+" finished.");}
        },"SmartPrayerCentral").start();
    }
    private static String validatedUrl(String value){
        if(value==null)throw new IllegalArgumentException("Enter your institution's HTTPS server address.");
        String base=value.trim();
        try {
            URI u=new URI(base);
            if(!"https".equalsIgnoreCase(u.getScheme()) || u.getHost()==null || u.getRawUserInfo()!=null || u.getQuery()!=null || u.getFragment()!=null || u.getPath()!=null && !u.getPath().replace("/", "").isEmpty())
                throw new IllegalArgumentException("Enter a secure https://server-domain address without path or password.");
            if (!INSTITUTION_SERVER.equalsIgnoreCase(base.replaceAll("/+$","")))
                throw new IllegalArgumentException("Use the configured Smart Prayer institution server.");
            return INSTITUTION_SERVER;
        } catch(java.net.URISyntaxException e){throw new IllegalArgumentException("Invalid HTTPS server URL.");}
    }
    private JSONObject request(String base,String endpoint,JSONObject payload,String bearer) throws Exception {
        URL url=new URL(base+endpoint);
        if(!"https".equals(url.getProtocol()))throw new IOException("HTTPS is required.");
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setConnectTimeout(12000);c.setReadTimeout(25000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept","application/json");
        if(!bearer.isEmpty())c.setRequestProperty("Authorization","Bearer "+bearer);
        c.setRequestMethod(payload==null?"GET":"POST");
        if(payload!=null){
            byte[] data=payload.toString().getBytes(StandardCharsets.UTF_8);
            if(data.length>2_000_000)throw new IOException("Sync batch exceeds 2 MB; reduce batch size.");
            c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");
            c.setFixedLengthStreamingMode(data.length);
            try(OutputStream out=c.getOutputStream()){out.write(data);}
        }
        try {
            int code=c.getResponseCode();
            if(code>=300&&code<400)throw new IOException("Redirect rejected. Use the final HTTPS address.");
            InputStream stream=code>=400?c.getErrorStream():c.getInputStream();
            if(stream==null)throw new IOException("No server response (HTTP "+code+")");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=stream){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(bytes.size()+n>350000)throw new IOException("Server response too large");bytes.write(b,0,n);}}
            String operation=c.getRequestMethod()+" "+url.getAuthority()+endpoint;
            JSONObject response;
            try {
                response=new JSONObject(bytes.toString("UTF-8"));
            } catch (JSONException invalidJson) {
                throw new IOException(operation+" returned HTTP "+code+" without a JSON response.");
            }
            if(code<200||code>=300||!response.optBoolean("ok",false))
                throw new IOException(operation+" returned HTTP "+code+": "+response.optString("error","Request failed"));
            return response.getJSONObject("data");
        } finally{c.disconnect();}
    }
    void login(String base,String user,String password){
        asynchronous("Central login",()->{
            final String verified=validatedUrl(base);
            JSONObject body=new JSONObject().put("username",user).put("password",password);
            JSONObject result=request(verified,"/api/v1/login",body,"");
            String newToken=result.getString("token");
            JSONObject identity=request(verified,"/api/v1/me",null,newToken);
            String authenticatedUser=identity.getString("username");
            String authenticatedRole=identity.getString("role");
            token=newToken;username=authenticatedUser;role=authenticatedRole;
            JSONObject sum=request(server,"/api/v1/summary",null,token);
            lastSummary=sum;
            emit("summary",sum);
            pull();refreshAdminMetadata();
        });
    }
    void register(String base,String user,String password,String invite){
        asynchronous("Staff registration",()->{
            String verified=validatedUrl(base);
            request(verified,"/api/v1/register",new JSONObject().put("username",user).put("password",password).put("code",invite),"");
            emit("registered","Central staff account created. You can now sign in.");
        });
    }
    void invite(){
        asynchronous("Invitation",()->{
            requireAdmin();
            JSONObject info=request(server,"/api/v1/invitations",new JSONObject().put("role","staff"),token);
            emit("invite",info);
        });
    }
    void logout(){token="";role="";username="";lastSummary=null;error="";emit("status","Central session disconnected from this phone.");}
    void refresh(){
        asynchronous("Cloud overview",()->{requireLogin();lastSummary=request(server,"/api/v1/summary",null,token);pull();refreshAdminMetadata();emit("summary",lastSummary);});
    }
    void requestSync(){ if(!token.isEmpty() && !busy) upload(); }
    JSONArray deviceList(){return devices;}
    void createDevice(String name){
        asynchronous("Device registration",()->{
            requireAdmin();
            JSONObject info=request(server,"/api/v1/devices",new JSONObject().put("name",name),token);
            emit("deviceCreated",info);refreshAdminMetadata();
            // The key is displayed ONCE and never stored in the app.
        });
    }
    private void refreshAdminMetadata(){
        if(!"admin".equals(role))return;
        try {
            devices=request(server,"/api/v1/devices",null,token).getJSONArray("devices");
            institutionDrive=request(server,"/api/v1/admin/drive/status",null,token);
        }catch(Exception ignored){ /* Existing attendance remains usable if admin metadata fails */ }
    }
    void connectInstitutionDrive(){
        asynchronous("Institution Google Drive authorization",()->{
            requireAdmin();
            String url=request(server,"/api/v1/admin/drive/start",new JSONObject(),token).getString("authorization_url");
            if(!url.startsWith("https://accounts.google.com/"))throw new IllegalArgumentException("Unexpected Google sign-in URL");
            activity.runOnUiThread(()->{
                android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(url));
                activity.startActivity(intent);
            });
            emit("progress","Google authorization opened in your browser. Return here after consent.");
        });
    }
    void backupInstitutionDrive(){
        asynchronous("Institution Google Drive backup",()->{
            requireAdmin();
            JSONObject info=request(server,"/api/v1/admin/drive/backup",new JSONObject(),token);
            emit("progress",info);
            refreshAdminMetadata();
        });
    }
    private static ContentValues values(JSONObject obj)throws JSONException{
        ContentValues cv=new ContentValues();
        for(java.util.Iterator<String> it=obj.keys();it.hasNext();){
            String k=it.next();Object value=obj.opt(k);
            if(value==null || value==JSONObject.NULL)cv.putNull(k);
            else if(value instanceof Integer)cv.put(k,(Integer)value);
            else if(value instanceof Long)cv.put(k,(Long)value);
            else if(value instanceof Boolean)cv.put(k,(Boolean)value ? 1:0);
            else cv.put(k,String.valueOf(value));
        }
        return cv;
    }
    private void pull()throws Exception {
        if(lastSummary!=null && lastSummary.optBoolean("paged_snapshot")){
            for(String collection:new String[]{"students","schedules","events","attendance"}){
                String after="";
                do {
                    JSONObject page=request(server,"/api/v1/sync/page",
                        new JSONObject().put("collection",collection).put("after",after),token);
                    JSONObject snapshot=new JSONObject();
                    for(String key:new String[]{"students","schedules","events","attendance"})
                        snapshot.put(key,key.equals(collection)?page.getJSONArray("rows"):new JSONArray());
                    applySnapshot(snapshot);
                    after=page.getString("next");
                } while(!after.isEmpty());
            }
        }else{
            applySnapshot(request(server,"/api/v1/sync/snapshot",null,token));
        }
        emit("progress","Shared records refreshed.");
    }
    private void applySnapshot(JSONObject snapshot)throws Exception {
        SQLiteDatabase db=store.getWritableDatabase();
        db.beginTransaction();
        try {
            JSONArray students=snapshot.getJSONArray("students");
            for(int i=0;i<students.length();i++){
                JSONObject data=students.getJSONObject(i);
                db.insertWithOnConflict("students",null,values(data),SQLiteDatabase.CONFLICT_IGNORE);
                db.update("students",values(data),"id=?",new String[]{data.getString("id")});
            }
            JSONArray schedules=snapshot.getJSONArray("schedules");
            for(int i=0;i<schedules.length();i++){
                JSONObject data=schedules.getJSONObject(i);
                db.update("prayer_schedule",values(data),"prayer=? AND updated_at < ?",
                    new String[]{data.getString("prayer"),String.valueOf(data.getLong("updated_at"))});
            }
            JSONArray events=snapshot.getJSONArray("events");
            for(int i=0;i<events.length();i++){
                JSONObject data=events.getJSONObject(i);
                ContentValues values=values(data);
                // created_by is mandatory locally, but not part of shared event response.
                values.put("created_by","central");
                db.insertWithOnConflict("special_events",null,values,SQLiteDatabase.CONFLICT_IGNORE);
                db.update("special_events",values(data),"id=? AND updated_at < ?",
                    new String[]{data.getString("id"),String.valueOf(data.getLong("updated_at"))});
            }
            JSONArray rows=snapshot.getJSONArray("attendance");
            for(int i=0;i<rows.length();i++){
                JSONObject data=rows.getJSONObject(i);
                ContentValues record=values(data);
                db.insertWithOnConflict("attendance",null,record,SQLiteDatabase.CONFLICT_IGNORE);
                // Recent, trusted server updates (manual corrections) supersede older local copies.
                db.update("attendance",record,"id=? AND recorded_at < ?",
                   new String[]{data.getString("id"),String.valueOf(data.getLong("recorded_at"))});
            }
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }
    private void requireLogin(){if(token.isEmpty())throw new IllegalStateException("Log in to the central server first.");}
    private void requireAdmin(){requireLogin();if(!"admin".equals(role))throw new IllegalStateException("Central administrator account required.");}
    private static JSONArray rows(SQLiteDatabase db,String sql,int offset,int limit)throws JSONException{
        JSONArray list=new JSONArray();
        try(Cursor c=db.rawQuery(sql+" LIMIT "+limit+" OFFSET "+offset,null)){
            while(c.moveToNext()){
                JSONObject obj=new JSONObject();
                for(int i=0;i<c.getColumnCount();i++){
                    int t=c.getType(i);
                    if(t==Cursor.FIELD_TYPE_NULL)obj.put(c.getColumnName(i),JSONObject.NULL);
                    else if(t==Cursor.FIELD_TYPE_INTEGER)obj.put(c.getColumnName(i),c.getLong(i));
                    else obj.put(c.getColumnName(i),c.getString(i));
                }
                list.put(obj);
            }
        }
        return list;
    }
    void upload(){
        asynchronous("Local-to-central upload",()->{
            requireLogin();
            SQLiteDatabase db=store.getReadableDatabase();
            String[][] collections={
                {"students","SELECT id,admission,name,group_name,enrolled,active,fingerprint_number FROM students ORDER BY id"},
                {"schedules","SELECT prayer,opens,deadline,ends,late_minutes,updated_at FROM prayer_schedule ORDER BY prayer"},
                {"events","SELECT id,title,event_date,opens,deadline,ends,late_minutes,group_name,active,updated_at FROM special_events ORDER BY id"},
                {"attendance","SELECT id,student_id,session_key,session_date,status,source,changed_by,recorded_at,note FROM attendance ORDER BY id"}
            };
            int synced=0;
            for(String[] collection:collections){
                String label=collection[0];
                if(!"admin".equals(role) && !label.equals("attendance"))continue;
                for(int offset=0;;offset+=150){
                    JSONArray batch=rows(db,collection[1],offset,150);
                    if(batch.length()==0)break;
                    JSONObject payload=new JSONObject().put(label,batch);
                    request(server,"/api/v1/sync/batch",payload,token);
                    synced+=batch.length();
                    emit("progress",new JSONObject().put("message",synced+" local rows checked against central database"));
                }
            }
            lastSummary=request(server,"/api/v1/summary",null,token);
            pull();refreshAdminMetadata();
            emit("summary",lastSummary);
            emit("uploaded",new JSONObject().put("message","Upload finished; "+synced+" rows checked. Conflicts are never silently overwritten."));
        });
    }
}

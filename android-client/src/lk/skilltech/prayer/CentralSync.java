package lk.skilltech.prayer;

import android.app.Activity;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.content.SharedPreferences;
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
    private final SharedPreferences prefs;
    private final Callback callback;
    private volatile String token="", role="", username="", error="";
    private volatile boolean busy=false;
    private volatile JSONObject lastSummary;
    private String server="";

    CentralSync(Activity activity, MainActivity.Store store, SharedPreferences prefs, Callback callback) {
        this.activity=activity; this.store=store;this.prefs=prefs;this.callback=callback;
        this.server=prefs.getString("centralServer","");
    }
    JSONObject status() throws JSONException {
        JSONObject j=new JSONObject().put("server",server).put("connected",!token.isEmpty())
            .put("username",username).put("role",role).put("busy",busy).put("error",error);
        if(lastSummary!=null)j.put("summary",lastSummary);
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
            return base.replaceAll("/+$","");
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
            JSONObject response=new JSONObject(bytes.toString("UTF-8"));
            if(code<200||code>=300||!response.optBoolean("ok",false))
                throw new IOException("Server HTTP "+code+": "+response.optString("error","Request failed"));
            return response.getJSONObject("data");
        } finally{c.disconnect();}
    }
    void login(String base,String user,String password){
        asynchronous("Central login",()->{
            final String verified=validatedUrl(base);
            JSONObject body=new JSONObject().put("username",user).put("password",password);
            JSONObject result=request(verified,"/api/v1/login",body,"");
            token=result.getString("token");username=result.getString("username");role=result.getString("role");server=verified;
            prefs.edit().putString("centralServer",verified).apply();
            JSONObject sum=request(server,"/api/v1/summary",null,token);
            lastSummary=sum;
            emit("summary",sum);
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
        asynchronous("Cloud overview",()->{requireLogin();lastSummary=request(server,"/api/v1/summary",null,token);emit("summary",lastSummary);});
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
                {"students","SELECT id,admission,name,group_name,enrolled,active FROM students ORDER BY id"},
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
            emit("summary",lastSummary);
            emit("uploaded",new JSONObject().put("message","Upload finished; "+synced+" rows checked. Conflicts are never silently overwritten."));
        });
    }
}

package lk.skilltech.prayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.json.*;

public class MainActivity extends Activity {
    private static final String HOME="https://appassets.androidplatform.net/";
    private WebView web;
    private Store store;
    private SharedPreferences prefs;
    private StaffAccounts staff;
    private AttendanceStore attendance;
    private DriveConnection drive;
    private String exportFrom="",exportTo="";
    private boolean choosingDocument;
    private long backgroundAt;
    private static final int EXPORT=100, IMPORT=101, EXPORT_ATTENDANCE=102;

    static final class Store extends SQLiteOpenHelper {
        Store(Context c){super(c,"students.sqlite",null,3);}
        public void onCreate(SQLiteDatabase db){
            db.execSQL("CREATE TABLE students(id TEXT PRIMARY KEY, admission TEXT COLLATE NOCASE UNIQUE NOT NULL, name TEXT NOT NULL, group_name TEXT NOT NULL, enrolled TEXT NOT NULL, active INTEGER NOT NULL)");
            StaffAccounts.schema(db);
            StaffAccounts.invitationSchema(db);
            AttendanceStore.schema(db);
        }
        public void onUpgrade(SQLiteDatabase db,int from,int to){
            if(from<1||from>3||to!=3)throw new IllegalStateException("Unsupported database version");
            if(from==1)StaffAccounts.schema(db);
            if(from<=2){StaffAccounts.invitationSchema(db);AttendanceStore.schema(db);}
        }
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        prefs=getSharedPreferences("private",MODE_PRIVATE);
        store=new Store(this);
        staff=new StaffAccounts(store.getWritableDatabase(),prefs);
        attendance=new AttendanceStore(store.getWritableDatabase(),staff);
        drive=new DriveConnection(this,prefs,(message,success)->{
            if(!isDestroyed())web.evaluateJavascript("window.onDriveUpdate && window.onDriveUpdate("+JSONObject.quote(message)+","+success+")",null);
        });
        getWindow().setStatusBarColor(Color.rgb(17,45,72));
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        web=new WebView(this);
        android.widget.FrameLayout frame=new android.widget.FrameLayout(this);
        frame.addView(web);
        frame.setOnApplyWindowInsetsListener((v,in)->{
            v.setPadding(in.getSystemWindowInsetLeft(),in.getSystemWindowInsetTop(),in.getSystemWindowInsetRight(),in.getSystemWindowInsetBottom());
            return in.consumeSystemWindowInsets();
        });
        setContentView(frame);
        WebSettings settings=web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                String url=r.getUrl().toString();
                String file=url.equals(HOME)?"index.html":url.equals(HOME+"app.js")?"app.js":url.equals(HOME+"style.css")?"style.css":null;
                if(file!=null)try{
                    return new WebResourceResponse(file.endsWith(".html")?"text/html":file.endsWith(".js")?"application/javascript":"text/css","UTF-8",getAssets().open(file));
                }catch(IOException ignored){}
                return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
            }
        });
        web.addJavascriptInterface(new Bridge(),"Phone");
        web.loadUrl(HOME);
    }

    @Override public void onStop(){super.onStop();backgroundAt=android.os.SystemClock.elapsedRealtime();}
    @Override public void onStart(){super.onStart();if(staff.signedIn() && !staff.remembered() && !choosingDocument && backgroundAt!=0 && android.os.SystemClock.elapsedRealtime()-backgroundAt>60000){staff.logout();web.reload();}}
    @Override public void onBackPressed(){web.evaluateJavascript("window.back ? window.back() : false",v->{if(!"true".equals(v))super.onBackPressed();});}
    @Override public void onDestroy(){web.removeJavascriptInterface("Phone");web.destroy();store.close();super.onDestroy();}

    private void requireUnlock(){if(!staff.signedIn())throw new IllegalStateException("Unlock the register first.");}
    static byte[] hash(String pass,byte[] salt) throws Exception{
        PBEKeySpec spec=new PBEKeySpec(pass.toCharArray(),salt,210000,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
        finally{spec.clearPassword();}
    }
    private String pack(Object result,String error){
        JSONObject obj=new JSONObject();
        try{if(error!=null)obj.put("error",error);else obj.put("data",result);}catch(JSONException ignored){}
        return obj.toString();
    }
    public final class Bridge {
        @JavascriptInterface public String call(String action,String payload){
            try{
                JSONObject d=new JSONObject(payload);
                switch(action){
                case "state":return pack(staff.state(),null);
                case "signup":return pack(staff.signup(d),null);
                case "login":staff.login(d);return pack(true,null);
                case "lock":staff.logout();return pack(true,null);
                case "pending":return pack(staff.pending(),null);
                case "approve":staff.approve(d.getString("id"));return pack(true,null);
                case "activity":return pack(staff.activity(),null);
                }
                requireUnlock();
                switch(action){
                case "list":return pack(roster(),null);
                case "driveStatus":return pack(drive.status(),null);
                case "driveConnect":
                    if(!staff.state().optBoolean("admin",false))throw new IllegalStateException("Administrator access required.");
                    runOnUiThread(()->drive.connectAndTest());return pack(true,null);
                case "appSigning":return pack(new JSONObject().put("package",getPackageName()).put("sha1",appSha1()),null);
                case "copySigning":runOnUiThread(()->{android.content.ClipboardManager c=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);c.setPrimaryClip(android.content.ClipData.newPlainText("Smart Prayer SHA-1",appSha1()));toast("APK SHA-1 copied.");});return pack(true,null);
                case "overview":return pack(attendance.overview(),null);
                case "schedules":return pack(attendance.schedules(),null);
                case "scheduleSave":attendance.saveSchedule(d);return pack(true,null);
                case "events":return pack(attendance.events(),null);
                case "eventSave":attendance.saveEvent(d);return pack(true,null);
                case "eventCancel":attendance.cancelEvent(d);return pack(true,null);
                case "attendanceMark":attendance.mark(d);return pack(true,null);
                case "attendanceList":return pack(attendance.attendance(d),null);
                case "attendanceAudit":return pack(attendance.audit(d),null);
                case "inviteGenerate":return pack(staff.generateInvite(),null);
                case "invitations":return pack(staff.invitations(),null);
                case "inviteRevoke":staff.revokeInvite(d.getString("id"));return pack(true,null);
                case "staffList":return pack(staff.members(),null);
                case "staffAccess":staff.setAccess(d.getString("id"),d.optBoolean("active"));return pack(true,null);
                case "attendanceExport":exportFrom=d.optString("from","");exportTo=d.optString("to","");runOnUiThread(()->pickAttendance());return pack(true,null);
                case "save":save(d,staff.identity());return pack(true,null);
                case "export":case "import":
                    runOnUiThread(()->pick(action.equals("export")));return pack(true,null);
                default:throw new IllegalArgumentException("Unknown operation.");
                }
            }catch(android.database.sqlite.SQLiteConstraintException e){return pack(null,"This admission number is already in use.");}
            catch(IllegalArgumentException|IllegalStateException e){return pack(null,e.getMessage());}
            catch(Exception e){return pack(null,"Could not complete this operation.");}
        }
    }

    private String appSha1(){
        try{
            android.content.pm.PackageInfo info;
            android.content.pm.Signature[] signatures;
            if(android.os.Build.VERSION.SDK_INT>=28){
                info=getPackageManager().getPackageInfo(getPackageName(),android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
                signatures=info.signingInfo.getApkContentsSigners();
            }else{
                info=getPackageManager().getPackageInfo(getPackageName(),android.content.pm.PackageManager.GET_SIGNATURES);
                signatures=info.signatures;
            }
            byte[] digest=java.security.MessageDigest.getInstance("SHA-1").digest(signatures[0].toByteArray());
            StringBuilder b=new StringBuilder();
            for(byte part:digest){if(b.length()>0)b.append(':');b.append(String.format(java.util.Locale.US,"%02X",part&255));}
            return b.toString();
        }catch(Exception e){return "Unavailable ("+e.getClass().getSimpleName()+")";}
    }

    private JSONArray roster() throws JSONException{
        JSONArray result=new JSONArray();
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id,admission,name,group_name,enrolled,active FROM students ORDER BY name COLLATE NOCASE,admission",null)){
            while(c.moveToNext()){
                JSONObject s=new JSONObject();
                for(int i=0;i<5;i++)s.put(c.getColumnName(i),c.getString(i));
                s.put("active",c.getInt(5)==1);result.put(s);
            }
        }
        return result;
    }
    private String field(JSONObject d,String key,int max)throws JSONException{
        String value=d.getString(key).trim();
        if(value.isEmpty()||value.length()>max)throw new IllegalArgumentException("Check the "+key+" field.");
        return value;
    }
    private void save(JSONObject d,String actor)throws JSONException{
        ContentValues v=new ContentValues();
        v.put("name",field(d,"name",120));v.put("admission",field(d,"admission",60));v.put("group_name",field(d,"group_name",80));
        String date=field(d,"enrolled",10);
        try{LocalDate.parse(date);}catch(java.time.DateTimeException e){throw new IllegalArgumentException("Choose a valid enrollment date.");}
        v.put("enrolled",date);v.put("active",d.getBoolean("active")?1:0);
        SQLiteDatabase db=store.getWritableDatabase();
        db.beginTransaction();
        try{
        if(d.optString("id").isEmpty()){v.put("id",UUID.randomUUID().toString());db.insertOrThrow("students",null,v);}
        else if(db.update("students",v,"id=?",new String[]{d.getString("id")})!=1)throw new IllegalArgumentException("Student no longer exists.");
        staff.record(actor,d.optString("id").isEmpty()?"Added student":"Updated student",v.getAsString("admission"));
        db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private void toast(String msg){Toast.makeText(this,msg,Toast.LENGTH_LONG).show();}
    private void pickAttendance(){
        if(!staff.signedIn())return;
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE,"Prayer_Attendance_"+exportFrom+"_"+exportTo+".csv");
        try{choosingDocument=true;startActivityForResult(i,EXPORT_ATTENDANCE);}catch(android.content.ActivityNotFoundException e){choosingDocument=false;toast("No document picker available.");}
    }
    private void pick(boolean export){
        if(!staff.signedIn())return;
        Intent i=new Intent(export?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(export?"text/csv":"*/*");
        if(export)i.putExtra(Intent.EXTRA_TITLE,"Prayer_Students.csv");
        try{choosingDocument=true;startActivityForResult(i,export?EXPORT:IMPORT);}
        catch(android.content.ActivityNotFoundException e){choosingDocument=false;toast("No document picker available.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(drive!=null && drive.handleActivityResult(request,result,data))return;
        choosingDocument=false;
        if(!staff.signedIn()||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        String actor=staff.identity();
        new Thread(()->{
            String msg;
            try{
                if(request==EXPORT_ATTENDANCE){
                    JSONObject dates=new JSONObject().put("from",exportFrom).put("to",exportTo).put("limit",20000);
                    JSONArray dataRows=attendance.attendance(dates);
                    try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
                        out.write(AttendanceStore.csv(dataRows).getBytes(StandardCharsets.UTF_8));
                    }
                    msg=dataRows.length()+" attendance records exported.";
                }else if(request==EXPORT){
                    try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
                        out.write(Csv.encode(roster()).getBytes(StandardCharsets.UTF_8));
                    }
                    msg="Student register exported.";
                }else{
                    byte[] buffer=new byte[8192];ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                    try(InputStream in=getContentResolver().openInputStream(uri)){int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>2000000)throw new IOException("File exceeds 2 MB.");bytes.write(buffer,0,n);}}
                    java.util.List<JSONObject> rows=Csv.read(bytes.toString("UTF-8"));
                    SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
                    try{for(JSONObject row:rows)save(row,actor);db.setTransactionSuccessful();}finally{db.endTransaction();}
                    msg=rows.size()+" students imported.";
                }
            }catch(Exception e){msg=request==IMPORT?"Import failed. Check the CSV header, dates and duplicate admission numbers. No students imported.":"Export failed. Choose another file.";}
            String notice=msg;runOnUiThread(()->{if(!isDestroyed()){toast(notice);if(staff.signedIn())web.evaluateJavascript("window.refreshRoster && window.refreshRoster()",null);}});
        }).start();
    }
}

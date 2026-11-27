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
import java.util.Arrays;
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
    private CentralSync central;
    private String exportFrom="",exportTo="";
    private boolean choosingDocument;
    private volatile boolean restoreInProgress;
    private char[] pendingRestorePassword;
    private long backgroundAt;
    private final android.os.Handler syncHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable foregroundSync=new Runnable(){
        public void run(){if(central!=null && staff!=null && staff.signedIn() && !restoreInProgress)central.requestSync();syncHandler.postDelayed(this,120000);}
    };
    private static final int EXPORT=100, IMPORT=101, EXPORT_ATTENDANCE=102, RESTORE_BACKUP=103;

    static final class Store extends SQLiteOpenHelper {
        Store(Context c){super(c,"students.sqlite",null,5);}
        public void onCreate(SQLiteDatabase db){
            db.execSQL("CREATE TABLE students(id TEXT PRIMARY KEY, admission TEXT COLLATE NOCASE UNIQUE NOT NULL, name TEXT NOT NULL, group_name TEXT NOT NULL, enrolled TEXT NOT NULL, active INTEGER NOT NULL)");
            StaffAccounts.schema(db);
            db.execSQL("ALTER TABLE staff ADD COLUMN archived INTEGER NOT NULL DEFAULT 0");
            StaffAccounts.invitationSchema(db);
            AttendanceStore.schema(db);
            db.execSQL("ALTER TABLE students ADD COLUMN fingerprint_number INTEGER");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_students_fp_num ON students(fingerprint_number) WHERE fingerprint_number IS NOT NULL");
        }
        public void onUpgrade(SQLiteDatabase db,int from,int to){
            if(from<1||from>4||to!=5)throw new IllegalStateException("Unsupported database version");
            if(from==1)StaffAccounts.schema(db);
            if(from<=2){StaffAccounts.invitationSchema(db);AttendanceStore.schema(db);}
            if(from<=3){db.execSQL("ALTER TABLE students ADD COLUMN fingerprint_number INTEGER");db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_students_fp_num ON students(fingerprint_number) WHERE fingerprint_number IS NOT NULL");}
            db.execSQL("ALTER TABLE staff ADD COLUMN archived INTEGER NOT NULL DEFAULT 0");
        }
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        prefs=getSharedPreferences("private",MODE_PRIVATE);
        store=new Store(this);
        staff=new StaffAccounts(store.getWritableDatabase(),prefs);
        attendance=new AttendanceStore(store.getWritableDatabase(),staff);
        drive=new DriveConnection(this,prefs,(message,success)->{
            if(!isDestroyed() && web!=null)
                web.evaluateJavascript("window.onDriveUpdate && window.onDriveUpdate("+JSONObject.quote(message)+","+success+")",null);
        },passphrase->new BackupArchive(store.getReadableDatabase()).create(getCacheDir(),passphrase));
        central=new CentralSync(this,store,(event,data)->{
            if(!isDestroyed() && web!=null)web.evaluateJavascript(
                "window.onCentralUpdate && window.onCentralUpdate("+JSONObject.quote(event)+","+data.toString()+")",null);
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
        // WebView does not provide the app's JavaScript confirm() dialogs unless
        // the host handles them. The UI uses confirm() before sensitive actions,
        // including encrypted backup upload and restore.
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onJsConfirm(WebView view,String url,String message,JsResult result){
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok,(dialog,which)->result.confirm())
                    .setNegativeButton(android.R.string.cancel,(dialog,which)->result.cancel())
                    .setOnCancelListener(dialog->result.cancel())
                    .show();
                return true;
            }

            @Override public boolean onJsAlert(WebView view,String url,String message,JsResult result){
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok,(dialog,which)->result.confirm())
                    .setOnCancelListener(dialog->result.confirm())
                    .show();
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                String url=r.getUrl().toString();
                String file=url.equals(HOME)?"index.html":url.equals(HOME+"app.js")?"app.js":url.equals(HOME+"style.css")?"style.css":url.equals(HOME+"mosque.png")?"mosque.png":null;
                if(file!=null)try{
                    return new WebResourceResponse(file.endsWith(".html")?"text/html":file.endsWith(".js")?"application/javascript":file.endsWith(".png")?"image/png":"text/css","UTF-8",getAssets().open(file));
                }catch(IOException ignored){}
                return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
            }
        });
        web.addJavascriptInterface(new Bridge(),"Phone");
        web.loadUrl(HOME);
    }

    @Override public void onStop(){syncHandler.removeCallbacks(foregroundSync);super.onStop();backgroundAt=android.os.SystemClock.elapsedRealtime();}
    @Override public void onStart(){super.onStart();syncHandler.removeCallbacks(foregroundSync);syncHandler.postDelayed(foregroundSync,15000);if(staff.signedIn() && !staff.remembered() && !choosingDocument && backgroundAt!=0 && android.os.SystemClock.elapsedRealtime()-backgroundAt>60000){staff.logout();web.reload();}}
    @Override public void onBackPressed(){web.evaluateJavascript("window.back ? window.back() : false",v->{if(!"true".equals(v))super.onBackPressed();});}
    @Override public void onDestroy(){syncHandler.removeCallbacks(foregroundSync);clearPendingRestorePassword();web.removeJavascriptInterface("Phone");web.destroy();store.close();super.onDestroy();}

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
                case "centralStatus":return pack(central.status(),null);
                case "centralLogin":central.login(d.getString("server"),d.getString("username"),d.getString("password"));return pack(true,null);
                case "centralRegister":central.register(d.getString("server"),d.getString("username"),d.getString("password"),d.getString("code"));return pack(true,null);
                case "centralLogout":central.logout();return pack(true,null);
                case "centralInvite":central.invite();return pack(true,null);
                case "centralRefresh":central.refresh();return pack(true,null);
                case "centralUpload":central.upload();return pack(true,null);
                case "centralCreateDevice":central.createDevice(d.getString("name"));return pack(true,null);
                case "centralDeviceList":return pack(central.deviceList(),null);
                case "centralDriveConnect":central.connectInstitutionDrive();return pack(true,null);
                case "centralDriveBackup":central.backupInstitutionDrive();return pack(true,null);
                case "driveStatus":return pack(drive.status(),null);
                case "driveConnect":
                    if(!staff.state().optBoolean("admin",false))throw new IllegalStateException("Administrator access required.");
                    runOnUiThread(()->drive.connectAndTest());return pack(true,null);
                case "driveBackup":
                    if(!staff.state().optBoolean("admin",false))throw new IllegalStateException("Administrator access required.");
                    String password=d.getString("password");
                    if(password.length()<12||password.length()>128)
                        throw new IllegalArgumentException("Backup password must be 12–128 characters.");
                    runOnUiThread(()->drive.backup(password.toCharArray()));
                    return pack(true,null);
                case "driveRestore":
                    if(!staff.state().optBoolean("admin",false))throw new IllegalStateException("Administrator access required.");
                    String recoveryPassword=d.getString("password");
                    if(recoveryPassword.length()<12||recoveryPassword.length()>128)
                        throw new IllegalArgumentException("Backup password must be 12–128 characters.");
                    if(choosingDocument||restoreInProgress)throw new IllegalStateException("A file selection or restore is already in progress.");
                    runOnUiThread(()->{clearPendingRestorePassword();pendingRestorePassword=recoveryPassword.toCharArray();pickBackupRestore();});
                    return pack(true,null);
                case "appSigning":return pack(new JSONObject().put("package",getPackageName()).put("sha1",appSha1()),null);
                case "copySigning":runOnUiThread(()->{android.content.ClipboardManager c=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);c.setPrimaryClip(android.content.ClipData.newPlainText("Smart Prayer SHA-1",appSha1()));toast("APK SHA-1 copied.");});return pack(true,null);
                case "overview":return pack(attendance.overview(),null);
                case "schedules":return pack(attendance.schedules(),null);
                case "scheduleSave":attendance.saveSchedule(d);central.requestSync();return pack(true,null);
                case "events":return pack(attendance.events(),null);
                case "eventSave":attendance.saveEvent(d);central.requestSync();return pack(true,null);
                case "eventCancel":attendance.cancelEvent(d);return pack(true,null);
                case "attendanceMark":attendance.mark(d);central.requestSync();return pack(true,null);
                case "attendanceList":return pack(attendance.attendance(d),null);
                case "attendanceAudit":return pack(attendance.audit(d),null);
                case "inviteGenerate":return pack(staff.generateInvite(),null);
                case "invitations":return pack(staff.invitations(),null);
                case "inviteRevoke":staff.revokeInvite(d.getString("id"));return pack(true,null);
                case "staffList":return pack(staff.members(),null);
                case "staffAccess":staff.setAccess(d.getString("id"),d.optBoolean("active"));return pack(true,null);
                case "attendanceExport":exportFrom=d.optString("from","");exportTo=d.optString("to","");runOnUiThread(()->pickAttendance());return pack(true,null);
                case "save":save(d,staff.identity());central.requestSync();return pack(true,null);
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
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id,admission,name,group_name,enrolled,active,fingerprint_number FROM students ORDER BY name COLLATE NOCASE,admission",null)){
            while(c.moveToNext()){
                JSONObject s=new JSONObject();
                for(int i=0;i<5;i++)s.put(c.getColumnName(i),c.getString(i));
                s.put("active",c.getInt(5)==1);s.put("fingerprint_number",c.isNull(6)?JSONObject.NULL:c.getInt(6));result.put(s);
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
        String fn=d.optString("fingerprint_number","").trim();
        if(fn.isEmpty())v.putNull("fingerprint_number");
        else{try{int num=Integer.parseInt(fn);if(num<1||num>300)throw new NumberFormatException();v.put("fingerprint_number",num);}catch(NumberFormatException e){throw new IllegalArgumentException("Fingerprint number must be between 1 and 300.");}}
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
    private void pickBackupRestore(){
        if(!staff.signedIn()||pendingRestorePassword==null){
            clearPendingRestorePassword();
            notifyBackupRestore("Restore cancelled because the recovery session expired.",false);
            return;
        }
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE,"Choose Smart Prayer encrypted backup (.spab)");
        try{
            choosingDocument=true;
            startActivityForResult(intent,RESTORE_BACKUP);
        }catch(android.content.ActivityNotFoundException e){
            choosingDocument=false;
            clearPendingRestorePassword();
            notifyBackupRestore("No Android document picker is available on this phone.",false);
        }
    }

    private void handleBackupRestoreResult(int result,Intent data){
        choosingDocument=false;
        char[] password=pendingRestorePassword;
        pendingRestorePassword=null;
        if(result!=RESULT_OK||data==null||data.getData()==null){
            if(password!=null)Arrays.fill(password,'\0');
            notifyBackupRestore("Backup restore cancelled. Your current records were not changed.",false);
            return;
        }
        if(password==null){
            notifyBackupRestore("Recovery password was cleared. Enter it again and choose the backup.",false);
            return;
        }
        if(!staff.signedIn()){
            Arrays.fill(password,'\0');
            notifyBackupRestore("Log in again before restoring a backup. Your current records were not changed.",false);
            return;
        }
        if(restoreInProgress){
            Arrays.fill(password,'\0');
            notifyBackupRestore("Another restore is already running.",false);
            return;
        }
        restoreInProgress=true;
        Uri selected=data.getData();
        String actor=staff.identity();
        new Thread(()->{
            File temporary=null;
            String message;
            boolean success=false;
            try{
                temporary=File.createTempFile("smart-prayer-restore-",".spab",getCacheDir());
                long copied=0;
                try(InputStream input=getContentResolver().openInputStream(selected);
                    OutputStream output=new FileOutputStream(temporary)){
                    if(input==null)throw new IOException("Android could not open the selected backup file.");
                    byte[] buffer=new byte[32768];
                    int count;
                    while((count=input.read(buffer))!=-1){
                        copied+=count;
                        if(copied>64L*1024L*1024L)throw new IOException("Backup file is larger than the 64 MB safety limit.");
                        output.write(buffer,0,count);
                    }
                    output.flush();
                }
                BackupArchive.RestoreResult restored=BackupArchive.restore(temporary,store.getWritableDatabase(),password,actor);
                message=restored.summary()+" Fingerprint templates and passwords were not restored.";
                success=true;
            }catch(Exception e){
                message="Restore failed; no partial database changes were kept. "+(e.getMessage()==null?"Check the selected file and recovery password.":e.getMessage());
            }finally{
                Arrays.fill(password,'\0');
                if(temporary!=null)temporary.delete();
                restoreInProgress=false;
            }
            final String notice=message;
            final boolean completed=success;
            runOnUiThread(()->notifyBackupRestore(notice,completed));
        }).start();
    }

    private void notifyBackupRestore(String message,boolean success){
        runOnUiThread(()->{
            if(!isDestroyed()&&web!=null)
                web.evaluateJavascript("window.onBackupRestoreComplete && window.onBackupRestoreComplete("+JSONObject.quote(message)+","+success+")",null);
            else if(!isDestroyed())toast(message);
        });
    }

    private void clearPendingRestorePassword(){
        if(pendingRestorePassword!=null){Arrays.fill(pendingRestorePassword,'\0');pendingRestorePassword=null;}
    }

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
        if(request==RESTORE_BACKUP){handleBackupRestoreResult(result,data);return;}
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

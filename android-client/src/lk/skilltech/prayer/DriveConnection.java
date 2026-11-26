package lk.skilltech.prayer;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.common.api.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Google Drive v3: authorize test uploads and manually requested encrypted backups.
 * No OAuth access token or backup recovery password is persisted.
 * Requires a correctly registered Android OAuth client in Google Cloud.
 */
final class DriveConnection {
    private static final String DRIVE_FILE = "https://www.googleapis.com/auth/drive.file";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,webViewLink";
    private static final int REQUEST_CODE = 8201;
    interface Listener { void update(String message, boolean success); }
    interface BackupWriter { File create(char[] passphrase) throws Exception; }
    private final BackupWriter backupWriter;
    private boolean backupMode;
    private char[] backupPassword;
    private final Activity activity;
    private final SharedPreferences prefs;
    private final Listener listener;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private volatile boolean pending;

    DriveConnection(Activity activity, SharedPreferences prefs, Listener listener, BackupWriter backupWriter){
        this.activity=activity;this.prefs=prefs;this.listener=listener;this.backupWriter=backupWriter;
    }

    JSONObject status() throws Exception {
        return new JSONObject()
            .put("authorizedBefore",prefs.getBoolean("drive_authorized",false))
            .put("busy",pending)
            .put("lastTestAt",prefs.getLong("drive_test_at",0))
            .put("lastFileId",prefs.getString("drive_file_id",""))
            .put("lastBackupAt",prefs.getLong("drive_backup_at",0))
            .put("lastBackupId",prefs.getString("drive_backup_id",""))
            .put("lastBackupName",prefs.getString("drive_backup_name",""))
            .put("lastError",prefs.getString("drive_last_error",""))
            .put("scope","drive.file")
            .put("note","Manual encrypted snapshots and transactional merge restore are supported; automatic backup/sync and fingerprint-template backup are not implemented.");
    }

    void connectAndTest(){
        if(pending){notifyUser("Google Drive authorization is already in progress.",false);return;}
        pending=true;
        backupMode=false;
        authorize();
    }

    void backup(char[] password){
        if(pending){Arrays.fill(password,'\0');notifyUser("Google Drive is busy.",false);return;}
        if(password.length<12||password.length>128){Arrays.fill(password,'\0');notifyUser("Backup password must be 12–128 characters.",false);return;}
        backupPassword=password;
        backupMode=true;
        pending=true;
        authorize();
    }

    private void authorize(){
        notifyUser("Requesting Google Drive permission…",true);
        AuthorizationRequest request=AuthorizationRequest.builder()
            .setRequestedScopes(Collections.singletonList(new Scope(DRIVE_FILE)))
            .build();
        try {
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener(result->{
                if(result.hasResolution()){
                    try{
                        activity.startIntentSenderForResult(result.getPendingIntent().getIntentSender(),REQUEST_CODE,null,0,0,0);
                    }catch(IntentSender.SendIntentException e){fail("Could not open Google consent screen: "+e.getMessage());}
                }else proceed(result);
            })
            .addOnFailureListener(e->fail("Google authorization failed: "+e.getMessage()));
        } catch(RuntimeException e) { fail("Google Play services unavailable: "+e.getMessage()); }
    }

    boolean handleActivityResult(int requestCode,int resultCode,Intent data){
        if(requestCode!=REQUEST_CODE)return false;
        if(resultCode!=Activity.RESULT_OK || data==null){fail("Google Drive permission was cancelled.");return true;}
        try{
            AuthorizationResult result=Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data);
            proceed(result);
        }catch(ApiException e){fail("Google consent failed ("+e.getStatusCode()+"): "+e.getMessage());}
        return true;
    }

    private void proceed(AuthorizationResult result){
        String token=result.getAccessToken();
        if(token==null||token.isEmpty()){fail("Google returned no Drive access token.");return;}
        prefs.edit().putBoolean("drive_authorized",true).apply();
        final boolean isBackup=backupMode;
        notifyUser(isBackup?"Encrypting attendance backup…":"Google Drive authorized. Uploading a harmless test file…",true);
        executor.execute(()->{
            File archive=null;
            try{
                if(isBackup){
                    if(backupWriter==null)throw new IllegalStateException("Backup writer unavailable.");
                    archive=backupWriter.create(backupPassword);
                    notifyUser("Uploading encrypted backup to Google Drive…",true);
                    JSONObject created=uploadBackupFile(token,archive);
                    prefs.edit().putLong("drive_backup_at",System.currentTimeMillis())
                        .putString("drive_backup_id",created.optString("id",""))
                        .putString("drive_backup_name",created.optString("name",""))
                        .remove("drive_last_error").apply();
                    notifyUser("Encrypted backup uploaded: "+created.optString("name"),true);
                }else{
                    JSONObject created=uploadConnectionTest(token);
                    prefs.edit().putLong("drive_test_at",System.currentTimeMillis())
                        .putString("drive_file_id",created.optString("id",""))
                        .remove("drive_last_error").apply();
                    notifyUser("Success! Test file uploaded to Google Drive: "+created.optString("name"),true);
                }
                pending=false;
            }catch(Exception e){fail((isBackup?"Backup upload failed: ":"Google Drive upload failed: ")+e.getMessage());}
            finally{
                if(archive!=null)archive.delete();
                clearBackupPassword();
            }
        });
    }

    private JSONObject uploadBackupFile(String token,File archive)throws Exception{
        // Resumable upload avoids building the complete file in a large RAM byte[].
        String filename="SmartPrayer_Encrypted_Backup_"+Instant.now().toString().replace(':','-')+".spab";
        JSONObject metadata=new JSONObject().put("name",filename).put("mimeType","application/octet-stream")
            .put("description","AES-256-GCM encrypted Smart Prayer Attendance backup; passphrase required to recover.");
        HttpURLConnection start=(HttpURLConnection)new URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name").openConnection();
        String location;
        try{
            byte[] json=metadata.toString().getBytes(StandardCharsets.UTF_8);
            start.setRequestMethod("POST");start.setDoOutput(true);
            start.setConnectTimeout(20000);start.setReadTimeout(30000);
            start.setRequestProperty("Authorization","Bearer "+token);
            start.setRequestProperty("Content-Type","application/json; charset=UTF-8");
            start.setRequestProperty("X-Upload-Content-Type","application/octet-stream");
            start.setRequestProperty("X-Upload-Content-Length",String.valueOf(archive.length()));
            start.setFixedLengthStreamingMode(json.length);
            try(OutputStream out=start.getOutputStream()){out.write(json);}
            int code=start.getResponseCode();
            if(code!=200)throw new IllegalStateException("Drive upload-session HTTP "+code+": "+readResponse(start));
            location=start.getHeaderField("Location");
            if(location==null||!location.startsWith("https://www.googleapis.com/"))
                throw new IllegalStateException("Google did not return a valid upload session.");
        }finally{start.disconnect();}
        HttpURLConnection upload=(HttpURLConnection)new URL(location).openConnection();
        try{
            upload.setRequestMethod("PUT");upload.setDoOutput(true);
            upload.setConnectTimeout(20000);upload.setReadTimeout(90000);
            upload.setRequestProperty("Authorization","Bearer "+token);
            upload.setRequestProperty("Content-Type","application/octet-stream");
            upload.setFixedLengthStreamingMode(archive.length());
            try(InputStream in=new FileInputStream(archive);OutputStream out=upload.getOutputStream()){
                byte[] b=new byte[32768];int n;
                while((n=in.read(b))!=-1)out.write(b,0,n);
            }
            int code=upload.getResponseCode();
            String body=readResponse(upload);
            if(code<200||code>=300)throw new IllegalStateException("Drive backup HTTP "+code+": "+body);
            JSONObject created=new JSONObject(body);
            if(created.optString("id","").isEmpty())throw new IllegalStateException("Google returned no backup file ID.");
            return created;
        }finally{upload.disconnect();}
    }

    private String readResponse(HttpURLConnection conn)throws Exception{
        InputStream in=conn.getResponseCode()<400?conn.getInputStream():conn.getErrorStream();
        if(in==null)return "No response details";
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(InputStream stream=in){byte[] b=new byte[2048];int n;while((n=stream.read(b))!=-1){if(bytes.size()+n>12000)break;bytes.write(b,0,n);}}
        return bytes.toString("UTF-8");
    }

    private JSONObject uploadConnectionTest(String token)throws Exception {
        String filename="SmartPrayer_Connection_Test_"+Instant.now().toString().replace(':','-')+".txt";
        JSONObject metadata=new JSONObject().put("name",filename).put("mimeType","text/plain")
            .put("description","Smart Prayer Attendance Android Google Drive connectivity test — contains no student data.");
        String text="Smart Prayer Attendance Android\nDrive connection test\nCreated: "+Instant.now()+"\n\nNo student or fingerprint data is included.\n";
        String boundary="smartprayer-"+UUID.randomUUID().toString().replace("-","");
        ByteArrayOutputStream body=new ByteArrayOutputStream();
        body.write(("--"+boundary+"\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"+metadata.toString()+"\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("--"+boundary+"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n"+text+"\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] payload=body.toByteArray();
        HttpURLConnection conn=(HttpURLConnection)new URL(UPLOAD).openConnection();
        conn.setRequestMethod("POST");conn.setDoOutput(true);conn.setConnectTimeout(15000);conn.setReadTimeout(25000);
        conn.setRequestProperty("Authorization","Bearer "+token);
        conn.setRequestProperty("Content-Type","multipart/related; boundary="+boundary);
        conn.setRequestProperty("Accept","application/json");
        conn.setFixedLengthStreamingMode(payload.length);
        try{
            try(OutputStream out=conn.getOutputStream()){out.write(payload);}
            int code=conn.getResponseCode();
            InputStream stream=code>=200&&code<300?conn.getInputStream():conn.getErrorStream();
            ByteArrayOutputStream result=new ByteArrayOutputStream();
            if(stream!=null)try(InputStream in=stream){byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1){if(result.size()+n>32000)break;result.write(b,0,n);}}
            String output=result.toString("UTF-8");
            if(code<200||code>=300){
                String msg=output;
                try{msg=new JSONObject(output).getJSONObject("error").optString("message",output);}catch(Exception ignored){}
                throw new IllegalStateException("HTTP "+code+": "+msg);
            }
            JSONObject created=new JSONObject(output);
            if(created.optString("id").isEmpty())throw new IllegalStateException("Upload response lacked a file ID.");
            return created;
        }finally{conn.disconnect();}
    }

    private void fail(String message){
        pending=false;
        clearBackupPassword();
        prefs.edit().putString("drive_last_error",message).apply();
        notifyUser(message,false);
    }
    private void clearBackupPassword(){
        if(backupPassword!=null){Arrays.fill(backupPassword,'\0');backupPassword=null;}
    }
    private void notifyUser(String message,boolean success){activity.runOnUiThread(()->listener.update(message,success));}
}

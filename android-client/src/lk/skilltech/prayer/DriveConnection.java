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

/** Google Drive v3: request per-file consent; upload NON-STUDENT test document only.
 *  No Google token or password is persisted. Requires OAuth Android client in Google Cloud.
 */
final class DriveConnection {
    private static final String DRIVE_FILE = "https://www.googleapis.com/auth/drive.file";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,webViewLink";
    private static final int REQUEST_CODE = 8201;
    interface Listener { void update(String message, boolean success); }
    private final Activity activity;
    private final SharedPreferences prefs;
    private final Listener listener;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private volatile boolean pending;

    DriveConnection(Activity activity, SharedPreferences prefs, Listener listener){
        this.activity=activity;this.prefs=prefs;this.listener=listener;
    }

    JSONObject status() throws Exception {
        return new JSONObject()
            .put("authorizedBefore",prefs.getBoolean("drive_authorized",false))
            .put("busy",pending)
            .put("lastTestAt",prefs.getLong("drive_test_at",0))
            .put("lastFileId",prefs.getString("drive_file_id",""))
            .put("lastError",prefs.getString("drive_last_error",""))
            .put("scope","drive.file")
            .put("note","Connection test only. Attendance records and fingerprint templates are not uploaded by this version.");
    }

    void connectAndTest(){
        if(pending){notifyUser("Google Drive authorization is already in progress.",false);return;}
        pending=true;
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
        notifyUser("Google Drive authorized. Uploading a harmless test file…",true);
        executor.execute(()->{
            try{
                JSONObject created=uploadConnectionTest(token);
                prefs.edit().putLong("drive_test_at",System.currentTimeMillis())
                    .putString("drive_file_id",created.optString("id",""))
                    .remove("drive_last_error").apply();
                pending=false;
                notifyUser("Success! Test file uploaded to Google Drive: "+created.optString("name"),true);
            }catch(Exception e){fail("Google Drive upload failed: "+e.getMessage());}
        });
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
        prefs.edit().putString("drive_last_error",message).apply();
        notifyUser(message,false);
    }
    private void notifyUser(String message,boolean success){activity.runOnUiThread(()->listener.update(message,success));}
}

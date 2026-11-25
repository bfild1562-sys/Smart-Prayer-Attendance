package lk.skilltech.prayer;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;
import org.json.*;

final class StaffAccounts {
    private final SQLiteDatabase db;
    private final SharedPreferences prefs;
    private volatile String current="";
    static void schema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE staff(id TEXT PRIMARY KEY, username TEXT NOT NULL, normalized TEXT UNIQUE NOT NULL, salt TEXT NOT NULL, digest TEXT NOT NULL, admin INTEGER NOT NULL, approved INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE staff_activity(id INTEGER PRIMARY KEY, staff_id TEXT NOT NULL REFERENCES staff(id), action TEXT NOT NULL, subject TEXT NOT NULL, at INTEGER NOT NULL)");
    }
    static void invitationSchema(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS staff_invitations(id TEXT PRIMARY KEY, code_hash TEXT NOT NULL UNIQUE, role TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, used_by TEXT, revoked INTEGER NOT NULL DEFAULT 0)");
    }
    private static String tokenHash(String code) throws Exception{
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(code.trim().getBytes(StandardCharsets.UTF_8));
        return enc(hash);
    }
    StaffAccounts(SQLiteDatabase db,SharedPreferences prefs){
        this.db=db;this.prefs=prefs;
        String saved=prefs.getString("rememberStaff","");
        try(Cursor c=db.rawQuery("SELECT id FROM staff WHERE id=? AND approved=1",new String[]{saved})){
            if(c.moveToFirst())current=c.getString(0);
        }
    }
    boolean signedIn(){return !current.isEmpty();}
    String identity(){String id=current;if(id.isEmpty())throw new IllegalStateException("Log in first.");return id;}
    boolean remembered(){return signedIn()&&current.equals(prefs.getString("rememberStaff",""));}
    boolean first(){try(Cursor c=db.rawQuery("SELECT 1 FROM staff LIMIT 1",null)){return !c.moveToFirst();}}
    boolean legacy(){return first()&&prefs.contains("hash");}
    void logout(){prefs.edit().remove("rememberStaff").commit();current="";}
    JSONObject state()throws JSONException{
        JSONObject obj=new JSONObject().put("unlocked",signedIn()).put("first",first()).put("legacy",legacy()).put("server",prefs.getString("server",""));
        if(signedIn())try(Cursor c=db.rawQuery("SELECT username,admin FROM staff WHERE id=?",new String[]{identity()})){
            if(c.moveToFirst())obj.put("username",c.getString(0)).put("admin",c.getInt(1)==1);
        }
        return obj;
    }
    private void checkRetry(){if(System.currentTimeMillis()<prefs.getLong("retry",0))throw new IllegalArgumentException("Try again in one minute.");}
    private void incorrect(){
        int fails=prefs.getInt("fails",0)+1;
        prefs.edit().putInt("fails",fails%5).putLong("retry",fails%5==0?System.currentTimeMillis()+60000:0).commit();
        throw new IllegalArgumentException("Check your username and password.");
    }
    private static String enc(byte[] bytes){return Base64.encodeToString(bytes,Base64.NO_WRAP);}
    private static boolean matches(String pass,String salt,String digest)throws Exception{
        return MessageDigest.isEqual(MainActivity.hash(pass,Base64.decode(salt,0)),Base64.decode(digest,0));
    }
    String signup(JSONObject d)throws Exception{
        String name=d.getString("username").trim();
        if(name.length()<1||name.length()>60||name.matches(".*[\\p{Cntrl}].*"))throw new IllegalArgumentException("Enter a username of 1–60 characters.");
        String pass=d.getString("password");
        if(!pass.equals(d.getString("confirm")))throw new IllegalArgumentException("Passwords do not match.");
        if(pass.length()<8||pass.length()>128)throw new IllegalArgumentException("Use a password of 8–128 characters.");
        String key=UUID.randomUUID().toString();boolean bootstrap;
        db.beginTransaction();
        try{
            bootstrap=first();
            if(!bootstrap){
                String invite=d.optString("invite","").trim();
                if(invite.isEmpty())throw new IllegalArgumentException("Ask your administrator for an invitation code.");
                String codeHash=tokenHash(invite);long now=System.currentTimeMillis();
                String invitationId="";
                try(Cursor c=db.rawQuery("SELECT id FROM staff_invitations WHERE code_hash=? AND used_by IS NULL AND revoked=0 AND expires_at>?",new String[]{codeHash,String.valueOf(now)})){
                    if(c.moveToFirst())invitationId=c.getString(0);
                }
                if(invitationId.isEmpty())throw new IllegalArgumentException("Invalid, expired or already used invitation code.");
                d.put("invitationId",invitationId);
            }
            if(legacy()){
                checkRetry();
                if(!matches(pass,prefs.getString("salt",""),prefs.getString("hash","")))incorrect();
            }
            byte[] salt=new byte[16];new SecureRandom().nextBytes(salt);
            try{
                db.execSQL("INSERT INTO staff VALUES(?,?,?,?,?,?,?)",new Object[]{key,name,name.toLowerCase(Locale.ROOT),enc(salt),enc(MainActivity.hash(pass,salt)),bootstrap?1:0,1});
                if(!bootstrap)db.execSQL("UPDATE staff_invitations SET used_by=? WHERE id=? AND used_by IS NULL",new Object[]{key,d.getString("invitationId")});
            }catch(android.database.sqlite.SQLiteConstraintException e){throw new IllegalArgumentException("That username is already registered.");}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        if(bootstrap){prefs.edit().remove("hash").remove("salt").remove("retry").remove("fails").commit();return "Staff account created. Log in to continue.";}
        return "Staff account created. Log in to continue.";
    }
    void login(JSONObject d)throws Exception{
        checkRetry();
        String pass=d.getString("password");
        if(pass.length()>128)incorrect();
        try(Cursor c=db.rawQuery("SELECT id,salt,digest,approved FROM staff WHERE normalized=?",new String[]{d.getString("username").trim().toLowerCase(Locale.ROOT)})){
            if(!c.moveToFirst()||!matches(pass,c.getString(1),c.getString(2)))incorrect();
            if(c.getInt(3)==0)throw new IllegalArgumentException("Your staff account is waiting for approval.");
            String id=c.getString(0);
            prefs.edit().remove("retry").remove("fails").putString("rememberStaff",d.optBoolean("remember")?id:"").commit();
            current=id;
        }
    }
    private void requireAdmin()throws JSONException{if(!state().optBoolean("admin"))throw new IllegalStateException("Administrator access required.");}
    JSONArray pending()throws JSONException{
        requireAdmin();JSONArray out=new JSONArray();
        try(Cursor c=db.rawQuery("SELECT id,username FROM staff WHERE approved=0 ORDER BY username",null)){while(c.moveToNext())out.put(new JSONObject().put("id",c.getString(0)).put("username",c.getString(1)));}
        return out;
    }
    void approve(String key)throws JSONException{
        requireAdmin();
        db.execSQL("UPDATE staff SET approved=1 WHERE id=?",new Object[]{key});
    }
    String generateInvite()throws Exception{
        requireAdmin();byte[] bytes=new byte[18];new SecureRandom().nextBytes(bytes);
        String code=Base64.encodeToString(bytes,Base64.URL_SAFE|Base64.NO_PADDING|Base64.NO_WRAP);
        String hash=tokenHash(code);
        db.execSQL("INSERT INTO staff_invitations(id,code_hash,role,created_at,expires_at,used_by,revoked) VALUES(?,?,?,?,?,NULL,0)",new Object[]{UUID.randomUUID().toString(),hash,"staff",System.currentTimeMillis(),System.currentTimeMillis()+7L*24*60*60*1000});
        record(identity(),"Created staff invitation","Expires in 7 days");
        return code;
    }
    JSONArray invitations()throws Exception{
        requireAdmin();JSONArray out=new JSONArray();
        try(Cursor c=db.rawQuery("SELECT id,created_at,expires_at,used_by,revoked FROM staff_invitations ORDER BY created_at DESC LIMIT 50",null)){
            while(c.moveToNext())out.put(new JSONObject().put("id",c.getString(0)).put("created",c.getLong(1)).put("expires",c.getLong(2)).put("used",!c.isNull(3)).put("revoked",c.getInt(4)==1));
        }return out;
    }
    void revokeInvite(String id)throws Exception{requireAdmin();db.execSQL("UPDATE staff_invitations SET revoked=1 WHERE id=? AND used_by IS NULL",new Object[]{id});record(identity(),"Revoked staff invitation",id);}
    JSONArray members()throws Exception{
        requireAdmin();JSONArray out=new JSONArray();
        try(Cursor c=db.rawQuery("SELECT id,username,admin,approved FROM staff ORDER BY username COLLATE NOCASE",null)){
            while(c.moveToNext())out.put(new JSONObject().put("id",c.getString(0)).put("username",c.getString(1)).put("admin",c.getInt(2)==1).put("approved",c.getInt(3)==1));
        }return out;
    }
    void setAccess(String id,boolean active)throws Exception{
        requireAdmin();if(id.equals(identity()))throw new IllegalArgumentException("Cannot deactivate your own account.");
        db.execSQL("UPDATE staff SET approved=? WHERE id=?",new Object[]{active?1:0,id});record(identity(),active?"Enabled staff":"Disabled staff",id);
    }
    void record(String actor,String action,String subject){
        db.execSQL("INSERT INTO staff_activity(staff_id,action,subject,at) VALUES(?,?,?,?)",new Object[]{actor,action,subject,System.currentTimeMillis()});
    }
    JSONArray activity()throws JSONException{
        requireAdmin();JSONArray out=new JSONArray();
        try(Cursor c=db.rawQuery("SELECT s.username,a.action,a.subject,a.at FROM staff_activity a JOIN staff s ON s.id=a.staff_id ORDER BY a.id DESC LIMIT 100",null)){
            while(c.moveToNext())out.put(new JSONObject().put("username",c.getString(0)).put("action",c.getString(1)).put("subject",c.getString(2)).put("at",c.getLong(3)));
        }
        return out;
    }
}

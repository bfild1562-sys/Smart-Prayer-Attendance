package lk.skilltech.prayer;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Base64;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypted application-only backup (SPABK001):
 * 8-byte magic | 16-byte PBKDF2 salt | 12-byte GCM nonce | gzip JSON encrypted by AES-256-GCM.
 *
 * Restore is a transactional MERGE: existing records are never overwritten or deleted.
 * Staff identities from a backup are restored as archived, non-login placeholders so
 * old audit entries retain their names. Passwords, invitations and fingerprint templates
 * are never exported or restored.
 */
final class BackupArchive {
    private static final String[] TABLES={
        "students", "prayer_schedule", "special_events", "attendance",
        "attendance_audit", "schedule_audit", "staff_activity", "staff_directory"
    };
    private static final byte[] MAGIC="SPABK001".getBytes(StandardCharsets.US_ASCII);
    private static final int ITERATIONS=210000;
    private final SQLiteDatabase db;

    interface RowHandler {
        void row(String table,Map<String,Object> values)throws Exception;
    }

    static final class RestoreResult {
        int studentsAdded,studentsMatched,schedulesRestored,schedulesPreserved;
        int eventsAdded,attendanceAdded,attendanceSkipped,auditAdded,scheduleAuditAdded,staffActivityAdded;
        int archivedStaffAdded;
        String summary(){
            return "Restore complete (merge only). Added "+studentsAdded+" students, "+attendanceAdded+
                " attendance records, "+eventsAdded+" events, "+auditAdded+" attendance audit entries, "+
                scheduleAuditAdded+" schedule history entries and "+staffActivityAdded+
                " staff activity entries. Restored "+schedulesRestored+" default schedules; preserved "+
                schedulesPreserved+" existing schedules. Matched "+studentsMatched+
                " students already on this phone; skipped "+attendanceSkipped+
                " attendance rows without a matching student. Preserved "+archivedStaffAdded+
                " historical staff identities as non-login records.";
        }
    }

    private static final class ArchiveMetadata {
        final Map<String,String> staffNames=new LinkedHashMap<>();
        final Set<String> tables=new HashSet<>();
    }

    BackupArchive(SQLiteDatabase db){this.db=db;}

    File create(File cacheDir,char[] passphrase)throws Exception{
        checkPassword(passphrase);
        byte[] salt=new byte[16],nonce=new byte[12],key=null;
        SecureRandom random=new SecureRandom();
        random.nextBytes(salt);random.nextBytes(nonce);
        PBEKeySpec spec=new PBEKeySpec(passphrase,salt,ITERATIONS,256);
        try{key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
        finally{spec.clearPassword();}
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        try{cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));}
        finally{Arrays.fill(key,(byte)0);}

        File archive=File.createTempFile("smart-prayer-backup-",".spab",cacheDir);
        boolean completed=false;
        try{
            try(OutputStream file=new FileOutputStream(archive)){
                file.write(MAGIC);file.write(salt);file.write(nonce);
                try(CipherOutputStream encrypted=new CipherOutputStream(file,cipher);
                    GZIPOutputStream compressed=new GZIPOutputStream(encrypted);
                    JsonWriter json=new JsonWriter(new OutputStreamWriter(compressed,StandardCharsets.UTF_8))){
                    db.beginTransactionNonExclusive();
                    try{
                        json.beginObject();
                        json.name("format").value("smart-prayer-attendance-v1");
                        json.name("created_at_utc").value(Instant.now().toString());
                        json.name("timezone").value("Asia/Colombo");
                        json.name("notes").value("Student, attendance, schedules, events and audit data; excludes passwords, invitations and fingerprint templates.");
                        json.name("tables").beginObject();
                        for(String table:TABLES){
                            json.name(table).beginArray();
                            String sql=table.equals("staff_directory")
                                ?"SELECT id,username,admin,approved,archived FROM staff"
                                :"SELECT * FROM "+table;
                            try(Cursor rows=db.rawQuery(sql,null)){
                                while(rows.moveToNext()){
                                    json.beginObject();
                                    for(int j=0;j<rows.getColumnCount();j++){
                                        json.name(rows.getColumnName(j));
                                        switch(rows.getType(j)){
                                            case Cursor.FIELD_TYPE_NULL:json.nullValue();break;
                                            case Cursor.FIELD_TYPE_INTEGER:json.value(rows.getLong(j));break;
                                            case Cursor.FIELD_TYPE_FLOAT:json.value(rows.getDouble(j));break;
                                            case Cursor.FIELD_TYPE_STRING:json.value(rows.getString(j));break;
                                            default:throw new IllegalStateException("Unsupported data in "+table);
                                        }
                                    }
                                    json.endObject();
                                }
                            }
                            json.endArray();
                        }
                        json.endObject();json.endObject();json.flush();
                        db.setTransactionSuccessful();
                    }finally{db.endTransaction();}
                }
            }
            if(archive.length()<60)throw new IllegalStateException("Backup archive unexpectedly empty.");
            completed=true;
            return archive;
        }finally{
            if(!completed)archive.delete();
        }
    }

    static RestoreResult restore(File archive,SQLiteDatabase db,char[] passphrase,String restoringStaffId)throws Exception{
        checkPassword(passphrase);
        if(archive==null||!archive.isFile()||archive.length()<60)
            throw new IllegalArgumentException("Choose a valid Smart Prayer .spab backup file.");

        // First pass fully parses and authenticates the archive before the database is changed.
        ArchiveMetadata metadata=new ArchiveMetadata();
        ArchiveMetadata parsed=readArchive(archive,passphrase,(table,row)->{
            if("staff_directory".equals(table)){
                String id=text(row.get("id"));
                String username=text(row.get("username"));
                if(!id.isEmpty()&&!username.isEmpty())metadata.staffNames.put(id,username);
            }
            if("staff_activity".equals(table))addFallbackStaff(metadata,text(row.get("staff_id")));
            if("attendance".equals(table))addFallbackStaff(metadata,text(row.get("changed_by")));
            if("attendance_audit".equals(table))addFallbackStaff(metadata,text(row.get("actor_id")));
        });
        metadata.tables.addAll(parsed.tables);
        return restoreAuthenticated(archive,db,passphrase,restoringStaffId,metadata);
    }

    private static RestoreResult restoreAuthenticated(File archive,SQLiteDatabase db,char[] passphrase,String restoringStaffId,ArchiveMetadata metadata)throws Exception{
        RestoreResult result=new RestoreResult();
        Map<String,String> studentIds=new HashMap<>();
        db.beginTransactionNonExclusive();
        try{
            result.archivedStaffAdded=seedArchivedStaff(db,metadata.staffNames);
            readArchive(archive,passphrase,(table,row)->{
                switch(table){
                    case "students":restoreStudent(db,row,studentIds,result);break;
                    case "prayer_schedule":restoreSchedule(db,row,result);break;
                    case "special_events":restoreEvent(db,row,result);break;
                    case "attendance":restoreAttendance(db,row,studentIds,result);break;
                    case "attendance_audit":restoreAttendanceAudit(db,row,studentIds,result);break;
                    case "schedule_audit":restoreScheduleAudit(db,row,result);break;
                    case "staff_activity":restoreStaffActivity(db,row,result);break;
                    case "staff_directory":break;
                    default:throw new IllegalArgumentException("Unsupported backup table: "+table);
                }
            });
            db.execSQL("INSERT INTO staff_activity(staff_id,action,subject,at) VALUES(?,?,?,?)",
                new Object[]{restoringStaffId,"Restored encrypted backup",result.summary(),System.currentTimeMillis()});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        return result;
    }

    private static int seedArchivedStaff(SQLiteDatabase db,Map<String,String> staffNames)throws Exception{
        int added=0;
        SecureRandom random=new SecureRandom();
        for(Map.Entry<String,String> entry:staffNames.entrySet()){
            String id=entry.getKey();
            if(id==null||id.isEmpty()||exists(db,"staff","id",id))continue;
            byte[] salt=new byte[16],secretBytes=new byte[32],digest=null;
            random.nextBytes(salt);random.nextBytes(secretBytes);
            String impossiblePassword=Base64.encodeToString(secretBytes,Base64.NO_WRAP);
            Arrays.fill(secretBytes,(byte)0);
            try{digest=MainActivity.hash(impossiblePassword,salt);}
            finally{impossiblePassword=null;}
            ContentValues values=new ContentValues();
            values.put("id",id);
            values.put("username",entry.getValue()==null||entry.getValue().isEmpty()?"Archived staff":entry.getValue());
            values.put("normalized","__archived__"+id.toLowerCase(java.util.Locale.ROOT));
            values.put("salt",Base64.encodeToString(salt,Base64.NO_WRAP));
            values.put("digest",Base64.encodeToString(digest,Base64.NO_WRAP));
            values.put("admin",0);values.put("approved",1);values.put("archived",1);
            long inserted=db.insertWithOnConflict("staff",null,values,SQLiteDatabase.CONFLICT_IGNORE);
            if(inserted!=-1)added++;
            Arrays.fill(salt,(byte)0);Arrays.fill(digest,(byte)0);
        }
        return added;
    }

    private static void restoreStudent(SQLiteDatabase db,Map<String,Object> row,Map<String,String> idMap,RestoreResult result){
        String oldId=text(row.get("id")),admission=text(row.get("admission"));
        if(oldId.isEmpty()||admission.isEmpty())throw new IllegalArgumentException("Backup contains a student without an ID or admission number.");
        String target=findStudentId(db,oldId,admission);
        if(target!=null){
            idMap.put(oldId,target);result.studentsMatched++;return;
        }
        ContentValues values=values(row,"id","admission","name","group_name","enrolled","active");
        // Fingerprint slot numbers are deliberately not restored: the sensor templates are not
        // part of this archive, and reusing a slot could link a student to another person's finger.
        values.putNull("fingerprint_number");
        long inserted=db.insertWithOnConflict("students",null,values,SQLiteDatabase.CONFLICT_IGNORE);
        if(inserted!=-1){
            idMap.put(oldId,oldId);result.studentsAdded++;
        }else{
            target=findStudentId(db,oldId,admission);
            if(target==null)throw new IllegalArgumentException("Could not merge student "+admission+".");
            idMap.put(oldId,target);result.studentsMatched++;
        }
    }

    private static String findStudentId(SQLiteDatabase db,String id,String admission){
        try(Cursor c=db.rawQuery("SELECT id FROM students WHERE id=?",new String[]{id})){if(c.moveToFirst())return c.getString(0);}
        try(Cursor c=db.rawQuery("SELECT id FROM students WHERE admission=? COLLATE NOCASE",new String[]{admission})){if(c.moveToFirst())return c.getString(0);}
        return null;
    }

    private static void restoreSchedule(SQLiteDatabase db,Map<String,Object> row,RestoreResult result){
        String prayer=text(row.get("prayer"));
        if(prayer.isEmpty())throw new IllegalArgumentException("Backup contains a prayer schedule without a prayer name.");
        String opens=text(row.get("opens")),deadline=text(row.get("deadline")),ends=text(row.get("ends"));
        int late=integer(row.get("late_minutes"),0);
        long updated=longValue(row.get("updated_at"),0L);
        try(Cursor c=db.rawQuery("SELECT opens,deadline,ends,updated_at FROM prayer_schedule WHERE prayer=?",new String[]{prayer})){
            if(c.moveToFirst()){
                boolean blank=c.getString(0).isEmpty()&&c.getString(1).isEmpty()&&c.getString(2).isEmpty()&&c.getLong(3)==0;
                if(!blank){result.schedulesPreserved++;return;}
                db.execSQL("UPDATE prayer_schedule SET opens=?,deadline=?,ends=?,late_minutes=?,updated_at=? WHERE prayer=?",
                    new Object[]{opens,deadline,ends,late,updated,prayer});
                result.schedulesRestored++;return;
            }
        }
        ContentValues values=values(row,"prayer","opens","deadline","ends","late_minutes","updated_at");
        if(db.insertWithOnConflict("prayer_schedule",null,values,SQLiteDatabase.CONFLICT_IGNORE)!=-1)
            result.schedulesRestored++;
        else result.schedulesPreserved++;
    }

    private static void restoreEvent(SQLiteDatabase db,Map<String,Object> row,RestoreResult result){
        ContentValues values=values(row,"id","title","event_date","opens","deadline","ends","late_minutes","group_name","active","created_by","updated_at");
        if(db.insertWithOnConflict("special_events",null,values,SQLiteDatabase.CONFLICT_IGNORE)!=-1)result.eventsAdded++;
    }

    private static void restoreAttendance(SQLiteDatabase db,Map<String,Object> row,Map<String,String> studentIds,RestoreResult result){
        String sourceStudent=text(row.get("student_id"));
        String targetStudent=studentIds.get(sourceStudent);
        if(targetStudent==null)targetStudent=existingStudentId(db,sourceStudent);
        if(targetStudent==null){result.attendanceSkipped++;return;}
        ContentValues values=values(row,"id","student_id","session_key","session_date","status","source","changed_by","recorded_at","note");
        values.put("student_id",targetStudent);
        if(db.insertWithOnConflict("attendance",null,values,SQLiteDatabase.CONFLICT_IGNORE)!=-1)result.attendanceAdded++;
    }

    private static void restoreAttendanceAudit(SQLiteDatabase db,Map<String,Object> row,Map<String,String> studentIds,RestoreResult result){
        ContentValues values=values(row,"student_id","session_key","session_date","old_status","new_status","reason","actor_id","recorded_at");
        String sourceStudent=text(row.get("student_id"));
        String targetStudent=studentIds.get(sourceStudent);
        values.put("student_id",targetStudent==null?sourceStudent:targetStudent);
        if(db.insertWithOnConflict("attendance_audit",null,values,SQLiteDatabase.CONFLICT_IGNORE)!=-1)result.auditAdded++;
    }

    private static void restoreScheduleAudit(SQLiteDatabase db,Map<String,Object> row,RestoreResult result){
        ContentValues values=values(row,"subject","previous_json","next_json","actor_id","recorded_at");
        if(db.insertWithOnConflict("schedule_audit",null,values,SQLiteDatabase.CONFLICT_IGNORE)!=-1)result.scheduleAuditAdded++;
    }

    private static void restoreStaffActivity(SQLiteDatabase db,Map<String,Object> row,RestoreResult result){
        String actor=text(row.get("staff_id"));
        if(actor.isEmpty()||!exists(db,"staff","id",actor))return;
        ContentValues values=values(row,"staff_id","action","subject","at");
        if(db.insertWithOnConflict("staff_activity",null,values,SQLiteDatabase.CONFLICT_IGNORE)!=-1)result.staffActivityAdded++;
    }

    private static String existingStudentId(SQLiteDatabase db,String id){
        try(Cursor c=db.rawQuery("SELECT id FROM students WHERE id=?",new String[]{id})){return c.moveToFirst()?c.getString(0):null;}
    }

    private static boolean exists(SQLiteDatabase db,String table,String column,String value){
        try(Cursor c=db.rawQuery("SELECT 1 FROM "+table+" WHERE "+column+"=? LIMIT 1",new String[]{value})){return c.moveToFirst();}
    }

    private static ContentValues values(Map<String,Object> row,String...columns){
        ContentValues values=new ContentValues();
        for(String column:columns){
            if(!row.containsKey(column))continue;
            Object value=row.get(column);
            if(value==null)values.putNull(column);
            else if(value instanceof String)values.put(column,(String)value);
            else if(value instanceof Long)values.put(column,(Long)value);
            else if(value instanceof Integer)values.put(column,(Integer)value);
            else if(value instanceof Double)values.put(column,(Double)value);
            else if(value instanceof Boolean)values.put(column,((Boolean)value)?1:0);
            else throw new IllegalArgumentException("Unsupported value in backup field "+column+".");
        }
        return values;
    }

    private static String text(Object value){return value==null?"":String.valueOf(value);}
    private static long longValue(Object value,long fallback){
        if(value instanceof Number)return ((Number)value).longValue();
        if(value==null)return fallback;
        try{return Long.parseLong(String.valueOf(value));}catch(Exception ignored){return fallback;}
    }
    private static int integer(Object value,int fallback){long n=longValue(value,fallback);return n<Integer.MIN_VALUE||n>Integer.MAX_VALUE?fallback:(int)n;}

    private static ArchiveMetadata readArchive(File archive,char[] passphrase,RowHandler handler)throws Exception{
        ArchiveMetadata metadata=new ArchiveMetadata();
        try(JsonReader json=new JsonReader(new InputStreamReader(openDecrypted(archive,passphrase),StandardCharsets.UTF_8))){
            json.beginObject();
            String format=null;
            boolean tablesFound=false;
            while(json.hasNext()){
                String name=json.nextName();
                if("format".equals(name))format=json.nextString();
                else if("tables".equals(name)){
                    if(tablesFound)throw new IllegalArgumentException("Backup contains duplicate table sections.");
                    tablesFound=true;json.beginObject();
                    while(json.hasNext()){
                        String table=json.nextName();
                        if(!metadata.tables.add(table))throw new IllegalArgumentException("Backup contains duplicate "+table+" sections.");
                        json.beginArray();
                        while(json.hasNext()){
                            json.beginObject();
                            Map<String,Object> row=new LinkedHashMap<>();
                            while(json.hasNext()){
                                String key=json.nextName();
                                JsonToken token=json.peek();
                                switch(token){
                                    case NULL:json.nextNull();row.put(key,null);break;
                                    case STRING:row.put(key,json.nextString());break;
                                    case NUMBER:{
                                        String number=json.nextString();
                                        try{row.put(key,Long.valueOf(number));}
                                        catch(NumberFormatException e){row.put(key,Double.valueOf(number));}
                                        break;
                                    }
                                    case BOOLEAN:row.put(key,json.nextBoolean());break;
                                    default:throw new IllegalArgumentException("Backup contains a nested value in "+table+"."+key+".");
                                }
                            }
                            json.endObject();
                            if(handler!=null)handler.row(table,row);
                        }
                        json.endArray();
                    }
                    json.endObject();
                }else json.skipValue();
            }
            json.endObject();
            if(json.peek()!=JsonToken.END_DOCUMENT)throw new IllegalArgumentException("Backup contains data after its JSON document.");
            if(!"smart-prayer-attendance-v1".equals(format))throw new IllegalArgumentException("Unsupported Smart Prayer backup format.");
            if(!tablesFound)throw new IllegalArgumentException("Backup does not contain a database section.");
        }
        String[] required={"students","prayer_schedule","special_events","attendance","attendance_audit","schedule_audit","staff_activity"};
        for(String table:required)if(!metadata.tables.contains(table))
            throw new IllegalArgumentException("Backup is missing its "+table+" data section.");
        return metadata;
    }

    private static InputStream openDecrypted(File archive,char[] passphrase)throws Exception{
        long length=archive.length();
        // A bounded authenticated decrypt keeps memory use predictable on phones.
        // Most 4-month attendance snapshots are far smaller after gzip compression.
        if(length>64L*1024L*1024L)throw new IllegalArgumentException("This encrypted backup is larger than the 64 MB restore limit.");
        DataInputStream input=new DataInputStream(new BufferedInputStream(new FileInputStream(archive)));
        byte[] encrypted=null,compressed=null,key=null;
        try{
            byte[] magic=new byte[8],salt=new byte[16],nonce=new byte[12];
            input.readFully(magic);input.readFully(salt);input.readFully(nonce);
            if(!Arrays.equals(magic,MAGIC))throw new IllegalArgumentException("This is not a Smart Prayer .spab backup.");
            int encryptedLength=(int)(length-36L);
            if(encryptedLength<16)throw new IllegalArgumentException("Encrypted backup is incomplete.");
            encrypted=new byte[encryptedLength];
            input.readFully(encrypted);
            if(input.read()!=-1)throw new IllegalArgumentException("Backup contains unexpected trailing bytes.");
            PBEKeySpec spec=new PBEKeySpec(passphrase,salt,ITERATIONS,256);
            try{key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
            finally{spec.clearPassword();}
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            // doFinal verifies the GCM authentication tag before JSON is parsed or imported.
            compressed=cipher.doFinal(encrypted);
            return new GZIPInputStream(new WipingByteArrayInputStream(compressed),32768);
        }catch(javax.crypto.AEADBadTagException e){
            if(compressed!=null)Arrays.fill(compressed,(byte)0);
            throw new IllegalArgumentException("Wrong recovery password or damaged backup.",e);
        }catch(Exception e){
            if(compressed!=null)Arrays.fill(compressed,(byte)0);
            throw e;
        }finally{
            try{input.close();}catch(Exception ignored){}
            if(encrypted!=null)Arrays.fill(encrypted,(byte)0);
            if(key!=null)Arrays.fill(key,(byte)0);
        }
    }

    private static final class WipingByteArrayInputStream extends ByteArrayInputStream {
        WipingByteArrayInputStream(byte[] bytes){super(bytes);}
        @Override public void close(){
            Arrays.fill(buf,(byte)0);
            super.reset();
        }
    }

    private static void checkPassword(char[] passphrase){
        if(passphrase==null||passphrase.length<12||passphrase.length>128)
            throw new IllegalArgumentException("Backup password must be 12–128 characters.");
    }
}

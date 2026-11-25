package lk.skilltech.prayer;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.JsonWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypted application-only backup (SPABK001 format):
 *  8-byte ASCII magic | 16-byte PBKDF2 salt | 12-byte GCM nonce |
 *  AES-256-GCM ciphertext (gzip-compressed UTF-8 JSON, including final GCM tag).
 * Password and plaintext are never written to disk or preferences.
 * This is a MANUAL snapshot; it is not automatic synchronization or fingerprint backup.
 */
final class BackupArchive {
    private static final String[] TABLES={
        "students", "prayer_schedule", "special_events", "attendance",
        "attendance_audit", "schedule_audit", "staff_activity", "staff_directory"
    };
    private static final byte[] MAGIC="SPABK001".getBytes(StandardCharsets.US_ASCII);
    private static final int ITERATIONS=210000;
    private final SQLiteDatabase db;

    BackupArchive(SQLiteDatabase db){this.db=db;}

    File create(File cacheDir,char[] passphrase)throws Exception{
        if(passphrase==null||passphrase.length<12||passphrase.length>128)
            throw new IllegalArgumentException("Backup password must be 12–128 characters.");
        byte[] salt=new byte[16],nonce=new byte[12],key=null;
        new SecureRandom().nextBytes(salt);new SecureRandom().nextBytes(nonce);
        PBEKeySpec spec=new PBEKeySpec(passphrase,salt,ITERATIONS,256);
        try{key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
        finally{spec.clearPassword();}
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        try{cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));}
        finally{Arrays.fill(key,(byte)0);}

        File archive=File.createTempFile("smart-prayer-backup-",".spab",cacheDir);
        boolean completed=false;
        try{
            // Header must remain readable for cross-device recovery.
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
                        json.name("notes").value("Student, attendance, schedules, events and audit data; excludes staff passwords and fingerprint templates.");
                        json.name("tables").beginObject();
                        for(String table:TABLES){
                            json.name(table).beginArray();
                            String sql=table.equals("staff_directory")
                                ?"SELECT id,username,admin,approved FROM staff"
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
}

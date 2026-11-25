package lk.skilltech.prayer;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Offline-first prayer attendance and correction journal. No simulated hardware/cloud results. */
final class AttendanceStore {
    private final SQLiteDatabase db;
    private final StaffAccounts staff;
    static final String[] PRAYERS = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};
    static final ZoneId ZONE=ZoneId.of("Asia/Colombo");

    static void schema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS prayer_schedule(prayer TEXT PRIMARY KEY, opens TEXT NOT NULL DEFAULT '', deadline TEXT NOT NULL DEFAULT '', ends TEXT NOT NULL DEFAULT '', late_minutes INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0)");
        for(String p:PRAYERS) db.execSQL("INSERT OR IGNORE INTO prayer_schedule(prayer) VALUES(?)",new Object[]{p});
        db.execSQL("CREATE TABLE IF NOT EXISTS special_events(id TEXT PRIMARY KEY, title TEXT NOT NULL, event_date TEXT NOT NULL, opens TEXT NOT NULL, deadline TEXT NOT NULL, ends TEXT NOT NULL, late_minutes INTEGER NOT NULL DEFAULT 0, group_name TEXT NOT NULL DEFAULT '', active INTEGER NOT NULL DEFAULT 1, created_by TEXT NOT NULL, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_special_date ON special_events(event_date, active)");
        db.execSQL("CREATE TABLE IF NOT EXISTS attendance(id TEXT PRIMARY KEY, student_id TEXT NOT NULL REFERENCES students(id), session_key TEXT NOT NULL, session_date TEXT NOT NULL, status TEXT NOT NULL, source TEXT NOT NULL, changed_by TEXT NOT NULL, recorded_at INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '', UNIQUE(student_id, session_key, session_date))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_att_date ON attendance(session_date, session_key, status)");
        db.execSQL("CREATE TABLE IF NOT EXISTS attendance_audit(id INTEGER PRIMARY KEY AUTOINCREMENT, student_id TEXT NOT NULL, session_key TEXT NOT NULL, session_date TEXT NOT NULL, old_status TEXT NOT NULL, new_status TEXT NOT NULL, reason TEXT NOT NULL, actor_id TEXT NOT NULL, recorded_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_date ON attendance_audit(recorded_at)");
        db.execSQL("CREATE TABLE IF NOT EXISTS schedule_audit(id INTEGER PRIMARY KEY AUTOINCREMENT, subject TEXT NOT NULL, previous_json TEXT NOT NULL, next_json TEXT NOT NULL, actor_id TEXT NOT NULL, recorded_at INTEGER NOT NULL)");
    }
    AttendanceStore(SQLiteDatabase db,StaffAccounts staff){this.db=db;this.staff=staff;}
    private void admin()throws JSONException {if(!staff.state().optBoolean("admin"))throw new IllegalStateException("Administrator permission required.");}
    private String val(JSONObject d,String k,int limit)throws JSONException {
        String v=d.optString(k,"").trim();
        if(v.length()>limit)throw new IllegalArgumentException(k+" is too long.");
        return v;
    }
    private static String date(String d){try{LocalDate.parse(d);return d;}catch(Exception e){throw new IllegalArgumentException("Invalid date: use YYYY-MM-DD.");}}
    private static int minute(String hhmm){try{return LocalTime.parse(hhmm).getHour()*60+LocalTime.parse(hhmm).getMinute();}catch(Exception e){throw new IllegalArgumentException("Use HH:MM times.");}}
    private static void validateSchedule(String opens,String deadline,String ends,int late){
        if(late<0||late>180)throw new IllegalArgumentException("Late allowance must be 0–180 minutes.");
        if(opens.isEmpty()||deadline.isEmpty()||ends.isEmpty())throw new IllegalArgumentException("Set all three times before saving.");
        int a=minute(opens), b=minute(deadline), c=minute(ends);
        if(a>=b||b>c||b+late>c)throw new IllegalArgumentException("Use Start < Deadline <= Final Close <= End, on the same date.");
    }
    private static JSONObject item(Cursor c)throws JSONException {
        JSONObject r=new JSONObject();
        for(int j=0;j<c.getColumnCount();j++){
            if(c.getType(j)==Cursor.FIELD_TYPE_NULL)r.put(c.getColumnName(j),JSONObject.NULL);
            else if(c.getType(j)==Cursor.FIELD_TYPE_INTEGER)r.put(c.getColumnName(j),c.getLong(j));
            else r.put(c.getColumnName(j),c.getString(j));
        }
        return r;
    }
    private JSONArray query(String sql,String[] args)throws JSONException {
        JSONArray a=new JSONArray();
        try(Cursor c=db.rawQuery(sql,args)){while(c.moveToNext())a.put(item(c));}
        return a;
    }
    JSONArray schedules()throws JSONException {return query("SELECT prayer,opens,deadline,ends,late_minutes,updated_at FROM prayer_schedule ORDER BY CASE prayer WHEN 'Fajr' THEN 1 WHEN 'Dhuhr' THEN 2 WHEN 'Asr' THEN 3 WHEN 'Maghrib' THEN 4 ELSE 5 END",null);}
    void saveSchedule(JSONObject d)throws JSONException {
        admin();String p=val(d,"prayer",20);boolean valid=false;for(String n:PRAYERS)if(n.equals(p))valid=true;
        if(!valid)throw new IllegalArgumentException("Choose one of the five prayers.");
        String opens=val(d,"opens",5),deadline=val(d,"deadline",5),ends=val(d,"ends",5);
        int late=d.optInt("late_minutes",0);validateSchedule(opens,deadline,ends,late);
        JSONObject before=null;try(Cursor c=db.rawQuery("SELECT prayer,opens,deadline,ends,late_minutes FROM prayer_schedule WHERE prayer=?",new String[]{p})){if(c.moveToFirst())before=item(c);}
        JSONObject after=new JSONObject().put("prayer",p).put("opens",opens).put("deadline",deadline).put("ends",ends).put("late_minutes",late);
        db.beginTransaction();try{
            db.execSQL("UPDATE prayer_schedule SET opens=?,deadline=?,ends=?,late_minutes=?,updated_at=? WHERE prayer=?",new Object[]{opens,deadline,ends,late,System.currentTimeMillis(),p});
            scheduleLog("prayer:"+p,before==null?"":before.toString(),after.toString());
            staff.record(staff.identity(),"Updated prayer schedule",p);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private void scheduleLog(String subject,String old,String next){db.execSQL("INSERT INTO schedule_audit(subject,previous_json,next_json,actor_id,recorded_at) VALUES(?,?,?,?,?)",new Object[]{subject,old,next,staff.identity(),System.currentTimeMillis()});}
    JSONArray events()throws JSONException {return query("SELECT id,title,event_date,opens,deadline,ends,late_minutes,group_name,active,updated_at FROM special_events ORDER BY event_date DESC,title LIMIT 600",null);}
    void saveEvent(JSONObject d)throws JSONException {
        admin();String id=val(d,"id",80),title=val(d,"title",120),on=date(val(d,"event_date",10)),opens=val(d,"opens",5),deadline=val(d,"deadline",5),ends=val(d,"ends",5),group=val(d,"group_name",80);
        int late=d.optInt("late_minutes",0);if(title.isEmpty())throw new IllegalArgumentException("Enter an event name.");validateSchedule(opens,deadline,ends,late);
        boolean exists=false;String previous="";
        if(!id.isEmpty())try(Cursor c=db.rawQuery("SELECT * FROM special_events WHERE id=?",new String[]{id})){if(c.moveToFirst()){exists=true;previous=item(c).toString();}}
        if(id.isEmpty())id=UUID.randomUUID().toString();
        JSONObject after=new JSONObject().put("id",id).put("title",title).put("event_date",on).put("opens",opens).put("deadline",deadline).put("ends",ends).put("late_minutes",late).put("group_name",group);
        db.beginTransaction();try{
            if(exists)db.execSQL("UPDATE special_events SET title=?,event_date=?,opens=?,deadline=?,ends=?,late_minutes=?,group_name=?,updated_at=? WHERE id=?",new Object[]{title,on,opens,deadline,ends,late,group,System.currentTimeMillis(),id});
            else db.execSQL("INSERT INTO special_events(id,title,event_date,opens,deadline,ends,late_minutes,group_name,active,created_by,updated_at) VALUES(?,?,?,?,?,?,?,?,1,?,?)",new Object[]{id,title,on,opens,deadline,ends,late,group,staff.identity(),System.currentTimeMillis()});
            scheduleLog("event:"+id,previous,after.toString());staff.record(staff.identity(),exists?"Edited event":"Created event",title);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    void cancelEvent(JSONObject d)throws JSONException{
        admin();String id=val(d,"id",80);db.beginTransaction();try{db.execSQL("UPDATE special_events SET active=0,updated_at=? WHERE id=?",new Object[]{System.currentTimeMillis(),id});staff.record(staff.identity(),"Cancelled event",id);db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    private void validateSession(String key,String day){
        if(key.startsWith("prayer:")){boolean ok=false;for(String p:PRAYERS)if(key.equals("prayer:"+p))ok=true;if(!ok)throw new IllegalArgumentException("Invalid prayer.");}
        else if(key.startsWith("event:")){
            try(Cursor c=db.rawQuery("SELECT event_date FROM special_events WHERE id=?",new String[]{key.substring(6)})){
                if(!c.moveToFirst()||!day.equals(c.getString(0)))throw new IllegalArgumentException("Event not found for selected date.");
            }
        }else throw new IllegalArgumentException("Select a prayer or special event.");
    }
    void mark(JSONObject d)throws JSONException{
        String student=val(d,"student_id",80),key=val(d,"session_key",120),day=date(val(d,"session_date",10)),status=val(d,"status",24),reason=val(d,"reason",240);
        if(reason.length()<3)throw new IllegalArgumentException("Enter a reason (at least 3 characters) for the staff change.");
        if(!status.equals("PRESENT")&&!status.equals("RED_MARK")&&!status.equals("ABSENT")&&!status.equals("EXCUSED")&&!status.equals("NOT_RECORDED"))throw new IllegalArgumentException("Invalid attendance status.");
        validateSession(key,day);
        String studentGroup="";
        try(Cursor c=db.rawQuery("SELECT group_name FROM students WHERE id=?",new String[]{student})){
            if(!c.moveToFirst())throw new IllegalArgumentException("Student not found.");
            studentGroup=c.getString(0);
        }
        if(key.startsWith("event:")){
            try(Cursor c=db.rawQuery("SELECT group_name FROM special_events WHERE id=?",new String[]{key.substring(6)})){
                if(c.moveToFirst()&&!c.getString(0).isEmpty()&&!studentGroup.equalsIgnoreCase(c.getString(0)))
                    throw new IllegalArgumentException("This student is not in the event's target group.");
            }
        }
        String old="NOT_RECORDED";String id="";
        try(Cursor c=db.rawQuery("SELECT id,status FROM attendance WHERE student_id=? AND session_key=? AND session_date=?",new String[]{student,key,day})){if(c.moveToFirst()){id=c.getString(0);old=c.getString(1);}}
        long now=System.currentTimeMillis();
        db.beginTransaction();try{
            if(id.isEmpty())db.execSQL("INSERT INTO attendance(id,student_id,session_key,session_date,status,source,changed_by,recorded_at,note) VALUES(?,?,?,?,?,'MANUAL',?,?,?)",new Object[]{UUID.randomUUID().toString(),student,key,day,status,staff.identity(),now,reason});
            else db.execSQL("UPDATE attendance SET status=?,source='MANUAL',changed_by=?,recorded_at=?,note=? WHERE id=?",new Object[]{status,staff.identity(),now,reason,id});
            db.execSQL("INSERT INTO attendance_audit(student_id,session_key,session_date,old_status,new_status,reason,actor_id,recorded_at) VALUES(?,?,?,?,?,?,?,?)",new Object[]{student,key,day,old,status,reason,staff.identity(),now});
            staff.record(staff.identity(),"Attendance: "+old+" -> "+status,key+" / "+day);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    JSONArray attendance(JSONObject d)throws JSONException{
        String from=date(d.optString("from",LocalDate.now(ZONE).toString())),to=date(d.optString("to",from));
        if(from.compareTo(to)>0)throw new IllegalArgumentException("Start date must be before end date.");
        int limit=Math.max(1,Math.min(20000,d.optInt("limit",3000)));
        return query("SELECT a.id,a.student_id,s.name,s.admission,s.group_name,a.session_key,a.session_date,a.status,a.source,st.username AS staff,a.recorded_at,a.note FROM attendance a JOIN students s ON s.id=a.student_id LEFT JOIN staff st ON st.id=a.changed_by WHERE a.session_date>=? AND a.session_date<=? ORDER BY a.session_date DESC,a.recorded_at DESC LIMIT "+limit,new String[]{from,to});
    }
    JSONArray audit(JSONObject d)throws JSONException{
        String from=date(d.optString("from",LocalDate.now(ZONE).minusMonths(1).toString())),to=date(d.optString("to",LocalDate.now(ZONE).toString()));
        return query("SELECT a.id,a.session_date,s.name,s.admission,a.session_key,a.old_status,a.new_status,a.reason,st.username AS staff,a.recorded_at FROM attendance_audit a LEFT JOIN students s ON s.id=a.student_id LEFT JOIN staff st ON st.id=a.actor_id WHERE a.session_date>=? AND a.session_date<=? ORDER BY a.id DESC LIMIT 3000",new String[]{from,to});
    }
    JSONObject overview()throws JSONException{
        String today=LocalDate.now(ZONE).toString();int active=0;
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM students WHERE active=1",null)){if(c.moveToFirst())active=c.getInt(0);}
        JSONArray rows=query("SELECT a.session_key,a.status,COUNT(*) AS amount FROM attendance a JOIN students s ON s.id=a.student_id AND s.active=1 WHERE a.session_date=? GROUP BY a.session_key,a.status",new String[]{today});
        return new JSONObject().put("day",today).put("clock",LocalTime.now(ZONE).toString()).put("active",active).put("counts",rows).put("devicesConnected",false).put("cloudConnected",false);
    }
    static String csv(JSONArray rows)throws JSONException {
        StringBuilder s=new StringBuilder("\ufeffdate,session,admission,student,class,status,source,staff,recorded_at,reason\r\n");
        String[] keys={"session_date","session_key","admission","name","group_name","status","source","staff","recorded_at","note"};
        for(int i=0;i<rows.length();i++){
            JSONObject o=rows.getJSONObject(i);
            for(int k=0;k<keys.length;k++){
                if(k>0)s.append(',');
                String v=o.optString(keys[k],"");if(!v.isEmpty()&&"=+-@\t\r'".indexOf(v.charAt(0))>=0)v="'"+v;
                s.append('"').append(v.replace("\"","\"\"")).append('"');
            }s.append("\r\n");
        }return s.toString();
    }
}

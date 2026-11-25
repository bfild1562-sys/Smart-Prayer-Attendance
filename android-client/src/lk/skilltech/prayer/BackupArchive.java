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

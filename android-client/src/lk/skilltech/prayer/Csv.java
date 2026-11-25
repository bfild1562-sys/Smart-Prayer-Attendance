package lk.skilltech.prayer;

import java.util.*;
import org.json.*;

final class Csv {
    static final List<String> HEADER=Arrays.asList("admission","name","group_name","enrolled","active");

    static String encode(JSONArray rows)throws JSONException{
        StringBuilder out=new StringBuilder("\ufeffadmission,name,group_name,enrolled,active\r\n");
        for(int i=0;i<rows.length();i++){
            JSONObject record=rows.getJSONObject(i);
            for(int k=0;k<HEADER.size();k++){
                if(k>0)out.append(',');
                String s=k==4?(record.getBoolean("active")?"1":"0"):record.getString(HEADER.get(k));
                if(!s.isEmpty() && ("=+-@\t\r'".indexOf(s.charAt(0))>=0))s="'"+s;
                out.append('"').append(s.replace("\"","\"\"")).append('"');
            }
            out.append("\r\n");
        }
        return out.toString();
    }

    static List<JSONObject> read(String input)throws JSONException{
        if(input.startsWith("\ufeff"))input=input.substring(1);
        List<List<String>> rows=new ArrayList<>();
        List<String> row=new ArrayList<>();
        StringBuilder cell=new StringBuilder();
        boolean quoted=false,closed=false;
        for(int i=0;i<input.length();i++){
            char c=input.charAt(i);
            if(quoted){
                if(c=='"'){
                    if(i+1<input.length()&&input.charAt(i+1)=='"'){cell.append('"');i++;}
                    else{quoted=false;closed=true;}
                }else cell.append(c);
            }else if(c==','){row.add(cell.toString());cell.setLength(0);closed=false;}
            else if(c=='\r'||c=='\n'){
                if(c=='\r'&&i+1<input.length()&&input.charAt(i+1)=='\n')i++;
                row.add(cell.toString());rows.add(row);row=new ArrayList<>();cell.setLength(0);closed=false;
                if(rows.size()>5001)throw new IllegalArgumentException();
            }else if(c=='"' && cell.length()==0&&!closed){quoted=true;}
            else if(closed||c=='"'){throw new IllegalArgumentException();}
            else cell.append(c);
        }
        if(quoted)throw new IllegalArgumentException();
        if(!row.isEmpty()||cell.length()>0||closed){row.add(cell.toString());rows.add(row);}
        if(rows.isEmpty()||!rows.get(0).equals(HEADER))throw new IllegalArgumentException();
        List<JSONObject> out=new ArrayList<>();
        for(List<String> values:rows.subList(1,rows.size())){
            if(values.size()==1&&values.get(0).isEmpty())continue;
            if(values.size()!=5)throw new IllegalArgumentException();
            JSONObject obj=new JSONObject();
            for(int i=0;i<4;i++){
                String s=values.get(i);
                if(s.length()>1&&s.charAt(0)=='\''&&"=+-@\t\r'".indexOf(s.charAt(1))>=0)s=s.substring(1);
                obj.put(HEADER.get(i),s);
            }
            String val=values.get(4);
            if(!val.equals("0")&&!val.equals("1"))throw new IllegalArgumentException();
            obj.put("active",val.equals("1"));out.add(obj);
        }
        return out;
    }
}

'use strict';
/* All data-changing actions use the Android SQLite bridge. No external workspace URL. */
const root=document.querySelector('#main');
const toast=document.querySelector('#msg');
let page='login',auth=null,people=[],schedules=[],events=[],timer=null,toastTimer=null;
let editStudent=null,editEvent=null,lastInvite='',reportMonth='',reportFilter='',logFrom='',logTo='',markDay='';
let centralInvite='',centralFeedback='',newDeviceKey='';
const PRAYERS=['Fajr','Dhuhr','Asr','Maghrib','Isha'];
const icons={dashboard:'◫',students:'♙',mark:'✓',schedule:'◷',events:'✧',reports:'▤',staff:'♙',cloud:'☁',settings:'⚙'};
const esc=x=>String(x??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const api=(name,details={})=>{let result=JSON.parse(Phone.call(name,JSON.stringify(details)));if(result.error)throw Error(result.error);return result.data;};
const showToast=s=>{toast.textContent=s;clearTimeout(toastTimer);toastTimer=setTimeout(()=>toast.textContent='',4100);};
const today=()=>new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Colombo',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
const monthNow=()=>today().slice(0,7);
const formatDate=d=>{if(!d)return '—';const [y,m,da]=d.split('-');return `${da}/${m}/${y}`;};
const pad=n=>String(n).padStart(2,'0');
const toMin=t=>{if(!t)return NaN;let p=t.split(':').map(Number);return p[0]*60+p[1];};
const toHHMM=n=>{let v=(n%1440+1440)%1440;return `${pad(Math.floor(v/60))}:${pad(v%60)}`;};
const human=t=>{if(!t)return 'Not set';let v=toMin(t);return `${Math.floor(v/60)%12||12}:${pad(v%60)} ${v<720?'AM':'PM'}`;};
const clockNow=()=>{let p=new Intl.DateTimeFormat('en-GB',{timeZone:'Asia/Colombo',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(new Date()).split(':').map(Number);return p[0]*3600+p[1]*60+p[2];};
const dateText=m=>new Date(m).toLocaleString('en-GB',{timeZone:'Asia/Colombo',day:'2-digit',month:'short',hour:'2-digit',minute:'2-digit'});
const number=(n)=>Number(n||0).toLocaleString('en-US');
const badge=(s)=>`<span class="pill ${s==='PRESENT'?'ok':s==='RED_MARK'?'red':s==='ABSENT'?'red':s==='EXCUSED'?'warn':s==='NOT_RECORDED'?'gray':'gray'}">${esc(String(s).replaceAll('_',' '))}</span>`;
const button=(title,act,opts={})=>`<button type="button" class="btn ${opts.style||''} ${opts.small?'smallbtn':''}" data-act="${esc(act)}" ${opts.id?`data-id="${esc(opts.id)}"`:''}>${esc(title)}</button>`;
const field=(label,name,value='',type='text',opts='')=>`<label class="field">${esc(label)}<input name="${esc(name)}" type="${type}" value="${esc(value)}" ${opts}></label>`;
const selectField=(label,name,items,value='',extra='')=>`<label class="field">${esc(label)}<select name="${name}" ${extra}>${items.map(i=>{let v=typeof i==='string'?i:i.value,txt=typeof i==='string'?i:i.label;return `<option value="${esc(v)}" ${String(v)===String(value)?'selected':''}>${esc(txt)}</option>`;}).join('')}</select></label>`;
const empty=(s)=>`<div class="empty">${esc(s)}</div>`;
function clearTicker(){if(timer){clearInterval(timer);timer=null;}}
function refreshData(){auth=api('state');if(auth.unlocked){people=api('list');schedules=api('schedules');events=api('events');}}
function layout(name,subtitle,content){
 clearTicker();page=name;
 const tabs=[['dashboard','Home'],['students','Students'],['mark','Mark'],['schedule','Prayers'],['events','Events'],['reports','Reports']];if(auth.admin)tabs.push(['staff','Staff']);tabs.push(['cloud','Cloud']);tabs.push(['settings','Settings']);
 root.innerHTML=`<div class="topbar"><div><div class="brand"><img class="brand-icon" src="mosque.png" alt=""> Smart Prayer</div><small>Attendance management</small></div><span class="pill">${esc(auth.username)}${auth.admin?' · Admin':''}</span></div>`+
 `<nav class="nav" aria-label="Main sections">${tabs.map(([k,v])=>`<button type="button" class="${k===name?'active':''}" data-act="${k}">${v}</button>`).join('')}</nav>`+
 `<div class="shell"><div class="page-intro"><div class="eyebrow">Smart Prayer / ${esc(name)}</div><h1>${esc(name==='schedule'?'Prayer schedules':name==='mark'?'Manual attendance':name==='settings'?'Device & settings':name==='dashboard'?'Dashboard':name==='students'?'Student register':name==='events'?'Special events':name==='reports'?'Reports & logs':name==='cloud'?'Central staff & sync':'Staff management')}</h1><p>${esc(subtitle)}</p></div>${content}</div>`;
}
function access(mode='login'){
 clearTicker();page=mode;auth=api('state');if(auth.unlocked){refreshData();dashboard();return;}
 const signup=mode==='signup';let bootstrap=auth.first;
 root.innerHTML=`<div class="auth-bg"><div class="auth-top"><div class="eyebrow" style="color:#bbd5f7">STAFF ONLY</div><h1><img class="brand-icon" src="mosque.png" alt=""> Smart Prayer Attendance</h1><p>Secure local access to prayer attendance and student records.</p></div><div class="auth-card"><h2>${signup?(bootstrap?'Create administrator':'Staff registration'):'Staff login'}</h2><p>${signup?(bootstrap?'Set up the first administrator on this device.':'Ask your administrator for a one-time invitation code.'):'Sign in with your username and password.'}</p><form id="${signup?'signup':'login'}">`+
 field('Username','username','','text','maxlength="60" autocomplete="username" autocapitalize="none"')+
 field('Password','password','','password',`minlength="${signup?8:1}" maxlength="128" autocomplete="${signup?'new-password':'current-password'}"`)+
 (signup?field('Confirm password','confirm','','password','minlength="8" maxlength="128"')+(bootstrap?'':field('Staff invitation code','invite','','text','required autocomplete="off"')):'<label class="remember"><input type="checkbox" name="remember"> Keep me signed in on this device</label>')+
 `<button class="btn wide" type="submit">${signup?'Create staff account':'Log in'}</button></form><div class="auth-foot"><button data-act="${signup?'login-page':'signup-page'}">${signup?'Back to login':'Register with invitation'}</button></div></div></div>`;
}
function phase(s,now=clockNow()){
 if(!s.opens||!s.deadline||!s.ends)return {kind:'unset',label:'SCHEDULE NOT SET',left:0};
 const start=toMin(s.opens)*60,normal=toMin(s.deadline)*60,final=(toMin(s.deadline)+Number(s.late_minutes))*60;
 if(now<start)return {kind:'upcoming',label:'OPENS IN',left:start-now};
 if(now<=normal)return {kind:'open',label:'NORMAL ATTENDANCE CLOSES IN',left:normal-now};
 if(now<=final&&Number(s.late_minutes)>0)return {kind:'late',label:'RED MARK CLOSES IN',left:final-now};
 return {kind:'closed',label:'FINGERPRINT WINDOW CLOSED',left:0};
}
function hhmmss(t){t=Math.max(0,Math.ceil(t));return `${pad(Math.floor(t/3600))}:${pad(Math.floor(t%3600/60))}:${pad(t%60)}`;}
function dashboard(){
 refreshData();const o=api('overview');let by={};for(const n of o.counts){let k=n.session_key;(by[k]??={})[n.status]=Number(n.amount);}
 const now=clockNow();const ready=schedules.filter(s=>s.opens).map(s=>({...s,ph:phase(s,now)}));const active=ready.find(s=>['open','late'].includes(s.ph.kind));const next=active||ready.find(s=>s.ph.kind==='upcoming');
 const present=Object.values(by).reduce((n,r)=>n+(r.PRESENT||0),0),late=Object.values(by).reduce((n,r)=>n+(r.RED_MARK||0),0);
 const recent=api('attendanceList',{from:o.day,to:o.day,limit:6});
 layout('dashboard',`Today · ${formatDate(o.day)} · Asia/Colombo`,
 `<div class="card navy"><div class="row between"><span class="status-time">${active?'ACTIVE PRAYER':next?'NEXT PRAYER':'TODAY'}</span><span class="pill ${active?.ph.kind==='late'?'red':'ok'}">${esc(active?.ph.kind==='late'?'RED MARK':active?'OPEN':next?'UPCOMING':'OFFLINE READY')}</span></div>
 <h2 style="font-size:25px;margin-top:13px">${esc((next||{}).prayer||'Prayer attendance')}</h2><div id="big-count" class="countdown">${next?hhmmss(next.ph.left):'--:--:--'}</div><div id="count-label" class="status-time">${next?next.ph.label:'Set prayer times to enable countdowns'}</div><small>${next?`Start ${human(next.opens)} · Normal deadline ${human(next.deadline)} · Late ${next.late_minutes} min`:'No active or upcoming scheduled prayer'}</small></div>`+
 `<div class="metric-grid"><div class="metric"><div class="num">${number(o.active)}</div><small>Active students</small></div><div class="metric"><div class="num green">${number(present)}</div><small>Present marks today</small></div><div class="metric"><div class="num red">${number(late)}</div><small>Red marks today</small></div><div class="metric"><div class="num">${number(recent.length)}</div><small>Recent records shown</small></div></div>`+
 `<div class="cols"><div class="card"><div class="row between"><h2>Five daily prayers</h2>${button('Edit times','schedule',{style:'secondary',small:true})}</div>${schedules.map(s=>{let ph=phase(s);let counts=by['prayer:'+s.prayer]||{};let closed=ph.kind==='closed'&&!!s.opens;let marked=(counts.PRESENT||0)+(counts.RED_MARK||0)+(counts.EXCUSED||0);return `<div class="timeline-row"><div><strong>${s.prayer}</strong><br><small>${human(s.opens)}</small></div><div><div class="row between"><span class="pill ${ph.kind==='late'?'red':ph.kind==='open'?'ok':ph.kind==='closed'?'gray':'warn'}">${ph.kind==='unset'?'Not set':ph.kind==='open'?'Open':ph.kind==='late'?'Red mark':ph.kind==='closed'?'Closed':'Upcoming'}</span><span class="small">✓ ${counts.PRESENT||0} · <span class="red">Late ${counts.RED_MARK||0}</span>${closed?' · Absent '+Math.max(0,o.active-marked):''}</span></div><small>Deadline: ${human(s.deadline)}</small></div></div>`}).join('')}</div>`+
 `<div class="card"><h2>Recent manual attendance</h2>${recent.length?recent.map(r=>`<div class="list-item"><div class="row between"><strong>${esc(r.name)}</strong>${badge(r.status)}</div><small>${esc(r.session_key.replace(':',' · '))} · ${dateText(r.recorded_at)}</small></div>`).join(''):empty('No attendance records yet. Use Manual attendance to record a first entry.')}<div style="margin-top:12px">${button('Mark attendance','mark',{style:'secondary'})}</div></div></div>`+
 `<div class="card"><h2>System status</h2><div class="row between"><span>Local Android database</span><span class="pill ok">Available</span></div><hr><div class="row between"><span>ESP32 / R503Pro</span><span class="pill gray">Not connected</span></div><hr><div class="row between"><span>Google Drive synchronization</span><span class="pill gray">Not configured</span></div><div class="footer-note">This version stores real data on this Android phone. Device connectivity and cross-phone cloud sync are not implemented yet.</div></div>`);
 if(next){timer=setInterval(()=>{if(page!=='dashboard')return;let ph=phase(next);let t=document.querySelector('#big-count'),l=document.querySelector('#count-label');if(t)t.textContent=hhmmss(ph.left);if(l)l.textContent=ph.label;},1000);}
}
let studentQuery='',studentGroup='';
function students(){
 refreshData();layout('students','Add, search and manage student records stored on this phone.',
 `<div class="buttons" style="margin-bottom:15px">${auth.admin?button('+ Add student','student-add'):''}${button('Import CSV','import',{style:'secondary'})}${button('Export CSV','export',{style:'secondary'})}</div>`+
 `<div class="card"><div class="form-grid">${field('Search name or admission','student_search',studentQuery,'search','placeholder="Search 300 students"')}${selectField('Class / group','student_group',[{value:'',label:'All groups'},...[...new Set(people.map(p=>p.group_name))].sort().map(g=>({value:g,label:g}))],studentGroup)}</div><div id="student-list"></div></div>`);
 document.querySelector('[name=student_search]').oninput=e=>{studentQuery=e.target.value;drawStudents();};document.querySelector('[name=student_group]').onchange=e=>{studentGroup=e.target.value;drawStudents();};drawStudents();
}
function drawStudents(){let box=document.querySelector('#student-list');if(!box)return;let q=studentQuery.trim().toLowerCase(),rows=people.filter(p=>(!studentGroup||p.group_name===studentGroup)&&(!q||[p.name,p.admission,p.group_name].some(v=>v.toLowerCase().includes(q))));
 box.innerHTML=`<small>${rows.length} of ${people.length} students</small>`+(rows.length?rows.slice(0,150).map(p=>`<div class="list-item row between"><div><div class="item-title">${esc(p.name)}</div><small>${esc(p.admission)} · ${esc(p.group_name)} · ${p.active?'Active':'Inactive'}${p.fingerprint_number?' · Fingerprint #'+p.fingerprint_number:''}</small></div>${auth.admin?button('Edit','student-edit',{id:p.id,style:'secondary',small:true}):''}</div>`).join(''):empty('No students match this search.'))+(rows.length>150?'<small>Showing first 150. Narrow the search to find others.</small>':'');
}
function studentEditor(p={}){
 editStudent=p;layout('students',p.id?'Edit student':'Create student',`<div class="card"><form id="student-save"><h2>${p.id?'Edit':'Add'} student</h2><div class="form-grid">${field('Full name','name',p.name||'','text','maxlength="120"')}${field('Admission number','admission',p.admission||'','text','maxlength="60"')}${field('Class / group','group_name',p.group_name||'','text','maxlength="80"')}${field('Fingerprint number (1–300, optional)','fingerprint_number',p.fingerprint_number??'','number','min="1" max="300"')}${field('Enrollment date','enrolled',p.enrolled||today(),'date')}${selectField('Status','active',[{label:'Active',value:'1'},{label:'Inactive',value:'0'}],p.active===false?'0':'1')}</div><div class="buttons"><button class="btn" type="submit">Save student</button>${button('Cancel','students',{style:'secondary'})}</div></form></div>`);
}
function sessionOptions(day){let p=PRAYERS.map(s=>({value:'prayer:'+s,label:s+' prayer'}));return p.concat(events.filter(e=>e.event_date===day&&Number(e.active)===1).map(e=>({value:'event:'+e.id,label:e.title+' (special)'})));}
function markPage(day=markDay||today()){
 refreshData();markDay=day;let options=sessionOptions(day),active=people.filter(p=>p.active),logs=api('attendanceList',{from:day,to:day,limit:80});
 layout('mark','Manual attendance with staff identity and correction history.',
 `<div class="card"><h2>Mark / correct attendance</h2><form id="attendance-mark"><div class="form-grid">${field('Session date','session_date',day,'date')}${selectField('Prayer or program','session_key',options)}${selectField('Student','student_id',active.map(p=>({value:p.id,label:p.name+' · '+p.admission})))}${selectField('Attendance status','status',['PRESENT','RED_MARK','ABSENT','EXCUSED','NOT_RECORDED'].map(v=>({value:v,label:v.replace('_',' ')})))}</div><label class="field">Reason / note<textarea name="reason" required minlength="3" maxlength="240" placeholder="E.g. present, teacher verification, correction of entry"></textarea></label><button class="btn" type="submit">Save attendance</button><div class="footer-note">The original and new statuses, staff username, time and reason are recorded for every manual change. NOT RECORDED unmarks the effective attendance without deleting the audit trail.</div></form></div>`+
 `<div class="card"><div class="row between"><h2>Records for ${formatDate(day)}</h2><span class="pill">${logs.length} shown</span></div>${logs.length?`<div class="table-scroll"><table><thead><tr><th>Student</th><th>Session</th><th>Status</th><th>Updated by</th></tr></thead><tbody>${logs.map(r=>`<tr><td>${esc(r.name)}<br><small>${esc(r.admission)}</small></td><td>${esc(r.session_key.replace('prayer:','').replace('event:','Event '))}</td><td>${badge(r.status)}</td><td>${esc(r.staff)}</td></tr>`).join('')}</tbody></table></div>`:empty('No manual attendance records for this date.')}</div>`);
 document.querySelector('[name=session_date]').onchange=e=>markPage(e.target.value);
}
function schedulePage(){
 refreshData();layout('schedule','Saved prayer times remain in effect until an admin changes them. Adjust all times together with one control.',
 `<div class="card soft"><div class="item-title">How linked time shift works</div><p>Shift +3 minutes moves the start, normal deadline and end +3 minutes together. Your RED MARK allowance stays unchanged. Times are not reset every week.</p></div>`+
 schedules.map(s=>`<div class="card"><div class="row between"><div><h2>${esc(s.prayer)}</h2><small>Late fingerprint allowance: ${s.late_minutes} min · Final close ${s.deadline?human(toHHMM(toMin(s.deadline)+Number(s.late_minutes))):'Not set'}</small></div>${badge(s.opens?'SAVED':'NOT_RECORDED')}</div>`+
 `<form class="schedule-form" data-prayer="${s.prayer}"><div class="form-grid">${field('Opening time','opens',s.opens,'time')}${field('Normal fingerprint deadline','deadline',s.deadline,'time')}${field('Session ending time','ends',s.ends,'time')}${field('RED MARK allowance (minutes)','late_minutes',s.late_minutes,'number','min="0" max="180" step="1"')}</div>`+
 (auth.admin?`<div class="buttons" style="margin:8px 0"><small style="width:100%">Quick adjust (unsaved)</small>${[-5,-2,-1,1,2,5].map(m=>`<button type="button" class="btn secondary smallbtn" data-shift="${m}">${m>0?'+':''}${m} min</button>`).join('')}<div class="row"><input aria-label="Custom shift minutes" type="number" name="shift_minutes" min="-120" max="120" step="1" placeholder="Custom minutes" style="width:140px;min-height:39px;border:1px solid #cbd9e8;border-radius:9px;padding:8px"><button type="button" class="btn secondary smallbtn" data-shift-custom="1">Apply shift</button></div></div><button type="submit" class="btn">Save ${s.prayer} schedule</button>`:'<div class="small">Only administrators can change prayer schedules.</div>')+`</form></div>`).join('')+
 `<div class="warnbox">Schedule validation: Start &lt; normal deadline ≤ final RED MARK close ≤ end. This local version supports same-day windows. Device schedule synchronization comes with ESP32 integration.</div>`);
 if(!auth.admin)document.querySelectorAll('.schedule-form input,.schedule-form select').forEach(x=>x.disabled=true);
}
function eventPage(){
 refreshData();layout('events','Set fingerprint windows for additional programs. Existing student IDs will be used when the device is integrated.',
 `${auth.admin?`<div class="card"><h2>${editEvent?'Edit special program':'Create special program'}</h2><form id="event-save"><div class="form-grid">${field('Program name','title',editEvent?.title||'','text','maxlength="120"')}${field('Date','event_date',editEvent?.event_date||today(),'date')}${field('Opening time','opens',editEvent?.opens||'','time')}${field('Normal deadline','deadline',editEvent?.deadline||'','time')}${field('Ending time','ends',editEvent?.ends||'','time')}${field('RED MARK allowance (minutes)','late_minutes',editEvent?.late_minutes||0,'number','min="0" max="180" step="1"')}${field('Target class / group (blank means all)','group_name',editEvent?.group_name||'','text','maxlength="80"')}</div><div class="buttons"><button class="btn" type="submit">${editEvent?'Save changes':'Create event'}</button>${editEvent?button('Cancel edit','event-reset',{style:'secondary'}):''}</div></form></div>`:''}`+
 `<div class="card"><h2>Special programs (${events.length})</h2>${events.length?events.map(e=>`<div class="list-item"><div class="row between"><div><strong>${esc(e.title)}</strong><br><small>${formatDate(e.event_date)} · ${human(e.opens)}–${human(e.ends)} · RED MARK ${e.late_minutes} min${e.group_name?' · '+esc(e.group_name):''}</small></div><span class="pill ${Number(e.active)===1?'ok':'gray'}">${Number(e.active)===1?'Active':'Cancelled'}</span></div>${auth.admin&&Number(e.active)===1?`<div class="buttons" style="margin-top:8px">${button('Edit','event-edit',{id:e.id,style:'secondary',small:true})}${button('Cancel program','event-cancel',{id:e.id,style:'danger',small:true})}</div>`:''}</div>`).join(''):empty('No special events yet.')}</div>`);
}
function reports(){
 refreshData();if(!reportMonth)reportMonth=monthNow();let from=reportMonth+'-01';let [y,m]=reportMonth.split('-').map(Number);let last=new Date(y,m,0).getDate();let to=reportMonth+'-'+pad(last);let rows=api('attendanceList',{from,to,limit:20000});
 const byStatus={PRESENT:0,RED_MARK:0,ABSENT:0,EXCUSED:0,NOT_RECORDED:0};rows.forEach(r=>{if(byStatus[r.status]!==undefined)byStatus[r.status]++;});
 const filtered=reportFilter?rows.filter(r=>r.session_key===reportFilter):rows;
 const shown=filtered.filter(r=>r.status!=='NOT_RECORDED');
 const groupStat={};for(const r of shown){let k=r.session_key;if(!groupStat[k])groupStat[k]={PRESENT:0,RED_MARK:0,ABSENT:0,EXCUSED:0};groupStat[k][r.status]=(groupStat[k][r.status]||0)+1;}
 const sessions=PRAYERS.map(v=>({value:'prayer:'+v,label:v})).concat(events.map(e=>({value:'event:'+e.id,label:e.title})));
 layout('reports','Real local attendance entries, correction history and CSV export. Cloud and ESP32 records are not connected yet.',
 `<div class="card"><div class="form-grid">${field('Report month','report_month',reportMonth,'month')}${selectField('Prayer / program','report_filter',[{value:'',label:'All sessions'},...sessions],reportFilter)}</div><div class="buttons">${button('Export attendance CSV','attendance-export')}${button('Show change history','logs',{style:'secondary'})}</div></div>`+
 `<div class="metric-grid"><div class="metric"><div class="num green">${number(shown.filter(r=>r.status==='PRESENT').length)}</div><small>Present records</small></div><div class="metric"><div class="num red">${number(shown.filter(r=>r.status==='RED_MARK').length)}</div><small>Red marks</small></div><div class="metric"><div class="num">${number(shown.filter(r=>r.status==='EXCUSED').length)}</div><small>Excused</small></div><div class="metric"><div class="num">${number(shown.filter(r=>r.status==='ABSENT').length)}</div><small>Manually absent</small></div></div>`+
 `<div class="card"><h2>By prayer / event</h2>${Object.entries(groupStat).length?Object.entries(groupStat).map(([key,v])=>{let total=(v.PRESENT||0)+(v.RED_MARK||0)+(v.ABSENT||0)+(v.EXCUSED||0);return `<div class="list-item"><div class="row between"><strong>${esc(key.replace('prayer:',''))}</strong><small>${total} records</small></div><div class="reportbar"><span class="present" style="width:${(v.PRESENT||0)/total*100}%"></span><span class="late" style="width:${(v.RED_MARK||0)/total*100}%"></span><span class="absent" style="width:${((v.ABSENT||0)+(v.EXCUSED||0))/total*100}%"></span></div><small>${v.PRESENT||0} Present · ${v.RED_MARK||0} Red mark · ${v.ABSENT||0} Absent · ${v.EXCUSED||0} Excused</small></div>`}).join(''):empty('No attendance records for this month.')}</div>`+
 `<div class="card"><div class="row between"><h2>Attendance records</h2><span class="pill">${shown.length}</span></div>${shown.length?`<div class="table-scroll"><table><thead><tr><th>Date</th><th>Student</th><th>Session</th><th>Status</th><th>Staff</th></tr></thead><tbody>${shown.slice(0,300).map(r=>`<tr><td>${formatDate(r.session_date)}</td><td>${esc(r.name)}<br><small>${esc(r.admission)}</small></td><td>${esc(r.session_key.replace('prayer:',''))}</td><td>${badge(r.status)}</td><td>${esc(r.staff)}</td></tr>`).join('')}</tbody></table></div>`:empty('Nothing recorded for this selection.')}</div>`+
 `<div class="warnbox">This report counts saved entries, not automatically inferred absences. Complete monthly attendance percentages and fingerprint events will be added with the central backend and device integration.</div>`);
 document.querySelector('[name=report_month]').onchange=e=>{reportMonth=e.target.value;reports();};document.querySelector('[name=report_filter]').onchange=e=>{reportFilter=e.target.value;reports();};
}
function logs(){
 refreshData();if(!logFrom)logFrom=monthNow()+'-01';if(!logTo)logTo=today();let rows=api('attendanceAudit',{from:logFrom,to:logTo});
 layout('reports','Audit trail of manual attendance changes, including original and replacement status.',
 `<div class="card"><div class="form-grid">${field('From','log_from',logFrom,'date')}${field('To','log_to',logTo,'date')}</div>${button('Back to reports','reports',{style:'secondary'})}</div><div class="card"><h2>Manual corrections (${rows.length})</h2>${rows.length?rows.map(r=>`<div class="list-item"><div class="row between"><strong>${esc(r.name||r.admission||'Student')}</strong><small>${dateText(r.recorded_at)}</small></div><small>${esc(r.session_key)} · ${formatDate(r.session_date)}</small><div style="margin:6px 0">${badge(r.old_status)} → ${badge(r.new_status)}</div><small>By ${esc(r.staff)} · Reason: ${esc(r.reason)}</small></div>`).join(''):empty('No changes for the selected dates.')}</div>`);
 document.querySelector('[name=log_from]').onchange=e=>{logFrom=e.target.value;logs();};document.querySelector('[name=log_to]').onchange=e=>{logTo=e.target.value;logs();};
}
function staffPage(){
 refreshData();const members=api('staffList'),invitations=api('invitations'),activity=api('activity');
 layout('staff','Manage staff-only registration and see who changed records.',
 `<div class="card"><h2>Staff invitation codes</h2><p>Generate a single-use code valid for seven days. Give it privately to an authorized staff member.</p>${button('Generate registration code','invite-generate')}${lastInvite?`<div style="margin-top:12px"><small>Copy this code now — it will not be shown again.</small><div class="codebox">${esc(lastInvite)}</div></div>`:''}<div class="footer-note">Codes work only on this Android device. A central backend is needed before staff can register on multiple phones.</div>${invitations.length?invitations.slice(0,12).map(i=>`<div class="list-item row between"><div><span class="pill ${i.used?'ok':i.revoked?'gray':Date.now()>i.expires?'red':'warn'}">${i.used?'Used':i.revoked?'Revoked':Date.now()>i.expires?'Expired':'Available'}</span><br><small>Expires ${dateText(i.expires)}</small></div>${!i.used&&!i.revoked&&Date.now()<=i.expires?button('Revoke','invite-revoke',{id:i.id,style:'danger',small:true}):''}</div>`).join(''):''}</div>`+
 `<div class="card"><h2>Registered staff</h2>${members.map(s=>`<div class="list-item row between"><div><strong>${esc(s.username)}</strong><br><small>${s.admin?'Administrator':'Staff'} · ${s.approved?'Enabled':'Disabled'}</small></div>${s.id===auth.id?'':s.admin?'':button(s.approved?'Disable':'Enable','staff-toggle',{id:s.id,style:'secondary',small:true})}</div>`).join('')}</div>`+
 `<div class="card"><h2>Recent staff activity</h2>${activity.length?activity.slice(0,50).map(a=>`<div class="list-item"><div class="row between"><strong>${esc(a.username)}</strong><small>${dateText(a.at)}</small></div><small>${esc(a.action)} · ${esc(a.subject)}</small></div>`).join(''):empty('No staff activity yet.')}</div>`);
}
function cloudPage(){
 refreshData();const c=api('centralStatus'),summary=c.summary||null;
 const overview=summary?`<div class="metric-grid"><div class="metric"><div class="num">${number(summary.students)}</div><small>Central students</small></div><div class="metric"><div class="num">${number(summary.attendance)}</div><small>Central attendance entries</small></div><div class="metric"><div class="num">${number(summary.staff)}</div><small>Central staff</small></div></div>
 <div class="card"><h2>Shared recent attendance</h2>${(summary.recent||[]).length?summary.recent.map(x=>`<div class="list-item"><div class="row between"><strong>${esc(x.name||x.student_id)}</strong>${badge(x.status)}</div><small>${esc(x.session_key)} · ${esc(x.session_date)} · ${esc(x.changed_by)}</small></div>`).join(''):empty('No central attendance uploaded yet.')}</div>`:'';
 layout('cloud','Institutional records synchronize while signed in. Offline attendance stays available.',
 `<div class="card"><div class="row between"><h2>Central server</h2><span class="pill ${c.connected?'ok':'warn'}">${c.connected?'Signed in':'Not connected'}</span></div>
 <p>This is NOT an external website launcher. Your offline dashboard, schedules and manual marking work without a central server. You need a real, deployed HTTPS server for sharing data across phones.</p>
 ${c.connected?`<div class="goodbox">Signed in as <strong>${esc(c.username)}</strong> (${esc(c.role)}) at ${esc(c.server)}</div><div class="buttons">${button('Refresh shared records','central-refresh',{style:'secondary'})}${button('Disconnect central session','central-disconnect',{style:'subtle'})}</div>`:
 `<form id="central-login-form">${field('Institution HTTPS server URL','server',c.server||'','url','readonly')}${field('Central staff username','username','','text','required autocomplete="username"')}${field('Central staff password','password','','password','required autocomplete="current-password"')}<button type="submit" class="btn wide">Connect and sign in</button></form>`}
 ${c.error?`<div class="warnbox" style="margin-top:10px">${esc(c.error)}</div>`:''}
 ${centralFeedback?`<div class="goodbox" style="margin-top:10px">${esc(centralFeedback)}</div>`:''}
 </div>`+
 (c.connected?`<div class="card"><h2>Institution sync</h2><p>Changes on this phone are uploaded automatically while the app is open and a central session is connected. You can also sync immediately.  Staff accounts upload attendance only. Each batch is checked for duplicates and conflicts before saving.</p>
 <div class="warnbox">Sync uploads local changes, then downloads the current shared roster, schedules, events and attendance. Conflicts are reported rather than silently discarded.</div>
 <div class="buttons">${button(c.busy?'Uploading…':'Sync now','central-upload',{style:'secondary'})}</div></div>${overview}`:
 `<div class="card"><h2>Register on the central server (staff invitation)</h2><p>A central administrator generates a one-time invitation. Staff register once and use their central username/password on any authorized device.</p>
 <form id="central-register-form">${field('Institution HTTPS URL','server',c.server||'','url','readonly')}${field('One-time central invitation code','code','','text','required autocomplete="off"')}${field('Choose central username','username','','text','required minlength="3"')}${field('Choose central password','password','','password','required minlength="10"')}<button type="submit" class="btn wide">Register central account</button></form></div>`)+
 (c.connected && c.role==='admin'?`<div class="card"><h2>Invite a central staff member</h2><p>Single-use invitation expires in 7 days. Share it privately.</p>${button('Generate central staff invitation','central-invite')}${centralInvite?`<div class="codebox" style="margin-top:12px">${esc(centralInvite)}</div><small>Copy this code now; it won't appear again.</small>`:''}</div>`:'')+
 (c.connected && c.role==='admin'?`<div class="card"><h2>Institution Google Drive backups</h2>
 <p>The institution administrator authorizes ONE Google account on the central server. Staff do not need Gmail accounts. Encrypted daily backups run on the server after deployment.</p>
 <div class="list-item"><strong>Backup connection:</strong> ${c.institution_drive?.connected?'Connected':'Not yet connected'}<br>
 <small>${c.institution_drive?.last_backup?.day?'Last backup: '+esc(c.institution_drive.last_backup.day):'No central backup yet'}</small></div>
 <div class="buttons">${button('Connect institution Google Drive','central-drive-connect')}${button('Back up now','central-drive-backup',{style:'secondary'})}</div>
 <div class="footer-note">Requires a deployed HTTPS server and a Google Cloud OAuth WEB client configured on that server.</div></div>`:'')+
 (c.connected && c.role==='admin'?`<div class="card"><h2>ESP32 attendance devices</h2><p>Create an authenticated device identity for the ESP32. Enter its one-time key in the firmware configuration (not in a public GitHub repository).</p>
 <form id="device-create-form">${field('Device name','name','Main prayer hall','text','required maxlength="100"')}<button class="btn" type="submit">Create ESP32 device key</button></form>
 ${newDeviceKey?`<div class="codebox">${esc(newDeviceKey)}</div><small>Copy now. This key will not be displayed again.</small>`:''}
 ${(c.devices||[]).map(d=>`<div class="list-item"><strong>${esc(d.name)}</strong><br><small>ID ${esc(d.id)} · ${d.enabled?'Enabled':'Disabled'} · Last seen ${d.last_seen?dateText(d.last_seen*1000):'Never'}</small></div>`).join('')}</div>`:'')+
 `<div class="card"><h2>Institution cloud</h2><p>Signed-in staff share central student and attendance records. The administrator manages the server and its Google Drive backup settings. Local records remain usable without internet.</p></div>`);
}
function settings(){
 refreshData();
 const driveState=api('driveStatus'),signing=api('appSigning');
 const testTime=driveState.lastTestAt?esc(dateText(driveState.lastTestAt)):'Not yet tested';
 const backupTime=driveState.lastBackupAt?esc(dateText(driveState.lastBackupAt)):'No encrypted backup uploaded yet';
 const backupName=driveState.lastBackupName?'<br><small>'+esc(driveState.lastBackupName)+'</small>':'';
 const driveError=driveState.lastError?'<div class="warnbox" style="margin-top:10px">Last Google Drive error: '+esc(driveState.lastError)+'</div>':'';
 const driveActions=auth.admin
   ?'<div class="buttons">'+button('Connect Google Drive & upload test','drive-connect')+button('Copy Android SHA-1','copy-signing',{style:'secondary'})+'</div>'+
    '<form id="drive-backup-form" style="margin-top:14px"><h3>Manual encrypted attendance backup</h3><p>Choose a recovery password of 12–128 characters. You will need this exact password to recover the backup.</p>'+
    field('Backup recovery password','password','','password','required minlength="12" maxlength="128" autocomplete="new-password"')+
    field('Confirm recovery password','confirm','','password','required minlength="12" maxlength="128" autocomplete="new-password"')+
    '<button class="btn wide" type="submit">Encrypt & upload backup</button></form>'+
    '<form id="drive-restore-form" style="margin-top:18px;border-top:1px solid var(--line);padding-top:14px"><h3>Restore from encrypted backup</h3><p>Choose a .spab backup from Google Drive or phone storage. Existing student and attendance records are preserved; only missing records are added.</p>'+
    field('Backup recovery password','password','','password','required minlength="12" maxlength="128" autocomplete="new-password"')+
    field('Confirm recovery password','confirm','','password','required minlength="12" maxlength="128" autocomplete="new-password"')+
    '<button class="btn wide secondary" type="submit">Choose backup file & restore</button></form>'
   :'<div class="warnbox">Only the administrator can connect Google Drive or create encrypted backups.</div>';
 layout('settings','Local storage, encrypted backup and device configuration.',
 '<div class="card"><h2>Application</h2><div class="list-item row between"><div><strong>Android attendance app v4.1</strong><br><small>Package: '+esc(signing.package||'lk.skilltech.prayer')+'</small></div><span class="pill ok">Local database</span></div><div class="list-item"><strong>Students</strong><br><small>'+people.length+' stored in this phone\'s SQLite database</small></div><div class="list-item"><strong>Android signing SHA-1</strong><br><small style="overflow-wrap:anywhere">'+esc(signing.sha1||'Unavailable')+'</small></div><div class="buttons" style="margin-top:12px">'+button('Export students','export',{style:'secondary'})+button('Sign out','logout',{style:'subtle'})+'</div></div>'+
 '<div class="card"><h2>Phone Google Drive backup</h2><p>This backs up the records stored on THIS phone to the Google account selected during authorization. It is separate from institution-server synchronization.</p>'+
 '<div class="list-item"><strong>Last test upload:</strong><br><small>'+testTime+'</small></div>'+
 '<div class="list-item"><strong>Last encrypted backup:</strong><br><small>'+backupTime+'</small>'+backupName+'</div>'+
 driveActions+driveError+
 '<div class="footer-note">Encrypted snapshots include students, schedules, events, attendance and audit history. Restore merges missing records and never overwrites existing entries. Old staff names are kept as non-login audit identities. Passwords, invitations and fingerprint templates are not restored. Backups are still manual; automatic backups are not yet implemented.</div></div>'+
 '<div class="card"><h2>Institution backup & synchronization</h2><p>When connected to the central server, shared records synchronize between authorized staff devices. The institution server has a separate Google Drive backup connection.</p><div class="buttons">'+button('Open institution cloud','cloud',{style:'secondary'})+'</div></div>'+
 '<div class="card"><h2>Hardware roadmap</h2><div class="list-item row between"><div><strong>R503Pro / ESP32</strong><br><small>AutoIdentify and AutoEnroll firmware source included</small></div><span class="pill warn">Device setup required</span></div><div class="list-item row between"><div><strong>8GB microSD card</strong><br><small>SD journal + pending-upload queue in ESP32 firmware</small></div><span class="pill warn">Device setup required</span></div><div class="list-item row between"><div><strong>TFT + buzzer + DS3231</strong><br><small>TFT/RTC/buzzer integration included in firmware source</small></div><span class="pill warn">Device setup required</span></div></div>'+
 '<div class="warnbox">Keep your recovery password somewhere safe and separate from the phone. Without it, the encrypted backup cannot be decrypted.</div>');
}
function go(name){try{switch(name){case'dashboard':dashboard();break;case'students':students();break;case'mark':markPage();break;case'schedule':schedulePage();break;case'events':editEvent=null;eventPage();break;case'reports':reports();break;case'staff':if(auth.admin)staffPage();else dashboard();break;case'cloud':cloudPage();break;case'settings':settings();break;default:dashboard();}}catch(err){showToast(err.message);}}
document.addEventListener('click',e=>{let b=e.target.closest('[data-act]');if(b){let action=b.dataset.act,id=b.dataset.id;try{
 if(['dashboard','students','mark','schedule','events','reports','staff','cloud','settings'].includes(action)){go(action);return;}
 switch(action){
 case 'login-page':access('login');break;case'signup-page':access('signup');break;
 case 'logout':api('lock');access();break;
 case 'central-drive-connect':api('centralDriveConnect');showToast('Opening institution Google authorization…');break;
 case 'central-drive-backup':api('centralDriveBackup');showToast('Starting encrypted institution backup…');break;
 case 'central-refresh':api('centralRefresh');showToast('Loading central overview…');break;
 case 'central-upload':if(confirm('Upload local records to the central institution server? Conflicts will stop, not overwrite records.')){api('centralUpload');showToast('Uploading…');}break;
 case 'central-invite':api('centralInvite');showToast('Generating central invitation…');break;
 case 'central-disconnect':api('centralLogout');centralInvite='';cloudPage();break;
 case 'drive-connect':api('driveConnect');showToast('Opening Google Drive authorization…');break;
 case 'copy-signing':api('copySigning');showToast('SHA-1 copied to clipboard.');break;
 case 'student-add':if(auth.admin)studentEditor();break;
 case 'student-edit':if(auth.admin)studentEditor(people.find(p=>p.id===id));break;
 case 'import':case'export':api(action);showToast('Choose a file using the Android document picker.');break;
 case 'attendance-export':api('attendanceExport',{from:reportMonth+'-01',to:(()=>{let [y,m]=reportMonth.split('-').map(Number);return reportMonth+'-'+pad(new Date(y,m,0).getDate());})()});showToast('Choose a destination for the attendance CSV.');break;
 case 'logs':logs();break;
 case 'event-edit':editEvent=events.find(x=>x.id===id);eventPage();break;
 case 'event-reset':editEvent=null;eventPage();break;
 case 'event-cancel':if(confirm('Cancel this program? Historical attendance is retained.')){api('eventCancel',{id});editEvent=null;eventPage();showToast('Program cancelled.');}break;
 case 'invite-generate':lastInvite=api('inviteGenerate');staffPage();break;
 case 'invite-revoke':if(confirm('Revoke this invitation?')){api('inviteRevoke',{id});staffPage();}break;
 case 'staff-toggle':{let found=api('staffList').find(s=>s.id===id);if(found&&confirm(`${found.approved?'Disable':'Enable'} ${found.username}?`)){api('staffAccess',{id,active:!found.approved});staffPage();}break;}
 } }catch(err){showToast(err.message);}return;}
 const shift=e.target.closest('[data-shift],[data-shift-custom]');if(shift){let form=shift.closest('form');if(!form)return;let amt=shift.hasAttribute('data-shift-custom')?Number(form.elements.namedItem('shift_minutes').value):Number(shift.dataset.shift);if(!Number.isInteger(amt)||amt>120||amt< -120||amt===0){showToast('Enter a non-zero shift between -120 and 120 minutes.');return;}let inputNames=['opens','deadline','ends'];let fields=inputNames.map(n=>form.elements.namedItem(n));if(fields.some(x=>!x.value)){showToast('First enter all three times.');return;}fields.forEach(x=>x.value=toHHMM(toMin(x.value)+amt));showToast(`${amt>0?'+':''}${amt} minutes previewed. Press Save to keep it.`);}
});
document.addEventListener('submit',e=>{e.preventDefault();let form=e.target,d=Object.fromEntries(new FormData(form));try{switch(form.id){
 case 'login':api('login',{...d,remember:d.remember==='on'});lastInvite='';refreshData();dashboard();break;
 case 'signup':if(d.password!==d.confirm)throw Error('Passwords do not match.');let r=api('signup',d);access('login');showToast(r);break;
 case 'central-login-form':centralFeedback='';api('centralLogin',d);form.elements.namedItem('password').value='';showToast('Contacting institution server…');break;
 case 'device-create-form':api('centralCreateDevice',d);showToast('Creating secure ESP32 device credential…');break;
 case 'central-register-form':centralFeedback='';api('centralRegister',d);form.elements.namedItem('password').value='';showToast('Registering with invitation…');break;
 case 'drive-backup-form':
   if(d.password!==d.confirm)throw Error('Backup passwords do not match.');
   if(d.password.length<12||d.password.length>128)throw Error('Use a recovery password of 12–128 characters.');
   if(!confirm('Upload an ENCRYPTED copy of student and attendance data to the authorized Google Drive account? Keep the recovery password safe.'))break;
   api('driveBackup',{password:d.password});
   form.reset();
   showToast('Preparing encrypted attendance backup…');
   break;
 case 'drive-restore-form':
   if(d.password!==d.confirm)throw Error('Backup passwords do not match.');
   if(d.password.length<12||d.password.length>128)throw Error('Use a recovery password of 12–128 characters.');
   if(!confirm('RESTORE by merging records from an encrypted backup? Existing records on this phone will NOT be overwritten or deleted. Continue?'))break;
   api('driveRestore',{password:d.password});
   form.reset();
   showToast('Choose the encrypted .spab backup file…');
   break;
 case 'student-save':if(!auth.admin)throw Error('Admin only.');api('save',{...d,id:editStudent?.id||'',active:d.active==='1'});students();showToast('Student saved.');break;
 case 'attendance-mark':api('attendanceMark',d);markPage(d.session_date);showToast('Attendance saved and correction logged.');break;
 case 'event-save':api('eventSave',{...d,id:editEvent?.id||'',late_minutes:Number(d.late_minutes)});editEvent=null;eventPage();showToast('Special event saved.');break;
 }}catch(err){showToast(err.message);}});
document.addEventListener('submit',e=>{if(!e.target.classList.contains('schedule-form'))return;e.preventDefault();try{let d=Object.fromEntries(new FormData(e.target));api('scheduleSave',{...d,prayer:e.target.dataset.prayer,late_minutes:Number(d.late_minutes)});schedulePage();showToast('Schedule saved permanently.');}catch(err){showToast(err.message);}});
window.onCentralUpdate=(event,data)=>{
 if(event==='invite'){centralInvite=data.code;centralFeedback='Invitation generated; share it securely.';}
 else if(event==='deviceCreated'){newDeviceKey='Device '+data.device_id+' · Key '+data.device_key;centralFeedback='Device credential generated. Copy it now.';}
 else if(event==='error'){centralFeedback=data.message||'Cloud error';}
 else if(event==='progress'||event==='uploaded'||event==='registered'){centralFeedback=data.message||'Cloud update';}
 else if(event==='success'){centralFeedback=data.message||'Success';}
 if(page==='cloud')cloudPage();
 if(data.message&&event!=='status')showToast(data.message);
};
window.onDriveUpdate=(message,success)=>{if(page==='settings'){settings();}showToast(message);};
window.onBackupRestoreComplete=(message,success)=>{
 if(success){try{refreshData();}catch(e){}}
 if(page==='settings')settings();
 else if(page==='dashboard')dashboard();
 showToast(message);
};
window.back=()=>{if(page==='signup'){access('login');return true;}if(page==='login')return false;if(page==='dashboard')return false;dashboard();return true;};
window.refreshRoster=()=>{if(page==='students')students();else if(page==='dashboard')dashboard();};
try{access();}catch(e){root.innerHTML=`<div class="card" style="margin:22px">Unable to load local app data: ${esc(e.message)}</div>`;}

/*
 R503Pro / ESP32 WROOM32 — 300 students, 2 templates/student,
 6 native AutoEnroll captures/template (12 captures/student).

 Uses manufacturer's native AutoEnroll 0x31 and AutoIdentify 0x32.
 6 scan events really contribute to each stored template; two templates
 per student. Does not use the previous unverified 4-buffer RegModel path.
 Firmware support must be checked on the actual module before deployment.
 To avoid mistakes, auto-enrollment will not overwrite occupied slots.
 Delete the student's two templates manually before re-registering.

 Wiring (ONLY for previously verified 6-wire harness):
 Sensor Red  -> ESP32 3V3
 Sensor Black-> ESP32 GND
 Sensor Yellow (TX) -> ESP32 GPIO16 (UART2 RX)
 Sensor Brown (RX)  -> ESP32 GPIO17 (UART2 TX)
 Sensor White -> 3V3 (touch power)
 Sensor Green -> not connected

 Requires ESP32 board package + Adafruit Fingerprint Sensor Library.
 Aura ring: blue=ready, purple=enrolling, green=success, red=error.
 Added: three different buzzer sounds:
   - matching success: two ascending notes
   - recognition failure/error: two descending notes
   - completed enrollment: four-note celebratory tune
   - each of the 12 enrollment captures: a short confirmation chirp
 Buzzer wiring: ESP32 GPIO14 -> small 3.3V piezo positive,
                piezo negative -> ESP32 GND.
 Use a transistor driver if your buzzer exceeds safe GPIO drive current.
 For active buzzer, set PASSIVE_BUZZER=false below to use distinct beep patterns.
 Connect to SSID R503-Fingerprint and open http://192.168.4.1
 Default password ChangeMe123 — CHANGE BEFORE USING WITH STUDENTS.
*/

#include <WiFi.h>
#include <WebServer.h>
#include <SPI.h>
#include <SD.h>
#include <Wire.h>
#include <RTClib.h>
#include <ArduinoJson.h>
#include <Adafruit_GFX.h>
#include <Adafruit_ILI9341.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <time.h>
#include "config.h"
#include <Adafruit_Fingerprint.h>


// === ESP32 production hardware: UART2, shared SPI TFT+SD, UTC RTC ===
#define PIN_TFT_CS 27
#define PIN_TFT_DC 26
#define PIN_TFT_RST 25
#define PIN_SD_CS 13
#define PIN_TFT_BL 32
RTC_DS3231 rtc;
Adafruit_ILI9341 tft(PIN_TFT_CS,PIN_TFT_DC,PIN_TFT_RST);
SemaphoreHandle_t sdMutex;
bool sdOK=false,rtcOK=false;
String lastMark="READY";
uint32_t lastDisplay=0;
unsigned long lastCloudSync=0;
DynamicJsonDocument configDoc(65536);
int nextSeq=0;
String recordAttendance(uint16_t slot,uint16_t confidence);
void initHardware();
void updateDeviceUI();
void beginUploadWorker();

static bool lockSD(){return sdMutex && xSemaphoreTake(sdMutex,pdMS_TO_TICKS(1500))==pdTRUE;}
static void unlockSD(){xSemaphoreGive(sdMutex);}
static String localDay(uint32_t utc){
  DateTime local(utc+19800);char buf[11];snprintf(buf,sizeof(buf),"%04d-%02d-%02d",local.year(),local.month(),local.day());return String(buf);
}
static int localMinute(uint32_t utc){DateTime t(utc+19800);return t.hour()*60+t.minute();}
static int timeMinute(const String &s){if(s.length()!=5)return -1;return s.substring(0,2).toInt()*60+s.substring(3).toInt();}
static String findStudentGroup(int number){
 JsonArray students=configDoc["students"].as<JsonArray>();
 for(JsonObject s:students)if(s["fingerprint_number"].as<int>()==number)return s["group_name"].as<String>();
 return "";
}
static String findWindow(uint32_t utc,int number=0){
  if(configDoc.size()==0)return "NO_CONFIG";
  int nowm=localMinute(utc);String day=localDay(utc);String result="CLOSED";int active=0;
  JsonArray schedules=configDoc["schedules"].as<JsonArray>();
  for(JsonObject p:schedules){
    String from=p["opens"].as<String>(),until=p["deadline"].as<String>();
    if(from.isEmpty()||until.isEmpty())continue;
    int st=timeMinute(from),normal=timeMinute(until),closing=normal+p["late_minutes"].as<int>();
    if(st<=nowm&&nowm<=closing){active++;result=nowm<=normal?"PRESENT":"RED_MARK";}
  }
  JsonArray evs=configDoc["events"].as<JsonArray>();
  for(JsonObject e:evs){
    String d=e["event_date"].as<String>();if(d!=day)continue;
    String group=e["group_name"].as<String>();
    if(group.length() && (number==0 || group!=findStudentGroup(number)))continue;
    int st=timeMinute(e["opens"].as<String>()),normal=timeMinute(e["deadline"].as<String>()),closing=normal+e["late_minutes"].as<int>();
    if(st<=nowm&&nowm<=closing){active++;result=nowm<=normal?"PRESENT":"RED_MARK";}
  }
  return active>1?"MULTIPLE_OPEN_SESSIONS":active==0?"CLOSED":result;
}
static String matchStudentName(int number){
  JsonArray students=configDoc["students"].as<JsonArray>();
  for(JsonObject s:students)if(s["fingerprint_number"].as<int>()==number)return s["name"].as<String>();
  return "Student " + String(number);
}
static bool loadConfig(){
 if(!sdOK||!lockSD())return false;
 File f=SD.open("/config.json",FILE_READ);if(!f){unlockSD();return false;}
 DeserializationError err=deserializeJson(configDoc,f);f.close();unlockSD();return !err;
}
static String queueFile="/queue.jsonl";
String recordAttendance(uint16_t slot,uint16_t confidence){
  if(!rtcOK||rtc.lostPower())return "RTC NOT SET";
  uint32_t utc=rtc.now().unixtime();if(utc<1760000000UL)return "INVALID RTC";
  String phase=findWindow(utc,(slot+1)/2);if(phase!="PRESENT"&&phase!="RED_MARK")return phase;
  if(!sdOK)return "SD ERROR";
  StaticJsonDocument<300> doc;
  doc["event_id"]=String(SPA_DEVICE_ID)+"-"+String(utc)+"-"+String(slot)+"-"+String(++nextSeq);
  doc["slot"]=slot;doc["confidence"]=confidence;
  doc["at_ms"]=(uint64_t)utc*1000ULL;
  String line;serializeJson(doc,line);
  if(!lockSD())return "SD BUSY";
  File file=SD.open(queueFile,FILE_APPEND);
  if(!file){unlockSD();return "SD WRITE ERROR";}
  size_t n=file.println(line);file.flush();file.close();unlockSD();
  if(n<line.length()+1)return "SD WRITE INCOMPLETE";
  lastMark=matchStudentName((slot+1)/2)+" "+phase;
  return phase;
}
static void drawHeader(){
 tft.fillScreen(ILI9341_NAVY);tft.setTextColor(ILI9341_WHITE,ILI9341_NAVY);
 tft.setTextSize(2);tft.setCursor(14,14);tft.print("SMART PRAYER");
 tft.drawFastHLine(14,40,290,ILI9341_CYAN);
}
void updateDeviceUI(){
 if(millis()-lastDisplay<1000)return;lastDisplay=millis();
 if(!lockSD())return;
 drawHeader();tft.setTextSize(2);
 if(!rtcOK){tft.setTextColor(ILI9341_RED);tft.setCursor(12,65);tft.print("RTC unavailable");unlockSD();return;}
 uint32_t unixTime=rtc.now().unixtime();DateTime local(unixTime+19800);
 char str[24];snprintf(str,sizeof(str),"%02d:%02d:%02d",local.hour(),local.minute(),local.second());
 tft.setCursor(70,52);tft.setTextSize(3);tft.print(str);
 String phase=findWindow(unixTime);
 tft.setTextSize(2);tft.setCursor(14,110);
 tft.setTextColor(phase=="PRESENT"?ILI9341_GREEN:phase=="RED_MARK"?ILI9341_RED:ILI9341_YELLOW,ILI9341_NAVY);
 tft.print(phase.substring(0,24));
 tft.setTextColor(ILI9341_WHITE,ILI9341_NAVY);tft.setTextSize(1);
 tft.setCursor(12,152);tft.print(lastMark.substring(0,45));
 tft.setCursor(12,191);tft.print(sdOK?"SD READY":"SD ERROR");
 tft.setCursor(160,191);tft.print(WiFi.status()==WL_CONNECTED?"ONLINE":"OFFLINE");
 unlockSD();
}
void initHardware(){
  sdMutex=xSemaphoreCreateMutex();
  SPI.begin(18,19,23);
  pinMode(PIN_SD_CS,OUTPUT);digitalWrite(PIN_SD_CS,HIGH);
  pinMode(PIN_TFT_BL,OUTPUT);digitalWrite(PIN_TFT_BL,HIGH);
  tft.begin();tft.setRotation(1);drawHeader();
  sdOK=SD.begin(PIN_SD_CS,SPI,4000000);
  Wire.begin(21,22);rtcOK=rtc.begin();
  if(sdOK)loadConfig();
  if(rtcOK && rtc.lostPower())Serial.println("RTC battery/time lost. Connect Wi-Fi to sync NTP.");
}
static void syncRtcNTP(){
 if(!rtcOK||WiFi.status()!=WL_CONNECTED)return;
 configTime(0,0,"pool.ntp.org","time.google.com");
 time_t n=time(nullptr);if(n>1760000000)rtc.adjust(DateTime((uint32_t)n));
}
static bool cloudRequest(bool get,String endpoint,String body,String &response){
 if(WiFi.status()!=WL_CONNECTED || !strlen(SPA_API_URL) || !strlen(SPA_DEVICE_KEY) || !strlen(SPA_ROOT_CA))return false;
 WiFiClientSecure client;client.setCACert(SPA_ROOT_CA);
 HTTPClient http;String url=String(SPA_API_URL)+endpoint;
 if(!http.begin(client,url))return false;
 http.setTimeout(8000);http.addHeader("Authorization",String("Device ")+SPA_DEVICE_KEY);
 int status;
 if(get)status=http.GET();
 else {http.addHeader("Content-Type","application/json");status=http.POST(body);}
 if(status==200)response=http.getString();
 http.end();return status==200;
}
static void refreshConfig(){
 String body;if(!cloudRequest(true,"/api/v1/device/config","",body))return;
 DynamicJsonDocument fresh(65536);
 if(deserializeJson(fresh,body))return;
 if(!fresh["ok"].as<bool>())return;
 // Server wraps payload in {ok,data}; keep only the actual config.
 String compact;serializeJson(fresh["data"],compact);
 if(!sdOK||!lockSD())return;
 File out=SD.open("/config.tmp",FILE_WRITE);
 if(out){out.print(compact);out.flush();out.close();SD.remove("/config.json");SD.rename("/config.tmp","/config.json");}
 unlockSD();loadConfig();
}
static uint32_t readSyncPosition(){
 if(!lockSD())return 0;
 File f=SD.open("/sync.pos",FILE_READ);uint32_t val=f?(uint32_t)f.parseInt():0;if(f)f.close();unlockSD();return val;
}
static void storeSyncPosition(uint32_t pos){
 if(!lockSD())return;
 File f=SD.open("/sync.tmp",FILE_WRITE);
 if(f){f.print(pos);f.flush();f.close();SD.remove("/sync.pos");SD.rename("/sync.tmp","/sync.pos");}
 unlockSD();
}
static void uploadBatch(){
 if(!sdOK||WiFi.status()!=WL_CONNECTED)return;
 uint32_t start=readSyncPosition();
 if(!lockSD())return;
 File f=SD.open(queueFile,FILE_READ);
 if(!f){unlockSD();return;}
 if(start>(uint32_t)f.size())start=0;
 f.seek(start);
 DynamicJsonDocument batch(8192);JsonArray events=batch.createNestedArray("events");
 uint32_t next=start;
 while(f.available() && events.size()<20){
   String row=f.readStringUntil('\n');
   if(!row.endsWith("}"))break;
   StaticJsonDocument<400> obj;if(deserializeJson(obj,row))break;
   events.add(obj.as<JsonObject>());next=f.position();
 }
 f.close();unlockSD();
 if(events.isEmpty())return;
 String json,resp;serializeJson(batch,json);
 if(cloudRequest(false,"/api/v1/device/scans",json,resp)){
   DynamicJsonDocument ack(8192);
   if(deserializeJson(ack,resp)==DeserializationError::Ok && ack["ok"].as<bool>())storeSyncPosition(next);
 }
}
void uploadTask(void*){
 uint32_t lastPush=0,lastConfig=0,lastNTP=0;
 for(;;){
   if(WiFi.status()==WL_CONNECTED){
      uint32_t now=millis();
      if(now-lastNTP>60000UL || lastNTP==0){syncRtcNTP();lastNTP=now;}
      if(now-lastConfig>300000UL || lastConfig==0){refreshConfig();lastConfig=now;}
      if(now-lastPush>10000UL || lastPush==0){uploadBatch();lastPush=now;}
   }
   vTaskDelay(pdMS_TO_TICKS(500));
 }
}
void beginUploadWorker(){xTaskCreatePinnedToCore(uploadTask,"UploadWorker",10000,nullptr,1,nullptr,0);}
HardwareSerial fpSerial(2); // UART2: GPIO16 RX, GPIO17 TX
Adafruit_Fingerprint finger(&fpSerial);
WebServer server(80);

const char *AP_SSID = SPA_AP_SSID;
const char *AP_PASS = SPA_AP_PASSWORD;
const uint16_t MAX_STUDENTS = 300;
const uint8_t SCANS_PER_TEMPLATE = 6;
// R503Pro manual lists GREEN as Aura LED color index 0x04.
const uint8_t R503PRO_LED_GREEN = 0x04;

// -------- BUZZER CONFIGURATION --------
#define BUZZER_PIN 14  // GPIO5; does not conflict with fingerprint D5/D6.
const bool PASSIVE_BUZZER = true; // false for an ACTIVE buzzer (fixed pitch).

struct SoundNote { uint16_t hz; uint16_t onMs; uint16_t gapMs; };
const SoundNote SOUND_MATCH[] = {
  {1319, 95, 60}, {2093, 160, 0}   // Two cheerful ascending notes
};
const SoundNote SOUND_FAIL[] = {
  {523, 170, 70}, {330, 280, 0}    // Two low descending notes
};
const SoundNote SOUND_REGISTER[] = {
  {784, 85, 55}, {1047, 95, 55}, {1319, 105, 55}, {1760, 220, 0}
};
const SoundNote SOUND_CAPTURE[] = { {1850, 65, 0} };
const SoundNote SOUND_LATE[] = { {880,125,65},{740,180,0} }; // one chirp per completed capture
const SoundNote *soundNotes = nullptr;
uint8_t soundLength = 0;
uint8_t soundIndex = 0;
bool soundOnPhase = false;
unsigned long soundPhaseStart = 0;

void buzzerOff() {
  if (PASSIVE_BUZZER) noTone(BUZZER_PIN);
  else digitalWrite(BUZZER_PIN, LOW);
}

void beginSoundStep() {
  if (!soundNotes || soundIndex >= soundLength) {
    buzzerOff();
    soundNotes = nullptr;
    return;
  }
  soundOnPhase = true;
  soundPhaseStart = millis();
  if (PASSIVE_BUZZER) tone(BUZZER_PIN, soundNotes[soundIndex].hz);
  else digitalWrite(BUZZER_PIN, HIGH);
}

// Schedules a sound without blocking fingerprint scanning or the Wi-Fi server.
void playSound(uint8_t kind) {
  buzzerOff();
  soundNotes = nullptr;
  if (kind == 1) {
    soundNotes = SOUND_MATCH;
    soundLength = sizeof(SOUND_MATCH) / sizeof(SOUND_MATCH[0]);
  } else if (kind == 2) {
    soundNotes = SOUND_FAIL;
    soundLength = sizeof(SOUND_FAIL) / sizeof(SOUND_FAIL[0]);
  } else if (kind == 3) {
    soundNotes = SOUND_REGISTER;
    soundLength = sizeof(SOUND_REGISTER) / sizeof(SOUND_REGISTER[0]);
  } else if (kind == 5) {
    soundNotes = SOUND_LATE;
    soundLength = sizeof(SOUND_LATE) / sizeof(SOUND_LATE[0]);
  } else if (kind == 4) {
    soundNotes = SOUND_CAPTURE;
    soundLength = sizeof(SOUND_CAPTURE) / sizeof(SOUND_CAPTURE[0]);
  } else {
    return;
  }
  soundIndex = 0;
  beginSoundStep();
}

void updateSound() {
  if (!soundNotes) return;
  unsigned long elapsed = millis() - soundPhaseStart;
  if (soundOnPhase) {
    if (elapsed < soundNotes[soundIndex].onMs) return;
    buzzerOff();
    soundOnPhase = false;
    soundPhaseStart = millis();
  }
  if (!soundOnPhase && millis() - soundPhaseStart >= soundNotes[soundIndex].gapMs) {
    soundIndex++;
    beginSoundStep();
  }
}


bool sensorOK = false;
uint16_t sensorCapacity = 0;
uint16_t storedCount = 0;
uint16_t activeStudent = 1;
uint8_t activeTemplate = 1;   // 1 or 2
uint8_t completedInTemplate = 0; // 0..6, counted from sensor step replies
uint8_t templatesSaved = 0;
int matchedSlot = -1;
int matchedStudent = -1;
uint16_t matchConfidence = 0;
unsigned long searchMs = 0; // recognition processing time after capture progress
unsigned long firstCaptureAt = 0;
String statusText = "Starting...";
String statusStyle = "working";
enum State : uint8_t { ST_IDLE, ST_ENROLL_ACTIVE, ST_WAIT_LIFT, ST_IDENTIFY_ACTIVE, ST_WAIT_CLEAR, ST_CANCELING };
State state = ST_IDLE;
unsigned long stateStart = 0;
unsigned long lastPoll = 0;
// Packet parser for unsolicited multi-step 0x31/0x32 replies.
uint8_t rxPacket[40];
uint8_t rxPos = 0;
uint8_t rxExpected = 0;
unsigned long rxLastByte = 0;
const uint8_t MATCH_SECURITY_LEVEL = 3; // Accuracy baseline. Do not lower casually.

// Continuous recognition (rapid testing). The sensor is only re-armed after
// getImage() has reported NOFINGER twice, so a held finger is not counted twice.
bool rapidMode = false;
bool retryAfterCancel = false;
uint8_t noFingerSamples = 0;
uint16_t rapidMatches = 0;
uint16_t rapidFailures = 0;
uint8_t lastResultKind = 0; // 0=none,1=match,2=unknown,4=error

// RAM-only diagnostic log. Does not write flash on every scan.
// Export CSV before power-down; the oldest entry is overwritten when full.
const uint16_t MAX_LOGS = 350;
struct LogEntry {
  uint32_t sequence;
  uint32_t uptimeMs;
  uint16_t student;
  uint16_t slot;
  uint16_t score;
  uint16_t durationMs;
  uint8_t kind; // 1=match,2=unknown,3=enrolled,4=error
};
LogEntry logBook[MAX_LOGS];
uint16_t logHead = 0;
uint16_t logCount = 0;
uint32_t nextLogSequence = 0;

void appendLog(uint8_t kind, uint16_t student, uint16_t slot,
               uint16_t score, unsigned long durationMs) {
  uint16_t at;
  if (logCount < MAX_LOGS) {
    at = (logHead + logCount) % MAX_LOGS;
    logCount++;
  } else {
    at = logHead;
    logHead = (logHead + 1) % MAX_LOGS;
  }
  LogEntry &e = logBook[at];
  e.sequence = ++nextLogSequence;
  e.uptimeMs = millis();
  e.kind = kind;
  e.student = student;
  e.slot = slot;
  e.score = score;
  e.durationMs = durationMs > 65535UL ? 65535 : (uint16_t)durationMs;
}

// Only time since boot is available: no RTC is connected yet.
void handleLogs() {
  uint32_t after = 0;
  if (server.hasArg("after")) after = (uint32_t)server.arg("after").toInt();
  String result;
  result.reserve(4500);
  result = "{\"items\":[";
  uint8_t sent = 0;
  bool first = true;
  bool more = false;
  for (uint16_t i = 0; i < logCount; i++) {
    const LogEntry &e = logBook[(logHead + i) % MAX_LOGS];
    if (e.sequence <= after) continue;
    if (sent >= 40) { more = true; break; } // incremental pagination
    if (!first) result += ',';
    first = false;
    result += '[';
    result += String(e.sequence) + ',' + String(e.uptimeMs) + ',';
    result += String(e.kind) + ',' + String(e.student) + ',';
    result += String(e.slot) + ',' + String(e.score) + ',';
    result += String(e.durationMs) + ']';
    sent++;
  }
  result += "],\"more\":";
  result += more ? "true}" : "false}";
  server.sendHeader("Cache-Control", "no-store");
  server.send(200,"application/json",result);
}

void handleCsv() {
  server.sendHeader("Content-Disposition", "attachment; filename=R503Pro_rapid_test_log.csv");
  server.setContentLength(CONTENT_LENGTH_UNKNOWN);
  server.send(200,"text/csv","");
  server.sendContent("sequence,uptime_ms,result,student_id,template_slot,confidence,processing_ms\r\n");
  String chunk;
  chunk.reserve(800);
  for (uint16_t i = 0; i < logCount; i++) {
    const LogEntry &e = logBook[(logHead + i) % MAX_LOGS];
    const char *kind = e.kind==1 ? "MATCH" : e.kind==2 ? "UNKNOWN" :
                       e.kind==3 ? "ENROLLED" : "ERROR";
    chunk += String(e.sequence) + ',' + String(e.uptimeMs) + ',' + String(kind) + ',';
    chunk += String(e.student) + ',' + String(e.slot) + ',';
    chunk += String(e.score) + ',' + String(e.durationMs) + "\r\n";
    if (chunk.length() > 600) { server.sendContent(chunk); chunk = ""; }
  }
  if (chunk.length()) server.sendContent(chunk);
  server.sendContent("");
}
void clearLogs() {
  logHead = 0;
  logCount = 0;
  server.send(200,"text/plain","Log cleared. This only affects RAM logs.");
}

const char PAGE[] PROGMEM = R"HTML(
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>R503Pro — Live Fingerprint Console</title>
<style>
:root{font-family:system-ui,-apple-system,Segoe UI,Arial,sans-serif;color:#16283b;background:#eef3f9}
*{box-sizing:border-box}body{margin:0;padding:15px 11px}main{max-width:900px;margin:0 auto}
header{background:linear-gradient(110deg,#102d58,#145c99);color:#fff;border-radius:18px;padding:22px;margin-bottom:14px}
h1{font-size:24px;margin:0 0 6px}header p{margin:0;opacity:.83;font-size:13px}
.card{background:#fff;border:1px solid #dce6f0;border-radius:16px;margin:13px 0;padding:17px;box-shadow:0 5px 15px #233e5908}
h2{font-size:17px;margin:0 0 10px}.muted{font-size:12px;color:#536b80;line-height:1.6}
.stats{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px}.stat{background:#f0f5fa;border-radius:10px;padding:11px}
.stat span{font-size:11px;color:#526b84;display:block;margin-bottom:4px}.stat strong{font-size:19px}
button,.linkbtn{display:inline-flex;justify-content:center;align-items:center;min-height:44px;border:0;border-radius:10px;padding:10px 13px;background:#176eae;color:#fff;font:inherit;font-size:13px;font-weight:700;cursor:pointer;text-decoration:none}
button:disabled{opacity:.45;cursor:not-allowed}.green{background:#147751}.red{background:#b84250}.gray{background:#526c86}
.buttons{display:flex;flex-wrap:wrap;gap:9px;margin:11px 0}.buttons>*{flex:1 1 155px}
.live{background:#0e2237;color:#f1f7ff;border-radius:13px;padding:16px;display:flex;flex-wrap:wrap;align-items:center;gap:14px}
.scanicon{height:66px;width:66px;display:grid;place-items:center;font-size:42px;background:#193e5c;border-radius:13px}
.big{font-size:30px;font-weight:800;line-height:1.15}.hint{font-size:12px;color:#b9cde2;margin-top:3px}
.tag{display:inline-block;border-radius:999px;background:#22476e;padding:5px 10px;font-size:11px;font-weight:650}
.good{color:#56e7ab}.bad{color:#ff9e9e}.notice{padding:10px;border-radius:9px;background:#fff5dd;color:#795417;font-size:12px}
label{display:block;font-size:13px;font-weight:700;margin-bottom:6px}input{width:100%;max-width:230px;padding:11px;border:1px solid #bacbdc;border-radius:9px;font:inherit}
#status{white-space:pre-wrap;padding:13px;border-radius:10px;background:#e8f3ff;font-weight:650;color:#1b567a;line-height:1.6;min-height:61px}
#status.ok{background:#def7e9;color:#116447}#status.error{background:#ffe9e9;color:#952739}
.progress{height:9px;background:#e5eef6;border-radius:99px;overflow:hidden;margin:12px 0 7px}#bar{height:100%;width:0;background:#208bbe;transition:width .25s}
.smallrow{display:flex;gap:12px;justify-content:space-between;flex-wrap:wrap;font-size:12px;color:#5a7087}
.scroll{overflow:auto;max-height:365px;border:1px solid #dbe7f0;border-radius:11px}
table{border-collapse:collapse;width:100%;min-width:570px;font-size:12px}th,td{padding:10px 9px;text-align:left;border-bottom:1px solid #edf2f7}th{position:sticky;top:0;background:#f3f7fb;color:#47627d}
tr:first-child td{background:#f8fbff}.pill{border-radius:6px;padding:3px 7px;font-weight:700;font-size:11px}
.pill.match{background:#daf9e9;color:#146441}.pill.unknown,.pill.error{background:#ffe5e5;color:#9b2836}.pill.enrolled{background:#e4eafd;color:#284c9b}
footer{text-align:center;color:#61758a;font-size:11px;margin:19px}
@media(max-width:520px){body{padding:9px}header{padding:18px}h1{font-size:21px}.card{padding:13px}.stats .stat strong{font-size:16px}.big{font-size:26px}}
</style>
</head>
<body><main>
<header><h1>Fingerprint Attendance Console</h1><p>R503Pro · ESP32 · 300 Students · AutoEnroll 6+6 · AutoIdentify</p></header>
<section class="card"><div class="stats">
<div class="stat"><span>Sensor</span><strong id="connection">Checking</strong></div>
<div class="stat"><span>Stored templates</span><strong id="stored">–</strong></div>
<div class="stat"><span>Capacity</span><strong id="capacity">–</strong></div>
</div></section>
<section class="card" id="rapid-card">
<h2>⚡ Rapid recognition test</h2>
<p class="muted">Tap Start once. Each student touches the reader, lifts their finger, and the sensor automatically becomes ready for the next student. No webpage button needed between students.</p>
<div class="buttons"><button class="green" id="rapidStart" onclick="rapid('start')">▶ Start continuous scanning</button><button class="red" id="rapidStop" onclick="rapid('stop')" disabled>■ Stop</button></div>
<div class="live">
<div class="scanicon">◎</div>
<div style="flex:1;min-width:160px"><span id="rapidTag" class="tag">INACTIVE</span><div class="big" id="lastResult">Ready</div><div class="hint" id="lastDetail">Start continuous scanning to test multiple students.</div></div>
</div>
<div class="stats" style="margin-top:11px">
<div class="stat"><span>Matches this session</span><strong id="rMatch">0</strong></div>
<div class="stat"><span>Unrecognized</span><strong id="rFail">0</strong></div>
<div class="stat"><span>Total scans</span><strong id="rTotal">0</strong></div>
</div>
</section>
<section class="card">
<h2>Enrollment · 2 templates × 6 captures</h2>
<label for="student">Student number (1–300)</label><input id="student" type="number" min="1" max="300" value="1" inputmode="numeric">
<p class="muted">Same finger for all 12 captures. Lift between captures and vary placement slightly. A short chirp plays after each successful capture; the registration melody plays after both templates are saved.</p>
<div class="buttons"><button id="enroll" onclick="action('enroll')">AutoEnroll 6+6</button><button id="scan" class="gray" onclick="action('scan')">One-shot scan</button><button id="del" class="red" onclick="action('delete')">Delete two templates</button><button id="cancel" class="gray" onclick="action('cancel')" disabled>Cancel</button></div>
<div class="notice">Enrollment never overwrites occupied slots automatically. Delete both slots before registering the same student again. Template 2 allows the same finger to be stored twice.</div>
</section>
<section class="card">
<h2>Live sensor status</h2><div id="status">Connecting…</div>
<div class="progress"><div id="bar"></div></div>
<div class="smallrow"><span id="templateProgress">Template –</span><span id="scanProgress">0 / 12 scans</span></div>
</section>
<section class="card">
<h2>Live recognition log</h2><p class="muted">Most recent entries at the top. Time column is since ESP32 startup (not clock time). Process ms begins after the capture-progress packet. Up to 350 entries are held in RAM; export before restarting or power loss.</p>
<div class="buttons"><button onclick="downloadCsv()" class="gray">⬇ Export CSV</button><button class="gray" onclick="clearLog()">Clear log</button></div>
<div class="scroll"><table><thead><tr><th>Uptime</th><th>Result</th><th>Student ID</th><th>Slot</th><th>Score</th><th>Process (ms)</th></tr></thead><tbody id="logBody"><tr id="empty"><td colspan="6">No scans recorded yet.</td></tr></tbody></table></div>
</section>
<section class="card"><h2>Test buzzer sounds</h2><div class="buttons"><button class="gray" onclick="testSound('capture')">Scan chirp</button><button class="gray" onclick="testSound('success')">Success</button><button class="gray" onclick="testSound('fail')">Failure</button><button class="gray" onclick="testSound('register')">Registration melody</button></div></section>
<footer>Offline Wi-Fi: SmartPrayer-Setup · http://192.168.4.1 · Student logs are temporary test data</footer>
<script>
let busy=false,lastSeq=0,fetchingLog=false;
let adminKey='';
function getAdminKey(){
 if(!adminKey)adminKey=prompt('Enter the device administration key (for enrollment/deletion):')||'';
 return adminKey;
}
function adminHeaders(){return {'X-Device-Admin':getAdminKey()};}

const $=id=>document.getElementById(id);
function fmtTime(ms){let s=Math.floor(ms/1000);const h=Math.floor(s/3600);s%=3600;return h.toString().padStart(2,'0')+':'+Math.floor(s/60).toString().padStart(2,'0')+':'+(s%60).toString().padStart(2,'0')}
async function refresh(){
 try{
  const r=await fetch('/status',{cache:'no-store'}),s=await r.json();
  $('connection').textContent=s.connected?'Connected':'Offline';$('stored').textContent=s.stored;$('capacity').textContent=s.capacity||'–';
  const st=$('status');st.textContent=s.message;st.className=s.style;
  $('templateProgress').textContent='Template '+s.template+' / 2';$('scanProgress').textContent=s.completed+' / 12 captures';$('bar').style.width=(s.completed/12*100)+'%';
  $('rMatch').textContent=s.rapid_matches;$('rFail').textContent=s.rapid_failures;$('rTotal').textContent=s.rapid_matches+s.rapid_failures;
  $('rapidTag').textContent=s.rapid?'SCANNING ACTIVE':'INACTIVE';
  $('rapidTag').style.background=s.rapid?'#116e53':'#22476e';
  if(s.last_kind===1){$('lastResult').textContent='Student '+s.matched_student;$('lastResult').className='big good';$('lastDetail').textContent='Matched slot '+s.matched_slot+' · confidence '+s.confidence+' · '+s.search_ms+' ms after capture'}
  else if(s.last_kind===2){$('lastResult').textContent='Unknown finger';$('lastResult').className='big bad';$('lastDetail').textContent='No matching student. Remove finger and retry.'}
  else if(s.last_kind===4){$('lastResult').textContent='Scan error';$('lastResult').className='big bad';$('lastDetail').textContent='Check the sensor and try again.'}
  else{$('lastResult').textContent=s.rapid?'Waiting for finger…':'Ready';$('lastResult').className='big';$('lastDetail').textContent='Last recognized student will appear here.'}
  busy=s.busy;
  for(const n of ['enroll','scan','del'])$(n).disabled=busy||!s.connected||s.rapid;
  $('cancel').disabled=!busy||s.rapid;
  $('rapidStart').disabled=busy||!s.connected||s.rapid;
  $('rapidStop').disabled=!s.rapid;
 }catch(e){$('status').textContent='Disconnected from ESP32. Check Wi-Fi connection.'}
}
async function action(name){
 const id=$('student').value;
 if(name==='enroll'&&!confirm('Enroll student '+id+' using 12 captures? Both slots must be empty.'))return;
 if(name==='delete'&&!confirm('Delete both fingerprint slots for student '+id+'?'))return;
 try{const r=await fetch('/'+name,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded',...adminHeaders()},body:'student='+encodeURIComponent(id)});if(!r.ok)alert(await r.text())}catch(e){alert('Could not contact ESP32')}
 await refresh();
}
async function rapid(which){
 try{const r=await fetch('/rapid/'+which,{method:'POST',headers:adminHeaders()});if(!r.ok)alert(await r.text())}catch(e){alert('Could not contact ESP32')}
 await refresh();
}
async function testSound(type){try{const r=await fetch('/beep?type='+type,{method:'POST',headers:adminHeaders()});if(!r.ok)alert(await r.text())}catch(e){alert('Buzzer test connection error')}}
async function downloadCsv(){try{const r=await fetch('/logs.csv',{headers:adminHeaders()});if(!r.ok)throw Error(await r.text());const b=await r.blob(),url=URL.createObjectURL(b),a=document.createElement('a');a.href=url;a.download='fingerprint_log.csv';a.click();URL.revokeObjectURL(url);}catch(e){alert(e.message)}}
async function clearLog(){if(!confirm('Clear all temporary test logs?'))return;const r=await fetch('/logs/clear',{method:'POST',headers:adminHeaders()});if(r.ok){$('logBody').innerHTML='<tr id="empty"><td colspan="6">No scans recorded yet.</td></tr>';lastSeq=0;}};
const kinds={1:['MATCH','match'],2:['UNKNOWN','unknown'],3:['ENROLLED','enrolled'],4:['ERROR','error']};
async function refreshLog(){
 if(fetchingLog)return;fetchingLog=true;
 try{
  const r=await fetch('/logs?after='+lastSeq,{cache:'no-store'});if(!r.ok)return;
  const data=await r.json();
  for(const a of data.items){
   const [seq,uptime,kind,sid,slot,score,duration]=a;
   if(seq<=lastSeq)continue;lastSeq=seq;
   const empty=$('empty');if(empty)empty.remove();
   const tr=document.createElement('tr');const type=kinds[kind]||['ERROR','error'];
   const cells=[fmtTime(uptime),type[0],sid||'–',slot||'–',score||'–',duration];
   cells.forEach((v,i)=>{const td=document.createElement('td');if(i===1){const span=document.createElement('span');span.className='pill '+type[1];span.textContent=v;td.appendChild(span)}else td.textContent=v;tr.appendChild(td)});
   $('logBody').prepend(tr);
  }
  while($('logBody').rows.length>85)$('logBody').deleteRow(85);
  if(data.more)setTimeout(refreshLog,100);
 }catch(e){}finally{fetchingLog=false;}
}
refresh();refreshLog();setInterval(refresh,450);setInterval(refreshLog,950);
</script>
</main></body></html>
)HTML";


void say(const String &s) { statusText = s; Serial.println(s); }

// Never issue ordinary Adafruit commands while the sensor is running
// AutoEnroll or AutoIdentify. The sensor sends its own asynchronous replies.
void setRing(uint8_t color) {
  if (!sensorOK || state == ST_ENROLL_ACTIVE || state == ST_IDENTIFY_ACTIVE || state == ST_CANCELING) return;
  finger.LEDcontrol(FINGERPRINT_LED_ON, 0, color);
}

void finish(const String &message, const String &style) {
  state = ST_IDLE;
  statusStyle = style;
  say(message);
  if (style == "error") setRing(FINGERPRINT_LED_RED);
  else if (style == "ok") setRing(R503PRO_LED_GREEN);
  else setRing(FINGERPRINT_LED_BLUE);
}

void recount() {
  // Safe ONLY when native automatic commands are fully finished.
  if (sensorOK && state != ST_ENROLL_ACTIVE && state != ST_IDENTIFY_ACTIVE &&
      state != ST_CANCELING && finger.getTemplateCount() == FINGERPRINT_OK) {
    storedCount = finger.templateCount;
  }
}

// ----- Raw R503Pro UART protocol: EF 01, 4-byte address, PID, length,
// command, parameters, checksum (big-endian). Default address FFFFFFFF.
void discardOldPackets() {
  while (fpSerial.available()) fpSerial.read();
  rxPos = 0;
  rxExpected = 0;
}

void sendFP(uint8_t command, const uint8_t *params, uint8_t n) {
  uint16_t length = n + 3; // instruction + parameters + 2-byte checksum
  uint16_t sum = 0x01 + (length >> 8) + (length & 0xFF) + command;
  const uint8_t start[] = {0xEF,0x01,0xFF,0xFF,0xFF,0xFF,0x01};
  fpSerial.write(start, sizeof(start));
  fpSerial.write((uint8_t)(length >> 8));
  fpSerial.write((uint8_t)length);
  fpSerial.write(command);
  for (uint8_t i = 0; i < n; i++) {
    fpSerial.write(params[i]);
    sum += params[i];
  }
  fpSerial.write((uint8_t)(sum >> 8));
  fpSerial.write((uint8_t)sum);
}

uint16_t firstSlot(uint16_t student) { return (student - 1) * 2 + 1; }
uint16_t studentFromSlot(uint16_t slot) { return (slot + 1) / 2; }

bool validStudent(uint16_t &out) {
  if (!server.hasArg("student")) { server.send(400,"text/plain","Missing student ID."); return false; }
  String x = server.arg("student");
  if (x.length() < 1 || x.length() > 3) { server.send(400,"text/plain","Student must be 1..300."); return false; }
  for (size_t i = 0; i < x.length(); i++) {
    if (x[i] < '0' || x[i] > '9') { server.send(400,"text/plain","Invalid student ID."); return false; }
  }
  long n = x.toInt();
  if (n < 1 || n > MAX_STUDENTS) { server.send(400,"text/plain","Student must be 1..300."); return false; }
  out = (uint16_t)n;
  return true;
}

bool ready() {
  if (!sensorOK) { server.send(503,"text/plain","Sensor unavailable."); return false; }
  if (state != ST_IDLE || rapidMode) { server.send(409,"text/plain","Operation running. Stop continuous scanning or cancel first."); return false; }
  return true;
}

void sendStatus() {
  uint8_t done = templatesSaved * 6 + completedInTemplate;
  if (done > 12) done = 12;
  String json;
  json.reserve(400);
  json += "{\"connected\":" + String(sensorOK ? "true" : "false");
  json += ",\"busy\":" + String(state != ST_IDLE ? "true" : "false");
  json += ",\"rapid\":" + String(rapidMode ? "true" : "false");
  json += ",\"rapid_matches\":" + String(rapidMatches);
  json += ",\"rapid_failures\":" + String(rapidFailures);
  json += ",\"last_kind\":" + String(lastResultKind);
  json += ",\"stored\":" + String(storedCount);
  json += ",\"capacity\":" + String(sensorCapacity);
  json += ",\"template\":" + String(activeTemplate);
  json += ",\"completed\":" + String(done);
  json += ",\"matched_slot\":" + String(matchedSlot);
  json += ",\"matched_student\":" + String(matchedStudent);
  json += ",\"confidence\":" + String(matchConfidence);
  json += ",\"search_ms\":" + String(searchMs);
  json += ",\"style\":\"" + statusStyle + "\"";
  // statusText has no unescaped quoted strings or backslashes in our messages.
  json += ",\"message\":\"" + statusText + "\"}";
  server.sendHeader("Cache-Control","no-store");
  server.send(200,"application/json",json);
}

void startNativeEnroll(uint8_t templateNumber) {
  activeTemplate = templateNumber;
  completedInTemplate = 0;
  const uint16_t slot = firstSlot(activeStudent) + templateNumber - 1;
  // Cover existing ID: NO. Duplicate finger: NO for template 1,
  // YES for template 2 (must be same student/finger).
  // Return each step: YES. Require lifting between images: YES.
  const uint8_t params[] = {
    (uint8_t)(slot >> 8), (uint8_t)slot,
    0, (uint8_t)(templateNumber == 2 ? 1 : 0), 1, 1
  };
  discardOldPackets();
  state = ST_ENROLL_ACTIVE;
  stateStart = millis();
  statusStyle = "working";
  say("Template " + String(templateNumber) + "/2: place the SAME finger. Scan 1/6. Lift between captures.");
  sendFP(0x31, params, sizeof(params));
}

void doEnroll() {
  if (!ready()) return;
  uint16_t student;
  if (!validStudent(student)) return;
  uint16_t slot = firstSlot(student);
  if (sensorCapacity == 0 || slot + 1 >= sensorCapacity) {
    server.send(400,"text/plain","Sensor capacity unavailable or student slots out of range."); return;
  }
  // No automatic overwriting. Protect previously enrolled students.
  uint8_t first = finger.loadModel(slot);
  uint8_t second = finger.loadModel(slot + 1);
  if (first == FINGERPRINT_OK || second == FINGERPRINT_OK) {
    server.send(409,"text/plain","At least one slot is occupied. Use Delete for this student before enrolling again."); return;
  }
  if (first != 0x0C || second != 0x0C) {
    server.send(503,"text/plain","Cannot verify both empty slots (communication or sensor error). Check sensor before enrolling."); return;
  }
  activeStudent = student;
  templatesSaved = 0;
  lastResultKind = 0;
  matchedSlot = matchedStudent = -1;
  matchConfidence = 0;
  searchMs = 0;
  setRing(FINGERPRINT_LED_PURPLE);
  startNativeEnroll(1);
  server.send(200,"text/plain","AutoEnroll started: 6 + 6 scans.");
}

void startIdentifyCommand() {
  setRing(FINGERPRINT_LED_BLUE);
  uint16_t slotsToSearch = 600;
  if (sensorCapacity != 0 && sensorCapacity <= 601) slotsToSearch = sensorCapacity - 1;
  const uint8_t params[] = {
    MATCH_SECURITY_LEVEL,
    0, 1, (uint8_t)(slotsToSearch >> 8), (uint8_t)slotsToSearch,
    1, // return progress packets
    1  // one attempt per AutoIdentify command
  };
  discardOldPackets();
  state = ST_IDENTIFY_ACTIVE;
  stateStart = millis();
  firstCaptureAt = 0;
  statusStyle = "working";
  say(rapidMode ? "READY: Place a finger. Next student will scan automatically after removal."
                : "AutoIdentify: place finger on sensor.");
  sendFP(0x32, params, sizeof(params));
}

void waitForNextFinger(const String &message, const String &style) {
  if (!rapidMode) {
    finish(message, style);
    return;
  }
  state = ST_WAIT_CLEAR;
  stateStart = millis();
  lastPoll = 0;
  noFingerSamples = 0;
  statusStyle = style;
  say(message + " Remove finger for the next student.");
  if (style == "ok") setRing(R503PRO_LED_GREEN);
  else if (style == "error") setRing(FINGERPRINT_LED_RED);
}

void doScan() {
  if (!ready()) return;
  if (storedCount == 0) { server.send(409,"text/plain","No templates stored yet."); return; }
  rapidMode = false;
  lastResultKind = 0;
  matchedSlot = matchedStudent = -1;
  matchConfidence = 0;
  searchMs = 0;
  startIdentifyCommand();
  server.send(200,"text/plain","One-shot AutoIdentify started.");
}

void doRapidStart() {
  if (!ready()) return;
  if (storedCount == 0) { server.send(409,"text/plain","Enroll at least one fingerprint first."); return; }
  rapidMode = true;
  rapidMatches = rapidFailures = 0;
  lastResultKind = 0;
  matchedSlot = matchedStudent = -1;
  matchConfidence = 0;
  searchMs = 0;
  startIdentifyCommand();
  server.send(200,"text/plain","Continuous recognition started.");
}

void doRapidStop() {
  if (!rapidMode) { server.send(200,"text/plain","Continuous recognition already stopped."); return; }
  rapidMode = false;
  retryAfterCancel = false;
  if (state == ST_IDENTIFY_ACTIVE) {
    sendFP(0x30, nullptr, 0);
    state = ST_CANCELING;
    stateStart = millis();
    statusStyle = "working";
    say("Stopping continuous scanning; waiting for sensor cancellation acknowledgement...");
  } else {
    finish("Continuous scanning stopped. You can enroll or scan manually.","working");
  }
  server.send(200,"text/plain","Stop requested.");
}

void doDelete() {
  if (!ready()) return;
  uint16_t student;
  if (!validStudent(student)) return;
  uint16_t slot = firstSlot(student);
  // Manufacturer's delete command supports deleting a block of two slots.
  uint8_t firstDelete = finger.deleteModel(slot);
  uint8_t secondDelete = finger.deleteModel(slot + 1);
  recount();
  if (firstDelete == FINGERPRINT_OK && secondDelete == FINGERPRINT_OK) {
    finish("Student " + String(student) + ": both templates deleted.", "ok");
    server.send(200,"text/plain","Both templates deleted.");
  } else {
    finish("Delete incomplete: slot1 code " + String(firstDelete) + ", slot2 code " + String(secondDelete) + ".", "error");
    server.send(500,"text/plain",statusText);
  }
}

void doCancel() {
  if (state == ST_ENROLL_ACTIVE || state == ST_IDENTIFY_ACTIVE) {
    rapidMode = false;
    retryAfterCancel = false;
    // Send cancellation to the SENSOR, not just the webpage state.
    sendFP(0x30, nullptr, 0);
    state = ST_CANCELING;
    stateStart = millis();
    say("Cancel requested. Waiting for sensor acknowledgement...");
    server.send(200,"text/plain","Cancel requested.");
  } else if (state == ST_WAIT_CLEAR) {
    rapidMode = false;
    finish("Continuous scanning stopped.","working");
    server.send(200,"text/plain","Stopped.");
  } else if (state == ST_WAIT_LIFT) {
    finish("Enrollment cancelled. Template 1 remains saved; delete both slots to retry.", "error");
    server.send(200,"text/plain","Cancelled.");
  } else {
    server.send(200,"text/plain","No active operation.");
  }
}

void doBeep() {
  const String t = server.arg("type");
  if (t == "success") playSound(1);
  else if (t == "fail") playSound(2);
  else if (t == "register") playSound(3);
  else if (t == "capture") playSound(4);
  else { server.send(400,"text/plain","Unknown sound."); return; }
  server.send(200,"text/plain","Sound queued.");
}

void failOperation(uint8_t code, const String &operation) {
  String msg = operation + " failed. Sensor code 0x" + String(code, HEX) + ".";
  if (code == 0x27) msg += " Duplicate finger: same fingerprint exists in database.";
  if (code == 0x26) msg += " Timeout: lift and replace finger promptly.";
  if (code == 0xFC) msg += " Auto command unsupported by this firmware.";
  if (templatesSaved != 0 && operation == "AutoEnroll") msg += " Template 1 already stored. Delete both slots before retrying.";
  finish(msg,"error");
  playSound(2);
}

void onAutoAck(uint8_t code, uint8_t step, uint16_t id, uint16_t score) {
  if (state == ST_CANCELING) return;
  if (state == ST_ENROLL_ACTIVE) {
    if (code != 0) { failOperation(code,"AutoEnroll"); return; }
    if (step >= 1 && step <= 12) {
      if ((step & 1) == 0) {
        uint8_t count = step / 2;
        if (count > completedInTemplate) {
          completedInTemplate = count;
          // One short audible acknowledgement for EACH of all 12 scans.
          playSound(4);
        }
        say("Template " + String(activeTemplate) + "/2: " + String(completedInTemplate) + "/6 scans processed. Remove and reposition finger.");
      } else {
        uint8_t n = (step + 1) / 2;
        say("Template " + String(activeTemplate) + "/2: capturing scan " + String(n) + "/6...");
      }
    } else if (step == 13) say("Checking duplicate fingerprint...");
    else if (step == 14) say("Combining six captures into one template...");
    else if (step == 15) {
      uint16_t expected = firstSlot(activeStudent) + activeTemplate - 1;
      if (id != expected) { failOperation(0xFE,"AutoEnroll (wrong returned storage ID)"); return; }
      completedInTemplate = 6;
      templatesSaved++;
      state = ST_WAIT_LIFT;
      stateStart = millis();
      recount();
      if (activeTemplate == 1) {
        say("Template 1 SAVED in slot " + String(id) + ". REMOVE finger to begin template 2.");
      } else {
        appendLog(3, activeStudent, id, 0, 0);
        finish("SUCCESS: Student " + String(activeStudent) + " registered in slots " + String(firstSlot(activeStudent)) + " and " + String(id) + ".", "ok");
        playSound(3);
      }
    }
    return;
  }
  if (state != ST_IDENTIFY_ACTIVE) return;
  if (code != 0) {
    searchMs = millis() - (firstCaptureAt ? firstCaptureAt : stateStart);
    matchedSlot = matchedStudent = -1;
    matchConfidence = 0;
    if (code == 0x26) { // native command timed out waiting for a finger: no failure beep
      waitForNextFinger("No finger detected during this attempt.","working");
      return;
    }
    if (code == 0x09) {
      lastResultKind = 2;
      if (rapidMode) rapidFailures++;
      appendLog(2, 0, 0, 0, searchMs);
      waitForNextFinger("UNKNOWN fingerprint. No student matched.", "error");
      playSound(2);
    } else {
      lastResultKind = 4;
      if (rapidMode) rapidFailures++;
      appendLog(4, 0, 0, 0, searchMs);
      if (code == 0xFC) rapidMode = false; // unsupported command, don't retry indefinitely
      waitForNextFinger("AutoIdentify error code 0x" + String(code,HEX) + ".", "error");
      playSound(2);
    }
    return;
  }
  if (step == 1) { firstCaptureAt = millis(); say("Fingerprint captured; processing..."); }
  else if (step == 2) say("Fingerprint processed; searching templates...");
  else if (step == 3) {
    searchMs = millis() - (firstCaptureAt ? firstCaptureAt : stateStart);
    matchedSlot = id;
    matchConfidence = score;
    if (id >= 1 && id <= 600) {
      matchedStudent = studentFromSlot(id);
      lastResultKind = 1;
      if (rapidMode) rapidMatches++;
      appendLog(1, matchedStudent, id, score, searchMs);
      String mark=recordAttendance(id,score);
      if(mark=="PRESENT"){
        waitForNextFinger("PRESENT: Student " + String(matchedStudent) + " (slot " + String(id) + ").", "ok");
        playSound(1);
      }else if(mark=="RED_MARK"){
        waitForNextFinger("RED MARK: Student " + String(matchedStudent) + " (slot " + String(id) + ").", "error");
        playSound(5);
      }else{
        waitForNextFinger("NO ATTENDANCE SAVED: " + mark, "error");
        playSound(2);
      }
    } else {
      matchedStudent = -1;
      lastResultKind = 4;
      if (rapidMode) rapidFailures++;
      appendLog(4, 0, id, score, searchMs);
      waitForNextFinger("Sensor returned an unassigned slot " + String(id) + ".", "error");
      playSound(2);
    }
  }
}

// Parses validated R503Pro ACK packets without blocking HTTP processing.
// AutoEnroll ACK payload: confirmation, stage, ID high, ID low.
// AutoIdentify ACK payload: confirmation, stage, ID high, ID low, score high, score low.
void processRx() {
  if (rxPos && millis() - rxLastByte > 350) { rxPos = 0; rxExpected = 0; }
  uint8_t budget = 96;
  while (fpSerial.available() && budget--) {
    uint8_t b = (uint8_t)fpSerial.read();
    rxLastByte = millis();
    if (rxPos == 0 && b != 0xEF) continue;
    if (rxPos == 1 && b != 0x01) { rxPos = 0; continue; }
    rxPacket[rxPos++] = b;
    if (rxPos == 9) {
      uint16_t plen = ((uint16_t)rxPacket[7] << 8) | rxPacket[8];
      if (plen < 3 || ((size_t)9 + plen) > sizeof(rxPacket)) {
        rxPos = 0; rxExpected = 0; continue;
      }
      rxExpected = 9 + plen;
    }
    if (rxExpected && rxPos == rxExpected) {
      uint16_t sum = 0;
      for (uint8_t i = 6; i < rxExpected - 2; i++) sum += rxPacket[i];
      uint16_t got = ((uint16_t)rxPacket[rxExpected - 2] << 8) | rxPacket[rxExpected - 1];
      bool good = rxPacket[6] == 0x07 && sum == got;
      uint8_t payloadSize = rxExpected - 11;
      uint8_t code = rxPacket[9];
      uint8_t step = payloadSize >= 2 ? rxPacket[10] : 0;
      uint16_t id = payloadSize >= 4 ? (((uint16_t)rxPacket[11] << 8) | rxPacket[12]) : 0;
      uint16_t score = payloadSize >= 6 ? (((uint16_t)rxPacket[13] << 8) | rxPacket[14]) : 0;
      rxPos = 0; rxExpected = 0;
      if (!good) { Serial.println("UART checksum/packet error; ignored."); continue; }
      if (state == ST_CANCELING) {
        if (payloadSize == 1) {
          if (code == 0) {
            if (retryAfterCancel && rapidMode) {
              retryAfterCancel = false;
              state = ST_WAIT_CLEAR;
              stateStart = millis();
              noFingerSamples = 0;
              say("Recognition attempt expired; re-arming sensor...");
            } else {
              retryAfterCancel = false;
              finish("Sensor operation stopped. Existing templates remain saved.","working");
            }
          } else {
            rapidMode = false;
            retryAfterCancel = false;
            sensorOK = false;
            finish("Cancel rejected; restart sensor before continuing.","error");
          }
        }
      } else if (state == ST_ENROLL_ACTIVE && (payloadSize == 4 || (payloadSize == 1 && code != 0))) {
        onAutoAck(code, step, id, 0);
      } else if (state == ST_IDENTIFY_ACTIVE && (payloadSize == 6 || (payloadSize == 1 && code != 0))) {
        onAutoAck(code, step, id, score);
      }
    }
  }
}

void processState() {
  if (state == ST_ENROLL_ACTIVE) {
    if (millis() - stateStart > 90000UL) {
      // Don't send more commands until cancel acknowledged.
      sendFP(0x30, nullptr, 0);
      state = ST_CANCELING;
      stateStart = millis();
      say("Enrollment watchdog expired; cancelling sensor command.");
    }
  } else if (state == ST_IDENTIFY_ACTIVE) {
    if (millis() - stateStart > 17000UL) {
      retryAfterCancel = rapidMode;
      sendFP(0x30, nullptr, 0);
      state = ST_CANCELING;
      stateStart = millis();
      say("Recognition timed out; cancelling sensor command.");
    }
  } else if (state == ST_WAIT_CLEAR) {
    if (!rapidMode) {
      finish("Continuous recognition stopped.","working");
      return;
    }
    if (millis() - lastPoll < 85) return;
    lastPoll = millis();
    uint8_t p = finger.getImage();
    if (p == FINGERPRINT_NOFINGER) {
      if (++noFingerSamples >= 2) startIdentifyCommand();
    } else {
      noFingerSamples = 0;
      if (millis() - stateStart > 6500UL && (millis() - stateStart < 6700UL))
        say("Please lift your finger before the next student scans.");
    }
  } else if (state == ST_WAIT_LIFT && activeTemplate == 1) {
    if (millis() - lastPoll < 160) return;
    lastPoll = millis();
    uint8_t p = finger.getImage();
    if (p == FINGERPRINT_NOFINGER) {
      startNativeEnroll(2);
    } else if (millis() - stateStart > 20000UL) {
      finish("Remove finger timeout. Template 1 saved; delete both slots before retrying.","error");
      playSound(2);
    }
  } else if (state == ST_CANCELING && millis() - stateStart > 3000UL) {
    sensorOK = false;
    rapidMode = false;
    retryAfterCancel = false;
    finish("Cancel not acknowledged. Restart ESP32 and sensor before other commands.","error");
  }
}

bool adminAllowed(){
  if(strlen(SPA_ADMIN_KEY)<16){server.send(503,"text/plain","Configure a strong SPA_ADMIN_KEY first.");return false;}
  const String received=server.header("X-Device-Admin");
  if(received.length()!=strlen(SPA_ADMIN_KEY)){server.send(403,"text/plain","Staff device key required.");return false;}
  uint8_t mismatch=0;
  for(size_t i=0;i<received.length();i++)mismatch|=(received[i] ^ SPA_ADMIN_KEY[i]);
  if(mismatch){server.send(403,"text/plain","Incorrect device administration key.");return false;}
  return true;
}
void setup() {
  pinMode(BUZZER_PIN, OUTPUT);
  buzzerOff();
  Serial.begin(115200);
  delay(300);
  fpSerial.begin(57600,SERIAL_8N1,16,17);
  finger.begin(57600);
  sensorOK = finger.verifyPassword();
  if (sensorOK) {
    if (finger.getParameters() == FINGERPRINT_OK) sensorCapacity = finger.capacity;
    recount();
    finish("Sensor ready. Native AutoEnroll 6+6 / AutoIdentify enabled.","working");
  } else {
    finish("Sensor not found. Check ESP32 GPIO16/17 and 3.3V wiring.","error");
  }
  initHardware();
  WiFi.mode(WIFI_AP_STA);
  if(strlen(AP_PASS)<12){Serial.println("ERROR: Set a unique AP password of at least 12 characters");}
  else WiFi.softAP(AP_SSID,AP_PASS);
  if (strlen(SPA_WIFI_SSID)) WiFi.begin(SPA_WIFI_SSID,SPA_WIFI_PASSWORD);
  const char* headers[]={"X-Device-Admin"};server.collectHeaders(headers,1);
  server.on("/",HTTP_GET,[](){server.send_P(200,"text/html",PAGE);});
  server.on("/status",HTTP_GET,sendStatus);
  server.on("/enroll",HTTP_POST,[](){if(adminAllowed())doEnroll();});
  server.on("/scan",HTTP_POST,[](){if(adminAllowed())doScan();});
  server.on("/rapid/start",HTTP_POST,[](){if(adminAllowed())doRapidStart();});
  server.on("/rapid/stop",HTTP_POST,[](){if(adminAllowed())doRapidStop();});
  server.on("/logs",HTTP_GET,handleLogs);
  server.on("/logs.csv",HTTP_GET,[](){if(adminAllowed())handleCsv();});
  server.on("/logs/clear",HTTP_POST,[](){if(adminAllowed())clearLogs();});
  server.on("/delete",HTTP_POST,[](){if(adminAllowed())doDelete();});
  server.on("/cancel",HTTP_POST,[](){if(adminAllowed())doCancel();});
  server.on("/beep",HTTP_POST,[](){if(adminAllowed())doBeep();});
  server.begin();
  beginUploadWorker();
  Serial.println("SSID: " + String(AP_SSID));
  Serial.println("Open: http://" + WiFi.softAPIP().toString());
}
void loop() {
  server.handleClient();
  if (state == ST_ENROLL_ACTIVE || state == ST_IDENTIFY_ACTIVE || state == ST_CANCELING) processRx();
  processState();
  updateSound();
  updateDeviceUI();
  yield();
}

/*
 * ESP8266 智能灭蚊计数器
 * --------------------------------------
 * 硬件：NodeMCU + ACS712(5A) + USB电击灭蚊灯
 * 引脚：
 *   A0  -> ACS712 OUT
 *   D1  -> OLED SCL  (可选)
 *   D2  -> OLED SDA  (可选)
 *   D5  -> 蜂鸣器+   (可选)
 *   D6  -> 复位按钮  (可选, 内部上拉, 按下接GND)
 *
 * 功能：
 *   - WiFiManager 配网 (首次AP: MosquitoKiller-Setup)
 *   - 电流尖峰检测 -> 计数
 *   - 内置网页 / JSON API
 *   - 24小时统计 + 总数, 持久化到 LittleFS
 *   - 阈值在网页上可在线调节
 *
 * 依赖库:
 *   WiFiManager  (tzapu)
 *   ArduinoJson  (Benoit Blanchon)
 *   (可选) Adafruit_SSD1306 + Adafruit_GFX
 */

#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>
#include <WiFiManager.h>
#include <LittleFS.h>
#include <ArduinoJson.h>

// ===== 如果接了 OLED 把下面这行取消注释 =====
// #define USE_OLED

#ifdef USE_OLED
  #include <Wire.h>
  #include <Adafruit_GFX.h>
  #include <Adafruit_SSD1306.h>
  Adafruit_SSD1306 oled(128, 64, &Wire, -1);
#endif

// ============ 引脚 ============
const int PIN_ACS712  = A0;
const int PIN_BUZZER  = D5;
const int PIN_BUTTON  = D6;

// ============ 参数 ============
const uint16_t SAMPLE_INTERVAL_MS = 2;       // ADC 采样间隔
const uint16_t KILL_DEBOUNCE_MS   = 300;     // 同一击杀防抖
const uint16_t BASELINE_TC        = 2000;    // 基线低通时间常数 (越大越慢)
const char*    AP_NAME            = "MosquitoKiller-Setup";
const char*    DATA_FILE          = "/data.json";

// ============ 运行时变量 ============
ESP8266WebServer server(80);

uint32_t totalKills = 0;
uint32_t hourlyKills[24] = {0};   // 滑动 24 小时
int      currentHourSlot = 0;     // 0..23
uint32_t lastHourMillis  = 0;     // 上次轮转时间

float    baseline    = 512.0f;    // ADC 静态基线
int      lastDiff    = 0;         // 最新尖峰幅度（用于网页显示）
int      peakDiff    = 0;         // 最近一次峰值
uint32_t lastKillMs  = 0;
uint16_t killThreshold = 60;      // 默认阈值，可在网页调

uint32_t lastSaveMs  = 0;
uint32_t lastUiMs    = 0;

// ============ 持久化 ============
void saveData() {
  StaticJsonDocument<512> doc;
  doc["total"] = totalKills;
  doc["thr"]   = killThreshold;
  JsonArray arr = doc.createNestedArray("hourly");
  for (int i = 0; i < 24; i++) arr.add(hourlyKills[i]);
  doc["slot"] = currentHourSlot;

  File f = LittleFS.open(DATA_FILE, "w");
  if (!f) return;
  serializeJson(doc, f);
  f.close();
}

void loadData() {
  if (!LittleFS.exists(DATA_FILE)) return;
  File f = LittleFS.open(DATA_FILE, "r");
  if (!f) return;
  StaticJsonDocument<512> doc;
  if (deserializeJson(doc, f) == DeserializationError::Ok) {
    totalKills     = doc["total"]  | 0;
    killThreshold  = doc["thr"]    | 60;
    currentHourSlot= doc["slot"]   | 0;
    JsonArray arr = doc["hourly"];
    for (int i = 0; i < 24 && i < (int)arr.size(); i++) hourlyKills[i] = arr[i];
  }
  f.close();
}

// ============ OLED ============
void oledShow(const String& line1, const String& line2 = "", const String& line3 = "") {
#ifdef USE_OLED
  oled.clearDisplay();
  oled.setTextSize(1);
  oled.setTextColor(SSD1306_WHITE);
  oled.setCursor(0, 0);  oled.println(line1);
  oled.setCursor(0, 16); oled.println(line2);
  oled.setCursor(0, 32); oled.println(line3);
  oled.setTextSize(2);
  oled.setCursor(0, 48);
  oled.print("Kills:"); oled.print(totalKills);
  oled.display();
#endif
}

// ============ 内置网页 ============
const char INDEX_HTML[] PROGMEM = R"HTML(
<!doctype html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>灭蚊统计</title>
<style>
body{font-family:system-ui,-apple-system,Arial;margin:0;background:#0f1117;color:#e6e6e6;padding:16px}
h1{margin:0 0 12px;font-size:20px}
.card{background:#1a1d27;border-radius:12px;padding:16px;margin-bottom:12px;box-shadow:0 2px 8px #0006}
.big{font-size:48px;font-weight:700;color:#4ade80}
.row{display:flex;gap:12px;flex-wrap:wrap}
.row .card{flex:1;min-width:140px}
.bars{display:flex;align-items:flex-end;gap:2px;height:140px;margin-top:8px}
.bar{flex:1;background:#4ade80;border-radius:3px 3px 0 0;min-height:2px;position:relative}
.bar span{position:absolute;top:-18px;left:50%;transform:translateX(-50%);font-size:10px;color:#888}
.lbl{display:flex;gap:2px;font-size:10px;color:#888;margin-top:4px}
.lbl div{flex:1;text-align:center}
input[type=range]{width:100%}
button{background:#ef4444;color:white;border:0;padding:8px 16px;border-radius:8px;cursor:pointer;font-size:14px}
.live{font-family:monospace;color:#fbbf24}
</style></head><body>
<h1>🦟 ESP8266 灭蚊统计器</h1>

<div class="row">
  <div class="card"><div>总击杀</div><div class="big" id="total">0</div></div>
  <div class="card"><div>今日</div><div class="big" id="today">0</div></div>
</div>

<div class="card">
  <div>最近 24 小时</div>
  <div class="bars" id="bars"></div>
  <div class="lbl" id="lbls"></div>
</div>

<div class="card">
  <div>实时尖峰幅度: <span class="live" id="diff">0</span> / 阈值: <span id="thrv">0</span></div>
  <input type="range" id="thr" min="20" max="500" step="5">
  <div style="margin-top:8px"><button onclick="reset()">清零计数</button></div>
</div>

<div class="card" style="font-size:12px;color:#888">
  IP: <span id="ip"></span> · WiFi: <span id="ssid"></span><br>
  阈值调节：值越大越不灵敏。建议先观察"尖峰幅度"在击杀时的最大值，把阈值设为它的 60%。
</div>

<script>
async function fetchData(){
  try{
    const r = await fetch('/api/status');
    const d = await r.json();
    document.getElementById('total').textContent = d.total;
    document.getElementById('today').textContent = d.today;
    document.getElementById('diff').textContent = d.diff;
    document.getElementById('thrv').textContent = d.thr;
    document.getElementById('ip').textContent = d.ip;
    document.getElementById('ssid').textContent = d.ssid;
    if(!document.activeElement || document.activeElement.id !== 'thr')
      document.getElementById('thr').value = d.thr;

    const max = Math.max(1, ...d.hourly);
    const bars = document.getElementById('bars');
    const lbls = document.getElementById('lbls');
    bars.innerHTML=''; lbls.innerHTML='';
    for(let i=0;i<24;i++){
      const v = d.hourly[i];
      const b = document.createElement('div');
      b.className='bar';
      b.style.height = (v/max*100)+'%';
      if(v>0) b.innerHTML = '<span>'+v+'</span>';
      bars.appendChild(b);
      const l = document.createElement('div');
      l.textContent = (i%3===0)? (i-23+24)%24 : '';
      lbls.appendChild(l);
    }
  }catch(e){}
}
document.getElementById('thr').addEventListener('change', async (e)=>{
  await fetch('/api/threshold?v='+e.target.value);
  fetchData();
});
async function reset(){
  if(confirm('确定清零总数和24小时统计?')){
    await fetch('/api/reset',{method:'POST'});
    fetchData();
  }
}
fetchData();
setInterval(fetchData, 1000);
</script>
</body></html>
)HTML";

// ============ HTTP 处理 ============
void handleRoot() {
  server.send_P(200, "text/html", INDEX_HTML);
}

uint32_t todayKills() {
  // hourly 是滑动窗口，最近 24 小时全部加起来作为"今日"近似
  uint32_t s = 0;
  for (int i = 0; i < 24; i++) s += hourlyKills[i];
  return s;
}

void handleStatus() {
  StaticJsonDocument<768> doc;
  doc["total"] = totalKills;
  doc["today"] = todayKills();
  doc["diff"]  = lastDiff;
  doc["thr"]   = killThreshold;
  doc["ip"]    = WiFi.localIP().toString();
  doc["ssid"]  = WiFi.SSID();
  JsonArray arr = doc.createNestedArray("hourly");
  // 把当前小时槽放到末尾（最近的一格）
  for (int i = 0; i < 24; i++) {
    int idx = (currentHourSlot + 1 + i) % 24;
    arr.add(hourlyKills[idx]);
  }
  String out; serializeJson(doc, out);
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(200, "application/json", out);
}

void handleThreshold() {
  if (server.hasArg("v")) {
    int v = server.arg("v").toInt();
    if (v >= 5 && v <= 1000) {
      killThreshold = v;
      saveData();
    }
  }
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(200, "text/plain", String(killThreshold));
}

void handleReset() {
  totalKills = 0;
  for (int i = 0; i < 24; i++) hourlyKills[i] = 0;
  saveData();
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(200, "text/plain", "ok");
}

// ============ 击杀检测 ============
void detectKill() {
  static uint32_t lastSample = 0;
  uint32_t now = millis();
  if (now - lastSample < SAMPLE_INTERVAL_MS) return;
  lastSample = now;

  int v = analogRead(PIN_ACS712);
  // 慢速更新基线
  baseline = baseline + (v - baseline) / (float)BASELINE_TC;
  int diff = abs(v - (int)baseline);
  lastDiff = diff;

  if (diff > killThreshold && (now - lastKillMs) > KILL_DEBOUNCE_MS) {
    lastKillMs = now;
    totalKills++;
    hourlyKills[currentHourSlot]++;
    peakDiff = diff;

    // 提示
    digitalWrite(PIN_BUZZER, HIGH);
    delay(30);
    digitalWrite(PIN_BUZZER, LOW);

    Serial.printf("[KILL] #%u  diff=%d  thr=%u\n", totalKills, diff, killThreshold);
  }
}

// ============ 小时轮转 ============
void rotateHourIfNeeded() {
  uint32_t now = millis();
  if (now - lastHourMillis >= 3600000UL) {     // 1 小时
    lastHourMillis = now;
    currentHourSlot = (currentHourSlot + 1) % 24;
    hourlyKills[currentHourSlot] = 0;          // 清空新一小时槽
    saveData();
  }
}

// ============ 按钮 ============
void handleButton() {
  static uint32_t pressStart = 0;
  static bool wasPressed = false;
  bool pressed = (digitalRead(PIN_BUTTON) == LOW);
  if (pressed && !wasPressed) { pressStart = millis(); wasPressed = true; }
  if (!pressed && wasPressed) {
    uint32_t held = millis() - pressStart;
    wasPressed = false;
    if (held > 50 && held < 1500) {
      // 短按：清零
      totalKills = 0;
      for (int i = 0; i < 24; i++) hourlyKills[i] = 0;
      saveData();
      Serial.println("[BTN] reset counts");
    } else if (held >= 3000) {
      // 长按 3s：重置 WiFi 配置
      Serial.println("[BTN] reset WiFi config");
      WiFiManager wm; wm.resetSettings();
      ESP.restart();
    }
  }
}

// ============ setup / loop ============
void setup() {
  Serial.begin(115200);
  Serial.println("\n[Boot] mosquito killer");

  pinMode(PIN_BUZZER, OUTPUT);
  pinMode(PIN_BUTTON, INPUT_PULLUP);
  digitalWrite(PIN_BUZZER, LOW);

#ifdef USE_OLED
  Wire.begin(D2, D1);
  if (oled.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {
    oled.clearDisplay(); oled.display();
    oledShow("Booting...");
  }
#endif

  if (!LittleFS.begin()) {
    Serial.println("LittleFS mount failed, formatting...");
    LittleFS.format(); LittleFS.begin();
  }
  loadData();

  // 校准基线
  uint32_t acc = 0;
  for (int i = 0; i < 200; i++) { acc += analogRead(PIN_ACS712); delay(2); }
  baseline = acc / 200.0f;
  Serial.printf("[Cal] baseline = %.1f\n", baseline);

  // WiFi 配网
  oledShow("WiFi config...", "AP:", AP_NAME);
  WiFiManager wm;
  wm.setConfigPortalTimeout(180);
  if (!wm.autoConnect(AP_NAME)) {
    Serial.println("WiFi failed, restart");
    ESP.restart();
  }
  Serial.print("[WiFi] IP="); Serial.println(WiFi.localIP());
  oledShow("WiFi OK", WiFi.SSID(), WiFi.localIP().toString());

  // HTTP
  server.on("/",              handleRoot);
  server.on("/api/status",    handleStatus);
  server.on("/api/threshold", handleThreshold);
  server.on("/api/reset", HTTP_POST, handleReset);
  server.on("/api/reset", HTTP_GET,  handleReset);
  server.begin();

  lastHourMillis = millis();
  Serial.println("[Ready]");
}

void loop() {
  server.handleClient();
  detectKill();
  rotateHourIfNeeded();
  handleButton();

  uint32_t now = millis();
  if (now - lastSaveMs > 60000) {       // 每分钟自动保存
    lastSaveMs = now;
    saveData();
  }
  if (now - lastUiMs > 1000) {
    lastUiMs = now;
    oledShow("WiFi:" + WiFi.SSID(), WiFi.localIP().toString(),
             "Diff:" + String(lastDiff) + " Thr:" + String(killThreshold));
  }
}

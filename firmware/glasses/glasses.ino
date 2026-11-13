/*
  Glasses firmware: Seeed XIAO ESP32S3 Sense + Adafruit 1.28" round GC9A01A display.

  Joins your phone's hotspot, connects to the Glasses Assistant app (ws://<phone>:8765), and then:
    - shows the assistant's text, in the current skin's colors, laid out for the round screen
    - shows pictures the phone streams (now playing, your phone screen, longer text), with text on top
    - answers "capture" with a photo from the camera
    - sends button presses: short = talk, double = switch display, long = hush and clear

  First run (or hold the button for a second while it says "Hold the button now"): it makes a Wi-Fi network called "Glasses-Setup".
  Join it from your phone, open http://192.168.4.1 and enter your hotspot name, password and the
  link token shown in the app.

  Display wiring (7 wires): Vin->3V3, GND->GND, SCK->D8, MOSI->D10, TCS->D7, DC->D4, RST->D5.
  Button: the XIAO's own BOOT button, or a button from D1 to GND.

  Board: "XIAO_ESP32S3", PSRAM: "OPI PSRAM". Libraries: Adafruit GC9A01A, Adafruit GFX,
  WebSockets (Markus Sattler), TJpg_Decoder (Bodmer), ArduinoJson.
*/
#include <WiFi.h>
#include <WebServer.h>
#include <DNSServer.h>
#include <Preferences.h>
#include <SPI.h>
#include <Adafruit_GFX.h>
#include <Adafruit_GC9A01A.h>
#include <Fonts/FreeSans9pt7b.h>
#include <WebSocketsClient.h>
#include <ArduinoJson.h>
#include <TJpg_Decoder.h>
#include "esp_camera.h"

#define FW_VERSION "1.0"
#define LINK_PORT 8765
#define SIZE 240

// ---------------------------------------------------------------- pins
#define TFT_CS  D7
#define TFT_DC  D4
#define TFT_RST D5
#define BUTTON_BOOT 0
#define BUTTON_EXT  D1

// XIAO ESP32S3 Sense camera
#define CAM_XCLK 10
#define CAM_SIOD 40
#define CAM_SIOC 39
#define CAM_Y9 48
#define CAM_Y8 11
#define CAM_Y7 12
#define CAM_Y6 14
#define CAM_Y5 16
#define CAM_Y4 18
#define CAM_Y3 17
#define CAM_Y2 15
#define CAM_VSYNC 38
#define CAM_HREF 47
#define CAM_PCLK 13

Adafruit_GC9A01A tft(TFT_CS, TFT_DC, TFT_RST);
GFXcanvas16 *screen = nullptr;   // what goes to the display, composed here first (no flicker)
GFXcanvas16 *frame = nullptr;    // the last picture streamed from the phone
WebSocketsClient ws;
Preferences prefs;

String ssid, pass, token, phoneIp;
bool cameraOk = false;
String cameraModel;              // e.g. OV2640, OV3660
String cameraError;              // why it didn't start, in words
bool linked = false;
bool streaming = false;          // showing streamed pictures instead of the plain screen

// Skin colors (RGB565), sent by the phone.
uint16_t cAccent = 0x3DB8, cBg = 0x08A2, cText = 0xE77D;

// Overlay text from the assistant.
String overlayText;
String overlayPos = "bottom";
uint32_t overlayUntil = 0;
String statusLine = "Starting";

// For the virtual display page (works with or without the real display attached).
uint32_t screenVersion = 0;      // goes up every time the picture changes
uint32_t framesIn = 0;           // streamed pictures received from the phone
uint32_t presses = 0;
String linkHost;
String skinName = "default";

// ---------------------------------------------------------------- drawing

/** Width of the circle available to a line of text whose middle is at y. */
int chordAt(int y, int margin) {
  int r = SIZE / 2 - margin;
  int dy = y - SIZE / 2;
  if (dy * dy >= r * r) return 0;
  return 2 * (int)sqrtf((float)(r * r - dy * dy));
}

int textWidth(GFXcanvas16 *c, const String &s) {
  int16_t x1, y1; uint16_t w, h;
  c->getTextBounds(s.c_str(), 0, 0, &x1, &y1, &w, &h);
  return w;
}

/** Word-wraps text into lines that fit the circle, starting at y0, line height lh. */
int layoutLines(GFXcanvas16 *c, const String &text, int y0, int lh, int maxLines, String *out) {
  int n = 0, i = 0, len = text.length();
  while (i < len && n < maxLines) {
    int y = y0 + n * lh;
    int w = chordAt(y - lh / 3, 16);
    String line;
    while (i < len) {
      int sp = text.indexOf(' ', i);
      if (sp < 0) sp = len;
      String word = text.substring(i, sp);
      String next = line.length() ? line + " " + word : word;
      if (line.length() && textWidth(c, next) > w) break;
      line = next;
      i = sp + 1;
    }
    if (n == maxLines - 1 && i < len) line += "...";
    out[n++] = line;
  }
  return n;
}

/** Draws text in the circle: top, bottom or center. On a streamed picture it gets a backing panel. */
void drawText(GFXcanvas16 *c, const String &text, const String &pos, bool panel) {
  const int lh = 20, maxLines = 7;
  String lines[maxLines];
  c->setFont(&FreeSans9pt7b);
  // Lay out once to count the lines, then place the block and lay out again at its real height.
  int count = layoutLines(c, text, pos == "top" ? 52 : 120, lh, maxLines, lines);
  int y0;
  if (pos == "top") y0 = 52;
  else if (pos == "center") y0 = SIZE / 2 - (count * lh) / 2 + 14;
  else y0 = SIZE - 40 - (count - 1) * lh;
  count = layoutLines(c, text, y0, lh, maxLines, lines);
  if (panel && count) {
    int top = y0 - 16, bottom = y0 + (count - 1) * lh + 8;
    int widest = 0;
    for (int k = 0; k < count; k++) widest = max(widest, textWidth(c, lines[k]));
    c->fillRoundRect(SIZE / 2 - widest / 2 - 10, top, widest + 20, bottom - top, 10, cBg);
  }
  c->setTextColor(cText);
  for (int k = 0; k < count; k++) {
    int w = textWidth(c, lines[k]);
    c->setCursor(SIZE / 2 - w / 2, y0 + k * lh);
    c->print(lines[k]);
  }
}

void ring(GFXcanvas16 *c, uint16_t color) {
  for (int r = SIZE / 2 - 1; r > SIZE / 2 - 4; r--) c->drawCircle(SIZE / 2, SIZE / 2, r, color);
}

/** Builds the whole picture, then sends it to the display in one go. */
void compose() {
  bool overlay = overlayText.length() && millis() < overlayUntil;
  if (streaming) {
    memcpy(screen->getBuffer(), frame->getBuffer(), SIZE * SIZE * 2);
    if (overlay) drawText(screen, overlayText, overlayPos == "top" ? "top" : "bottom", true);
  } else {
    screen->fillScreen(cBg);
    ring(screen, cAccent);
    if (overlay) {
      drawText(screen, overlayText, overlayPos == "top" ? "top" : "center", false);
    } else if (!linked) {
      drawText(screen, statusLine, "center", false);
    } else {
      screen->fillCircle(SIZE / 2, SIZE / 2, 5, cAccent);   // ready
    }
  }
  tft.drawRGBBitmap(0, 0, screen->getBuffer(), SIZE, SIZE);
  screenVersion++;
}

void setStatus(const String &s) {
  statusLine = s;
  Serial.println(s);
  compose();
}

// JPEG blocks land in the frame buffer.
bool onJpegBlock(int16_t x, int16_t y, uint16_t w, uint16_t h, uint16_t *bitmap) {
  uint16_t *buf = frame->getBuffer();
  for (int row = 0; row < h; row++) {
    int yy = y + row;
    if (yy < 0 || yy >= SIZE) continue;
    for (int col = 0; col < w; col++) {
      int xx = x + col;
      if (xx >= 0 && xx < SIZE) buf[yy * SIZE + xx] = bitmap[row * w + col];
    }
  }
  return true;
}

/** sendTXT only takes a String by reference, so send temporary Strings through here. */
void sendText(const String &s) { ws.sendTXT(s.c_str()); }

// ---------------------------------------------------------------- camera

/** Plain-words reason for a camera failure. */
String cameraHelp(esp_err_t e) {
  String name = esp_err_to_name(e);
  if (e == ESP_ERR_NOT_FOUND || e == 0x105 || e == ESP_FAIL)
    return name + ": no camera answered. Check the camera's ribbon is clicked fully into the Sense board, "
                  "and the Sense board is pressed all the way onto the XIAO.";
  if (e == ESP_ERR_NO_MEM)
    return name + ": out of memory. PSRAM may be off in this build.";
  if (e == ESP_ERR_NOT_SUPPORTED)
    return name + ": this camera model isn't supported by the firmware.";
  return name;
}

bool tryCamera(int xclkHz, framesize_t size) {
  camera_config_t c = {};
  c.ledc_channel = LEDC_CHANNEL_0;
  c.ledc_timer = LEDC_TIMER_0;
  c.pin_d0 = CAM_Y2; c.pin_d1 = CAM_Y3; c.pin_d2 = CAM_Y4; c.pin_d3 = CAM_Y5;
  c.pin_d4 = CAM_Y6; c.pin_d5 = CAM_Y7; c.pin_d6 = CAM_Y8; c.pin_d7 = CAM_Y9;
  c.pin_xclk = CAM_XCLK; c.pin_pclk = CAM_PCLK; c.pin_vsync = CAM_VSYNC; c.pin_href = CAM_HREF;
  c.pin_sccb_sda = CAM_SIOD; c.pin_sccb_scl = CAM_SIOC;
  c.pin_pwdn = -1; c.pin_reset = -1;
  c.xclk_freq_hz = xclkHz;
  c.pixel_format = PIXFORMAT_JPEG;
  c.frame_size = size;
  c.jpeg_quality = 12;
  c.fb_count = 2;
  c.fb_location = CAMERA_FB_IN_PSRAM;
  c.grab_mode = CAMERA_GRAB_LATEST;
  esp_err_t e = esp_camera_init(&c);
  if (e != ESP_OK) {
    cameraError = cameraHelp(e);
    Serial.printf("Camera start failed at %d MHz: %s\n", xclkHz / 1000000, cameraError.c_str());
    esp_camera_deinit();
    return false;
  }
  sensor_t *sensor = esp_camera_sensor_get();
  if (sensor) {
    camera_sensor_info_t *info = esp_camera_sensor_get_info(&sensor->id);
    cameraModel = info ? info->name : String("PID 0x") + String(sensor->id.PID, HEX);
  }
  // Prove it with one real picture.
  camera_fb_t *fb = esp_camera_fb_get();
  if (!fb) {
    cameraError = "The camera started but gave no picture. Reseat its ribbon.";
    Serial.println(cameraError);
    esp_camera_deinit();
    return false;
  }
  esp_camera_fb_return(fb);
  cameraError = "";
  return true;
}

void startCamera() {
  if (cameraOk) { esp_camera_deinit(); cameraOk = false; }
  cameraModel = "";
  // Normal settings first, then a slower clock that some modules and long ribbons need.
  cameraOk = tryCamera(20000000, FRAMESIZE_VGA) || tryCamera(10000000, FRAMESIZE_VGA);
  Serial.println(cameraOk ? "Camera ready: " + cameraModel : "Camera failed: " + cameraError);
}

void capture(int id) {
  if (!cameraOk) {
    JsonDocument d;
    d["type"] = "capture_failed"; d["id"] = id; d["reason"] = "the glasses camera didn't start: " + cameraError;
    String out; serializeJson(d, out);
    sendText(out);
    return;
  }
  // The first frame can be stale; grab twice.
  camera_fb_t *fb = esp_camera_fb_get();
  if (fb) esp_camera_fb_return(fb);
  fb = esp_camera_fb_get();
  if (!fb) {
    sendText(String("{\"type\":\"capture_failed\",\"id\":") + id + ",\"reason\":\"no frame\"}");
    return;
  }
  ws.sendBIN(fb->buf, fb->len);
  esp_camera_fb_return(fb);
}

// ---------------------------------------------------------------- link

void onMessage(uint8_t *payload, size_t len) {
  JsonDocument doc;
  if (deserializeJson(doc, payload, len)) return;
  const char *type = doc["type"] | "";
  if (!strcmp(type, "overlay")) {
    overlayText = (const char *)(doc["text"] | "");
    overlayPos = (const char *)(doc["pos"] | "bottom");
    overlayUntil = millis() + (uint32_t)(doc["ttl_ms"] | 6000);
    compose();
  } else if (!strcmp(type, "clear")) {
    overlayText = "";
    compose();
  } else if (!strcmp(type, "skin")) {
    cAccent = doc["accent"] | cAccent;
    cBg = doc["bg"] | cBg;
    cText = doc["text"] | cText;
    skinName = (const char *)(doc["name"] | "default");
    compose();
  } else if (!strcmp(type, "stream_end")) {
    streaming = false;
    compose();
  } else if (!strcmp(type, "capture")) {
    capture(doc["id"] | 0);
  }
}

void onWs(WStype_t type, uint8_t *payload, size_t len) {
  switch (type) {
    case WStype_CONNECTED: {
      linked = true;
      Serial.println("Reached the app, sending token " + token);
      sendText(String("{\"type\":\"hello\",\"token\":\"") + token +
                 "\",\"device\":\"xiao-glasses\",\"fw\":\"" FW_VERSION "\",\"ip\":\"" +
                 WiFi.localIP().toString() + "\"}");
      compose();
      break;
    }
    case WStype_DISCONNECTED:
      if (linked) Serial.println("The app closed the link. If this repeats right after \"sending token\", the token is wrong.");
      if (linked) { linked = false; streaming = false; setStatus("Looking for the app..."); }
      break;
    case WStype_TEXT:
      onMessage(payload, len);
      break;
    case WStype_BIN:
      // A streamed picture: decode, draw, then tell the phone it can send the next one.
      if (TJpgDec.drawJpg(0, 0, payload, len) == JDR_OK) {
        streaming = true;
        framesIn++;
        compose();
      }
      ws.sendTXT("{\"type\":\"frame_ack\"}");
      break;
    default:
      break;
  }
}

// ---------------------------------------------------------------- button

uint32_t downAt = 0, lastUp = 0;
bool wasDown = false, longSent = false, waitingDouble = false;

bool buttonDown() { return digitalRead(BUTTON_BOOT) == LOW || digitalRead(BUTTON_EXT) == LOW; }

void sendPress(const char *kind) {
  if (!linked) return;
  presses++;
  sendText(String("{\"type\":\"button\",\"press\":\"") + kind + "\"}");
}

void pollButton() {
  bool down = buttonDown();
  uint32_t now = millis();
  if (down && !wasDown) { downAt = now; longSent = false; }
  if (down && !longSent && now - downAt > 800) { longSent = true; waitingDouble = false; sendPress("long"); }
  if (!down && wasDown && !longSent && now - downAt > 30) {
    if (waitingDouble && now - lastUp < 350) { waitingDouble = false; sendPress("double"); }
    else { waitingDouble = true; lastUp = now; }
  }
  if (waitingDouble && !down && now - lastUp >= 350) { waitingDouble = false; sendPress("short"); }
  wasDown = down;
}

// ---------------------------------------------------------------- setup portal

WebServer web(80);
DNSServer dns;

const char PAGE[] PROGMEM = R"HTML(<!doctype html><meta name=viewport content="width=device-width">
<title>Glasses setup</title><style>body{font:16px system-ui;margin:24px;max-width:420px}input{width:100%;
padding:10px;margin:4px 0 14px;font-size:16px;box-sizing:border-box}button{padding:12px 20px;font-size:16px}</style>
<h2>Glasses setup</h2><p>Your phone's hotspot must be 2.4 GHz (turn on "Extend compatibility" if it has it). Turn on your phone's hotspot, then enter its details. The token is shown in the
Glasses Assistant app, on the main screen next to "Link".</p>
<form method=post action=/save>Hotspot name<input name=ssid value="%SSID%">Hotspot password
<input name=pass type=password>Link token<input name=token value="%TOKEN%" autocapitalize=off autocorrect=off spellcheck=false>Phone address
(leave empty on a hotspot)<input name=phone value="%PHONE%"><button>Save and restart</button></form>)HTML";

void portal() {
  WiFi.mode(WIFI_AP);
  WiFi.softAP("Glasses-Setup");
  dns.start(53, "*", WiFi.softAPIP());
  statusLine = "Join Wi-Fi Glasses-Setup, open 192.168.4.1";
  linked = false;
  compose();
  web.on("/", []() {
    String p = FPSTR(PAGE);
    p.replace("%SSID%", ssid); p.replace("%TOKEN%", token); p.replace("%PHONE%", phoneIp);
    web.send(200, "text/html", p);
  });
  web.on("/save", HTTP_POST, []() {
    prefs.putString("ssid", web.arg("ssid"));
    if (web.arg("pass").length()) prefs.putString("pass", web.arg("pass"));
    String t = web.arg("token"); t.trim(); t.toLowerCase();
    prefs.putString("token", t);
    prefs.putString("phone", web.arg("phone"));
    web.send(200, "text/html", "<meta name=viewport content='width=device-width'><h2>Saved.</h2>"
                               "<p>The glasses restart and join your hotspot. Reconnect your phone to its usual Wi-Fi.</p>");
    delay(1500);
    ESP.restart();
  });
  web.onNotFound([]() { web.sendHeader("Location", "http://192.168.4.1/"); web.send(302); });
  web.begin();
  while (true) { dns.processNextRequest(); web.handleClient(); delay(5); }
}

// ---------------------------------------------------------------- virtual display

/*
  Open http://<glasses address>/ on the phone (the app shows the address once the glasses connect).
  It shows exactly what the round display shows, has the button on screen, and can take a photo,
  so everything can be tested with just the XIAO.
*/
const char VIRTUAL[] PROGMEM = R"HTML(<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>Glasses</title><style>
body{margin:0;background:#0d0f12;color:#e8eaed;font:15px system-ui;display:flex;flex-direction:column;align-items:center;padding:28px 16px;gap:18px}
.lens{width:min(84vw,360px);aspect-ratio:1;border-radius:50%;overflow:hidden;box-shadow:0 0 0 10px #22262c,0 0 0 12px #3a3f47}
canvas{width:100%;height:100%;image-rendering:auto}
.row{display:flex;gap:8px;flex-wrap:wrap;justify-content:center}
button{background:#22262c;color:#e8eaed;border:1px solid #3a3f47;border-radius:22px;padding:12px 16px;font-size:15px}
button:active{background:#3a3f47}#st{font:13px ui-monospace,monospace;color:#9aa0a6;white-space:pre-line;text-align:center}
img{max-width:min(92vw,480px);border-radius:10px}</style>
<div class=lens><canvas id=c width=240 height=240></canvas></div>
<div class=row><button onclick="press('short')">Press · talk</button><button onclick="press('double')">Double · switch display</button>
<button onclick="press('long')">Hold · hush</button></div>
<div class=row><button onclick="photo()">Take a photo</button><button onclick="retry()">Retry camera</button></div>
<div id=st>Connecting…</div><div id=cam></div><img id=ph hidden>
<script>
const c=document.getElementById('c').getContext('2d'),img=c.createImageData(240,240);let v=0;
async function frame(){try{const r=await fetch('/screen?v='+v);if(r.status==200){v=+r.headers.get('X-Version');
const b=new Uint8Array(await r.arrayBuffer()),d=img.data;for(let i=0,j=0;i<b.length;i+=2,j+=4){const p=b[i]|b[i+1]<<8;
d[j]=(p>>11)<<3;d[j+1]=((p>>5)&63)<<2;d[j+2]=(p&31)<<3;d[j+3]=255}c.putImageData(img,0,0)}}catch(e){}setTimeout(frame,document.hidden?1000:200)}
async function status(){try{const s=await (await fetch('/status')).json();document.getElementById('st').textContent=
(s.camera_error?'Camera problem: '+s.camera_error+'\n\n':'')+(s.linked?'Linked to the app at '+s.phone:'Not linked: looking for the app at '+s.phone)+'\nSkin: '+s.skin+' · Wi-Fi '+s.ssid+' ('+s.rssi+' dBm)'+
'\nCamera: '+(s.camera?(s.camera_model||'')+' working':'not working')+' · Streamed pictures: '+s.frames+' · Presses sent: '+s.presses+
'\nFree PSRAM: '+Math.round(s.psram/1024)+' KB · Up '+s.uptime+' s'}catch(e){
document.getElementById('st').textContent='Lost contact with the glasses'}setTimeout(status,2000)}
function press(k){fetch('/press?kind='+k)}
const cam=document.getElementById('cam');cam.style.cssText='max-width:420px;text-align:center;color:#f28b82'
async function photo(){const p=document.getElementById('ph');cam.textContent='Taking a photo…';try{const r=await fetch('/photo?t='+Date.now());
if(!r.ok){cam.textContent='Photo failed: '+await r.text();p.hidden=true;return}p.src=URL.createObjectURL(await r.blob());p.hidden=false;cam.textContent=''}
catch(e){cam.textContent='Photo failed: lost contact'}}
async function retry(){cam.textContent='Restarting the camera…';try{cam.textContent=await (await fetch('/camera/retry')).text()}catch(e){cam.textContent='Lost contact'}}
frame();status();
</script>)HTML";

void startVirtualDisplay() {
  web.on("/", []() { web.send_P(200, "text/html", VIRTUAL); });
  web.on("/screen", []() {
    if (web.arg("v").toInt() == (long)screenVersion) { web.send(204); return; }
    web.sendHeader("X-Version", String(screenVersion));
    web.sendHeader("Cache-Control", "no-store");
    web.setContentLength(SIZE * SIZE * 2);
    web.send(200, "application/octet-stream", "");
    web.sendContent((const char *)screen->getBuffer(), SIZE * SIZE * 2);
  });
  web.on("/press", []() {
    String k = web.arg("kind");
    if (k == "short" || k == "double" || k == "long") sendPress(k.c_str());
    web.send(linked ? 200 : 409, "text/plain", linked ? "ok" : "not linked to the app");
  });
  web.on("/photo", []() {
    if (!cameraOk) { web.send(503, "text/plain", "the camera didn't start. " + cameraError); return; }
    camera_fb_t *fb = esp_camera_fb_get();
    if (fb) esp_camera_fb_return(fb);   // drop a stale frame
    fb = esp_camera_fb_get();
    if (!fb) { web.send(503, "text/plain", "the camera gave no picture. Try Retry camera, or reseat its ribbon."); return; }
    web.sendHeader("Cache-Control", "no-store");
    web.send_P(200, "image/jpeg", (const char *)fb->buf, fb->len);
    esp_camera_fb_return(fb);
  });
  web.on("/camera/retry", []() {
    startCamera();
    web.send(200, "text/plain", cameraOk ? "Camera working: " + cameraModel : "Camera failed: " + cameraError);
  });
  web.on("/status", []() {
    JsonDocument d;
    d["linked"] = linked; d["phone"] = linkHost; d["ssid"] = ssid; d["rssi"] = WiFi.RSSI();
    d["camera"] = cameraOk; d["camera_model"] = cameraModel; d["camera_error"] = cameraError;
    d["frames"] = framesIn; d["presses"] = presses; d["skin"] = skinName;
    d["streaming"] = streaming; d["psram"] = ESP.getFreePsram(); d["uptime"] = millis() / 1000;
    d["display"] = false; d["fw"] = FW_VERSION;
    String out; serializeJson(d, out);
    web.send(200, "application/json", out);
  });
  web.begin();
}

// ---------------------------------------------------------------- main

void setup() {
  Serial.begin(115200);
  pinMode(BUTTON_BOOT, INPUT_PULLUP);
  pinMode(BUTTON_EXT, INPUT_PULLUP);

  tft.begin(40000000);
  tft.setRotation(0);
  screen = new GFXcanvas16(SIZE, SIZE);
  frame = new GFXcanvas16(SIZE, SIZE);
  if (!screen->getBuffer() || !frame->getBuffer()) {
    tft.fillScreen(GC9A01A_RED);   // no PSRAM: set PSRAM to "OPI PSRAM" in the board settings
    while (true) delay(1000);
  }
  frame->fillScreen(0);
  TJpgDec.setJpgScale(1);
  TJpgDec.setSwapBytes(false);
  TJpgDec.setCallback(onJpegBlock);

  prefs.begin("glasses");
  ssid = prefs.getString("ssid", "");
  ssid.trim();
  pass = prefs.getString("pass", "");
  token = prefs.getString("token", "");
  token.trim(); token.toLowerCase();
  phoneIp = prefs.getString("phone", "");

  // Holding BOOT during power-up would start the chip's flashing mode, so setup is asked for
  // by holding the button for a second while this message shows.
  setStatus(ssid.isEmpty() ? "Starting" : "Hold the button now to change Wi-Fi");
  uint32_t held = 0, start = millis();
  while (!ssid.isEmpty() && millis() - start < 2500) {
    held = buttonDown() ? held + 20 : 0;
    if (held >= 1000) break;
    delay(20);
  }
  if (ssid.isEmpty() || held >= 1000) portal();

  startCamera();

  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);   // lower latency for streaming
  WiFi.begin(ssid.c_str(), pass.c_str());
  setStatus("Joining " + ssid);
  uint32_t t0 = millis();
  while (WiFi.status() != WL_CONNECTED) {
    delay(250);
    if (millis() - t0 > 30000) {
      setStatus("Can't join " + ssid + ". Restart and hold the button to change it.");
      t0 = millis();
      WiFi.reconnect();
    }
  }
  // On a phone hotspot, the phone is the gateway.
  String host = phoneIp.length() ? phoneIp : WiFi.gatewayIP().toString();
  linkHost = host;
  Serial.println("Glasses address: http://" + WiFi.localIP().toString() + "/  (virtual display)");
  startVirtualDisplay();
  setStatus("Looking for the app...");
  ws.begin(host, LINK_PORT, "/");
  ws.onEvent(onWs);
  ws.setReconnectInterval(2000);
  ws.enableHeartbeat(10000, 4000, 2);
}

uint32_t lastStatus = 0, lastRedraw = 0;
bool overlayShown = false;

void loop() {
  ws.loop();
  web.handleClient();
  pollButton();
  uint32_t now = millis();

  // Take the overlay down when its time is up.
  bool overlayNow = overlayText.length() && now < overlayUntil;
  if (overlayShown && !overlayNow) compose();
  overlayShown = overlayNow;

  if (linked && now - lastStatus > 30000) {
    lastStatus = now;
    sendText(String("{\"type\":\"status\",\"rssi\":") + WiFi.RSSI() + ",\"psram_free\":" + ESP.getFreePsram() +
               ",\"uptime_s\":" + now / 1000 + ",\"camera\":" + (cameraOk ? "true" : "false") + "}");
  }
  if (WiFi.status() != WL_CONNECTED && now - lastRedraw > 5000) {
    lastRedraw = now;
    setStatus("Hotspot lost, rejoining...");
  }
  delay(2);
}

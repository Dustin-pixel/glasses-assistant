/*
  Camera test for the XIAO ESP32S3 Sense. Nothing else: no Wi-Fi, display or app.
  Uses Espressif's standard camera settings for this board.

  The little orange light next to the USB port tells you the result:
    steady on          = camera working (it takes a picture every 2 seconds)
    fast blinking      = camera not found
    slow blinking      = camera found, but it couldn't take a picture

  For details, open a serial console at 115200 baud (e.g. the Console in the web flasher).
*/
#include "esp_camera.h"

#define LED 21   // the XIAO's user light (on when the pin is LOW)

void blinkForever(int ms) {
  while (true) {
    digitalWrite(LED, LOW); delay(ms);
    digitalWrite(LED, HIGH); delay(ms);
  }
}

void setup() {
  Serial.begin(115200);
  pinMode(LED, OUTPUT);
  digitalWrite(LED, HIGH);
  delay(2000);   // time to open the console
  Serial.println();
  Serial.println("=== XIAO ESP32S3 Sense camera test ===");
  Serial.printf("PSRAM: %s (%u KB free)\n", psramFound() ? "found" : "MISSING", ESP.getFreePsram() / 1024);

  camera_config_t c = {};
  c.ledc_channel = LEDC_CHANNEL_0;
  c.ledc_timer = LEDC_TIMER_0;
  c.pin_d0 = 15; c.pin_d1 = 17; c.pin_d2 = 18; c.pin_d3 = 16;
  c.pin_d4 = 14; c.pin_d5 = 12; c.pin_d6 = 11; c.pin_d7 = 48;
  c.pin_xclk = 10; c.pin_pclk = 13; c.pin_vsync = 38; c.pin_href = 47;
  c.pin_sccb_sda = 40; c.pin_sccb_scl = 39;
  c.pin_pwdn = -1; c.pin_reset = -1;
  c.xclk_freq_hz = 20000000;
  c.pixel_format = PIXFORMAT_JPEG;
  c.frame_size = FRAMESIZE_SVGA;
  c.jpeg_quality = 12;
  c.fb_count = 1;
  c.fb_location = psramFound() ? CAMERA_FB_IN_PSRAM : CAMERA_FB_IN_DRAM;
  c.grab_mode = CAMERA_GRAB_WHEN_EMPTY;

  esp_err_t err = esp_camera_init(&c);
  if (err != ESP_OK) {
    Serial.printf("RESULT: camera NOT found. Error 0x%x (%s)\n", err, esp_err_to_name(err));
    Serial.println("The board got no answer from the camera: check its connector, or the camera is faulty.");
    blinkForever(120);
  }

  sensor_t *s = esp_camera_sensor_get();
  camera_sensor_info_t *info = s ? esp_camera_sensor_get_info(&s->id) : nullptr;
  Serial.printf("Camera found: %s (PID 0x%x)\n", info ? info->name : "unknown model", s ? s->id.PID : 0);
}

void loop() {
  camera_fb_t *fb = esp_camera_fb_get();
  if (!fb) {
    Serial.println("RESULT: camera found, but it gave no picture.");
    blinkForever(600);
  }
  Serial.printf("RESULT: camera working. Picture %ux%u, %u bytes\n", fb->width, fb->height, fb->len);
  esp_camera_fb_return(fb);
  digitalWrite(LED, LOW);   // steady on
  delay(2000);
}

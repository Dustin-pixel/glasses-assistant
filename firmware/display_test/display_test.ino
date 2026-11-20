/*
  Screen test for the glasses: XIAO ESP32S3 + Adafruit 1.28" round GC9A01A. Nothing else runs.
  Wiring: Vin->3V3, GND->GND, SCK->D8, MOSI->D10, TCS->D7, DC->D4, RST->D5.

  What you should see, over and over: red, green, blue, white, then "Screen OK" with a counter.
  The XIAO's orange light blinks once per loop, so you know the program is running even if the
  screen stays dark. A serial console at 115200 baud prints each step.

    Light blinking, screen fully dark         -> no power or backlight: check Vin->3V3 and GND
    Light blinking, screen lit but plain white -> powered but no picture: check SCK, MOSI, TCS, DC, RST
    Colors wrong or speckled                  -> a loose data wire (SCK or MOSI) or a cold joint
*/
#include <SPI.h>
#include <Adafruit_GFX.h>
#include <Adafruit_GC9A01A.h>

#define TFT_CS  D7
#define TFT_DC  D4
#define TFT_RST D5
#define LED 21

Adafruit_GC9A01A tft(TFT_CS, TFT_DC, TFT_RST);
uint32_t loops = 0;

void setup() {
  Serial.begin(115200);
  pinMode(LED, OUTPUT);
  delay(1500);
  Serial.println("=== Glasses screen test ===");
  Serial.println("Starting the screen at 10 MHz (slow and safe)...");
  tft.begin(10000000);
  Serial.println("Screen started. If nothing shows, it's the wiring or power, not the software.");
}

void show(uint16_t color, const char *name) {
  Serial.printf("Filling %s\n", name);
  tft.fillScreen(color);
  delay(700);
}

void loop() {
  digitalWrite(LED, LOW); delay(80); digitalWrite(LED, HIGH);
  show(GC9A01A_RED, "red");
  show(GC9A01A_GREEN, "green");
  show(GC9A01A_BLUE, "blue");
  show(GC9A01A_WHITE, "white");
  tft.fillScreen(GC9A01A_BLACK);
  tft.drawCircle(120, 120, 118, GC9A01A_YELLOW);
  tft.drawCircle(120, 120, 117, GC9A01A_YELLOW);
  tft.setTextColor(GC9A01A_WHITE);
  tft.setTextSize(3);
  tft.setCursor(30, 95);  tft.print("Screen OK");
  tft.setTextSize(2);
  tft.setCursor(85, 140); tft.print(++loops);
  Serial.printf("Text shown, loop %u\n", loops);
  delay(1500);
}

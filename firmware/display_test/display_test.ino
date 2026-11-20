/*
  Screen test for the glasses: XIAO ESP32S3 + Adafruit 1.28" round GC9A01A. Nothing else runs.
  Wiring: Vin->3V3, GND->GND, SCK->D8, MOSI->D10, TCS->D7, DC->D4, RST->D5.

  It alternates between two ways of talking to the screen, so the cause of a blank screen is clear:
    Round A, "SLOW SPI": the XIAO's SPI hardware at 1 MHz (very forgiving of long wires)
    Round B, "SOFTWARE": the pins switched one by one in software, not using the SPI hardware at all
  Each round shows red, green, blue, white, then its name in big letters.

    Both rounds show colors      -> the screen and wiring are fine; the glasses firmware needs a fix
    Only SOFTWARE shows colors   -> the SPI hardware setup is the problem (a firmware fix)
    Neither shows anything       -> the screen itself, most likely its thin ribbon on the back

  The orange light blinks once at the start of each round. A serial console at 115200 baud says
  which round is running.
*/
#include <SPI.h>
#include <Adafruit_GFX.h>
#include <Adafruit_GC9A01A.h>

#define TFT_CS   D7
#define TFT_DC   D4
#define TFT_RST  D5
#define TFT_MOSI D10
#define TFT_SCK  D8
#define LED 21

Adafruit_GC9A01A *hw = nullptr;   // SPI hardware
Adafruit_GC9A01A *sw = nullptr;   // software, pin by pin
uint32_t rounds = 0;

void blink() { digitalWrite(LED, LOW); delay(120); digitalWrite(LED, HIGH); }

void run(Adafruit_GC9A01A *tft, const char *name) {
  const uint16_t colors[] = {GC9A01A_RED, GC9A01A_GREEN, GC9A01A_BLUE, GC9A01A_WHITE};
  const char *names[] = {"red", "green", "blue", "white"};
  for (int i = 0; i < 4; i++) {
    Serial.printf("  %s: filling %s\n", name, names[i]);
    tft->fillScreen(colors[i]);
    delay(600);
  }
  tft->fillScreen(GC9A01A_BLACK);
  tft->drawCircle(120, 120, 118, GC9A01A_YELLOW);
  tft->drawCircle(120, 120, 117, GC9A01A_YELLOW);
  tft->setTextColor(GC9A01A_WHITE);
  tft->setTextSize(3);
  int w = strlen(name) * 18;
  tft->setCursor(120 - w / 2, 100);
  tft->print(name);
  tft->setTextSize(2);
  tft->setCursor(95, 140);
  tft->print(rounds);
  delay(1500);
}

void setup() {
  Serial.begin(115200);
  pinMode(LED, OUTPUT);
  digitalWrite(LED, HIGH);
  delay(1500);
  Serial.println("=== Glasses screen test (slow SPI and software) ===");
  hw = new Adafruit_GC9A01A(TFT_CS, TFT_DC, TFT_RST);
  sw = new Adafruit_GC9A01A(TFT_CS, TFT_DC, TFT_MOSI, TFT_SCK, TFT_RST);
}

void loop() {
  rounds++;

  Serial.printf("Round %u A: SLOW SPI (SPI hardware at 1 MHz)\n", rounds);
  blink();
  hw->begin(1000000);
  run(hw, "SLOW SPI");
  SPI.end();   // hand the pins back for the software round

  Serial.printf("Round %u B: SOFTWARE (pins switched directly, no SPI hardware)\n", rounds);
  blink(); delay(150); blink();
  sw->begin();
  run(sw, "SOFTWARE");
}

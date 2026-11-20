/*
  Wiring check for the glasses screen. It doesn't use the screen; it tests the five signal wires
  from the XIAO's side, looking for wires that touch each other, ground or 3.3 V.

  Open a serial console at 115200 baud (e.g. the Console in the web flasher) to read the report.
  The XIAO's orange light: steady on = no shorts found, blinking = a problem (see the console).

  It can't tell whether a wire goes to the right screen pin; check that by color.
*/
struct Pin { const char *xiao; int gpio; const char *screen; };
Pin pins[] = {
  {"D8",  7,  "SCK"},
  {"D10", 9,  "MOSI"},
  {"D7",  44, "TFTCS"},
  {"D4",  5,  "DC"},
  {"D5",  6,  "RST"},
};
const int N = sizeof(pins) / sizeof(pins[0]);
#define LED 21

void allInputs(int mode) { for (auto &p : pins) pinMode(p.gpio, mode); }

int check() {
  int problems = 0;
  Serial.println();
  Serial.println("=== Screen wiring check ===");

  // 1. Each wire against ground: with a pull-up it should read high.
  allInputs(INPUT_PULLUP);
  delay(5);
  for (auto &p : pins) {
    if (digitalRead(p.gpio) == LOW) {
      Serial.printf("PROBLEM: %s (to screen %s) is touching GROUND.\n", p.xiao, p.screen);
      problems++;
    }
  }

  // 2. Each wire against each other: pull one low, the rest should stay high.
  for (int i = 0; i < N; i++) {
    allInputs(INPUT_PULLUP);
    pinMode(pins[i].gpio, OUTPUT);
    digitalWrite(pins[i].gpio, LOW);
    delay(5);
    for (int j = i + 1; j < N; j++) {
      if (digitalRead(pins[j].gpio) == LOW) {
        Serial.printf("PROBLEM: %s (screen %s) and %s (screen %s) are touching each other.\n",
                      pins[i].xiao, pins[i].screen, pins[j].xiao, pins[j].screen);
        problems++;
      }
    }
  }

  // 3. Each wire against 3.3 V: with a pull-down it should read low. The screen board pulls some
  //    of its inputs up gently, so a "high" here is only reported, not counted as a short.
  allInputs(INPUT_PULLDOWN);
  delay(5);
  for (auto &p : pins) {
    if (digitalRead(p.gpio) == HIGH) {
      // Check how strong: a real short to 3.3 V stays high even when we pull down hard.
      pinMode(p.gpio, OUTPUT); digitalWrite(p.gpio, LOW); delay(2);
      pinMode(p.gpio, INPUT_PULLDOWN); delayMicroseconds(20);
      bool stillHigh = digitalRead(p.gpio) == HIGH;
      Serial.printf("Note: %s (screen %s) reads high: %s\n", p.xiao, p.screen,
                    stillHigh ? "it may be touching 3.3 V or Vin" : "a pull-up on the screen board, normal");
      if (stillHigh) problems++;
    }
  }
  allInputs(INPUT);

  if (problems == 0) {
    Serial.println("RESULT: no shorts found. If the screen is still blank, check each wire goes to the right pin,");
    Serial.println("and that every joint has solder flowed onto both the pad and the wire.");
  } else {
    Serial.printf("RESULT: %d problem(s) found. Fix those joints and the screen should work.\n", problems);
  }
  return problems;
}

void setup() {
  Serial.begin(115200);
  pinMode(LED, OUTPUT);
  digitalWrite(LED, HIGH);
  delay(2000);
}

void loop() {
  int problems = check();
  for (int k = 0; k < 10; k++) {          // show the result on the light for about 5 seconds
    if (problems == 0) { digitalWrite(LED, LOW); delay(500); }
    else { digitalWrite(LED, LOW); delay(150); digitalWrite(LED, HIGH); delay(350); }
  }
}

# Glasses Assistant

A personal voice + vision assistant for DIY smart glasses. The Android phone is the brain;
the glasses (Pi Zero 2 W) are a thin client: camera, display, button.

```
 Glasses (Pi)                          Phone (this app)                       Cloud
 ─────────────                         ─────────────────                      ─────
 camera ── JPEG on request ──────────▶ GlassesLink (WebSocket :8765)
 display ◀── overlay JSON ──────────── AssistantService
 button ──── "button" ───────────────▶   listen (SpeechRecognizer)
                                         think  (Brain) ───────────────────▶ Claude API
 Bluetooth earbuds ◀──── speech ───────  speak  (TextToSpeech)                (tools, vision)
```

## Phone app

1. Open this folder in Android Studio (it sets up the Gradle wrapper on first sync).
2. Run on your phone. Enter your Claude API key, tap **Start assistant**, allow the microphone and notifications.
3. Tap **Talk** (in the app or on the notification) and speak. Try "what time is it" or "set a timer for 2 minutes".

The app shows a link token. The glasses need it to connect.

## Glasses firmware (XIAO ESP32S3 Sense)

`firmware/glasses/glasses.ino`. GitHub builds it on every change and publishes a **Glasses firmware** release
with a single `.bin`. To install it, from a computer with Chrome or Edge:

1. Plug the XIAO in with a USB-C data cable.
2. Open https://espressif.github.io/esptool-js/, set the baud rate to 921600 and click **Connect**.
3. Set the flash address to `0x0`, choose the `.bin` and click **Program**. Then unplug and replug the XIAO.

On first start the display says *Join Wi-Fi Glasses-Setup*. Join that network from your phone, open
http://192.168.4.1 and enter your hotspot name, its password and the link token from the app. To change these
later, hold the button while the glasses power up.

Button (the XIAO's BOOT button, or one wired from D1 to GND): press to talk, double-press to switch the
display (normal, now playing, phone screen), and hold to stop talking and clear the display.

`glasses/glasses_client.py` is the older Raspberry Pi version, and is still handy for testing the link from a laptop.

## Display, search and phone control

- **Web search**: Claude's web search tool, switchable in the app. It costs about $10 per 1,000 searches on the API key.
- **Now playing**: a round card with the art, title, artist and a progress ring. It works with any player.
  Needs *music access* (Notification access).
- **Screen mirror**: the phone screen at up to 12 frames a second. Crop is auto (TikTok fills the circle,
  YouTube shows the video strip, other apps fit), or fit, fill, video or zoom. Android asks once per session.
- **Text card**: longer text paged to fit the circle, e.g. a reply read from the Claude app.
- **Phone control** (Accessibility): opens apps and searches, swipes to the next TikTok, scrolls, reads the
  screen, taps buttons by label and types (e.g. a prompt into the Claude app). On sideloaded apps, Android
  may first need *Allow restricted settings* from the app's info page.

## Protocol

One WebSocket; JSON text frames plus binary JPEG frames.

| Direction | Message | Fields |
|---|---|---|
| glasses → phone | `hello` | `token`, `device`, `fw` (must be first) |
| glasses → phone | `button` | `press`: `short` (talk), `double` (switch display), `long` (hush) |
| glasses → phone | `status` | `rssi`, `psram_free`, `uptime_s`, `camera` |
| glasses → phone | `capture_failed` | `id`, `reason` |
| glasses → phone | `frame_ack` | sent after drawing a streamed frame (paces the stream) |
| glasses → phone | *binary* | JPEG answering the latest `capture` |
| phone → glasses | `capture` | `id` |
| phone → glasses | `overlay` | `text`, `pos` (`top`/`bottom`), `ttl_ms` |
| phone → glasses | `clear` | |
| phone → glasses | `skin` | `name`, `accent`, `bg`, `text` (RGB565) |
| phone → glasses | *binary* | 240×240 JPEG to fill the display |
| phone → glasses | `stream_end` | back to the glasses' own screen |

## Code map

| File | Job |
|---|---|
| `AssistantService.kt` | Foreground service; owns everything; runs listen → think → speak |
| `link/GlassesLink.kt` | WebSocket server, auth, frame requests |
| `brain/Brain.kt` | Claude Messages API, tool loop, rolling history |
| `brain/Tools.kt` | Tool interface + built-ins: `look`, `show_on_display`, `get_time`, `set_timer` |
| `voice/SpeechInput.kt` / `SpeechOutput.kt` | Android speech recognition and TTS |
| `MainActivity.kt` | Settings and status screen |

## Adding an ability

Implement `Tool` (name, description, JSON schema, `run`) and add it to the list in
`AssistantService.onCreate`. Claude decides when to call it from the description.

## Next steps

- **Wake word** instead of a button: openWakeWord on the Pi, or Porcupine on the phone.
- **Overlay rendering** on the Pi: draw text onto the camera passthrough (`Display.show`).
- **GPIO button** on the Pi (`gpiozero.Button`) in place of the Enter key.
- **More tools**: notifications (NotificationListenerService), navigation, calendar, messages, smart home.
- **Local fallback**: a small on-device model for simple commands when offline.
- The API key is stored unencrypted in app preferences. Fine for a personal build; use EncryptedSharedPreferences or a keystore before sharing the app.

## Skins and voices

Default, Crimson (Ultron), Sage (Great Sage) and Glitch (Capri). Each skin sets the personality,
the app colors, the glasses display style and a voice effect (`voice/VoiceFx.kt`): Crimson is
doubled and metallic, Sage is thin and synthetic with an echo, Glitch stutters and crunches.
Argue mode puts all three characters in one conversation.

## Android Auto

1. In the Android Auto app on your phone, open settings and tap **Version** about 10 times to unlock developer mode.
2. From the three-dot menu open **Developer settings** and turn on **Unknown sources**.
3. Start the assistant on your phone, then connect to the car. **Glasses Assistant** appears with the car's media apps.

In the car it works like a music app: a **Controls** tab (Talk, mode, Keep arguing, New argument, Stop talking)
and a **Characters** tab. The now-playing screen shows who is talking and what they said.
You can also say **"Hey Google, play *anything* on Glasses Assistant"**: your words go straight in as a question,
a topic, or a line in the argument, using the car's microphone.

## Windows app

`desktop/` is a native Windows version (C#/WPF, .NET 8) with the same skins, personas, Argue mode and voice effects.
It uses Windows' own speech recognition and voices, and adds a `look_at_screen` tool so the assistant can see your monitor.
Press **Ctrl+Alt+Space** anywhere to talk. GitHub builds a single `GlassesAssistant-N.exe` on the Releases page.

First run: paste your Claude API key under Settings and tap Save (it's stored encrypted for your Windows account).
For voice input, turn on **Settings > Privacy & security > Speech > Online speech recognition** and allow
desktop apps to use the microphone.

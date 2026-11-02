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

## Glasses client

On the Pi (or a laptop for testing), with the phone's hotspot on and the device joined to it:

```
pip install websockets
python3 glasses/glasses_client.py --token <token from the app>
```

It connects to the default gateway (the phone, when on its hotspot). Press Enter to start a turn.
With a Pi camera attached, "what am I looking at?" sends a frame to Claude.
To test over normal Wi-Fi instead, pass `--phone <phone IP>`.

## Protocol

One WebSocket; JSON text frames plus binary JPEG frames.

| Direction | Message | Fields |
|---|---|---|
| glasses → phone | `hello` | `token`, `device` (must be first) |
| glasses → phone | `button` | starts a turn |
| glasses → phone | `status` | free-form (battery, temperature) |
| glasses → phone | `capture_failed` | `id`, `reason` |
| glasses → phone | *binary* | JPEG answering the latest `capture` |
| phone → glasses | `capture` | `id` |
| phone → glasses | `overlay` | `text`, `pos` (`top`/`bottom`), `ttl_ms` |
| phone → glasses | `clear` | |

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

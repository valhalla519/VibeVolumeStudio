# VibeVolume — Prototype

Auto-adjusts your phone's media volume based on how crowded/lively the room is, using two passive signals:

1. **Bluetooth device count** — more phones nearby = more people
2. **Accelerometer vibration energy** — more movement/bass/noise = louder room

No microphone. No privacy concerns. Runs as a foreground service.

---

## Setup in Android Studio

1. Open Android Studio → **File > Open** → select the `VibeVolume` root folder (the one containing `settings.gradle`)
2. Let Gradle sync finish. If it doesn't auto-sync, go to **File → Sync Project with Gradle Files**
3. Connect your Android phone with USB debugging enabled, or launch an emulator
4. Hit the green **Run ▶** button

> Minimum Android version: 6.0 (API 23)

---

## Folder Structure

```
VibeVolume/
├── gradle.properties        ← AndroidX + JVM settings
├── settings.gradle
├── build.gradle
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/vibevolume/app/
        │   ├── MainActivity.kt       ← UI
        │   └── VibeVolumeService.kt  ← Sensor engine + volume logic
        └── res/
            ├── layout/activity_main.xml
            └── values/colors.xml
```

---

## Permissions the app requests

| Permission | Why |
|---|---|
| `BLUETOOTH_SCAN` | Count nearby BT devices |
| `ACCESS_FINE_LOCATION` | Required for BT scanning on Android < 12 |
| `FOREGROUND_SERVICE` | Run persistently in background |
| `MODIFY_AUDIO_SETTINGS` | Adjust system volume |

---

## How it works

### On Start:
- Registers accelerometer listener (no special permission needed)
- Runs a BLE scan every **30 seconds** (8 second scan window to save battery)
- Adjusts volume every **5 seconds**
- **Calibrates a baseline** on the first BT scan — this is your "empty room" reference point. Start the app before guests arrive for best results.

### Crowd Score formula:
```
BT Score    = (current_devices - baseline_devices) / baseline_devices  → clamped 0–1
Vibr Score  = (current_vibration / baseline_vibration - 1) / 2        → clamped 0–1

Raw Score   = (BT Score × 0.6) + (Vibration Score × 0.4)
Curved Score = applyCurve(Raw Score)   ← shaped by Response Curve setting
Target Vol  = minVolume + (maxVolume - minVolume) × Curved Score
```

BT gets 60% weight — more stable and reliable.
Vibration gets 40% — reacts faster but noisier.

---

## Response Curve Modes

| Mode | Math | Behavior |
|---|---|---|
| Gradual | x^0.5 (square root) | Responds quickly to small crowds, flattens near the top |
| Medium | x^1.0 (linear) | Proportional all the way — good default |
| Aggressive | x^2.0 (squared) | Stays low until the room is packed, then surges |

---

## Settings

| Setting | What it does |
|---|---|
| Min Volume | Floor — volume never goes below this |
| Max Volume | Ceiling — volume never goes above this |
| Response Curve | Shape of the volume ramp (Gradual / Medium / Aggressive) |

---

## Testing on an Emulator

The emulator has no real Bluetooth hardware, so BT count will always read 0. The app won't crash — it will rely on the vibration signal only.

To simulate vibration: open **Extended Controls** (the `...` button on the emulator toolbar) → **Virtual Sensors** and move the accelerometer sliders. Watch the Vibration Energy reading respond in the app.

For a full test of both signals, use a **real physical phone**.

---

## Tuning tips

- Start the app **before guests arrive** so calibration captures a quiet room
- Place the phone on a **hard surface** (table, counter) — vibration detection works much better than in a pocket
- If volume jumps too erratically, switch to **Gradual** curve
- If the app feels unresponsive, switch to **Aggressive** curve
- Restart the app to recalibrate the baseline

---

## Known prototype limitations

- BLE scan requires location permission on Android < 12 (Android platform requirement)
- Volume adjustments are silent — no system UI popup, intentional
- Baseline calibrates once at startup only — restart to recalibrate
- Classic Bluetooth devices (non-BLE) are not counted in this version

---

## Next steps after prototype validation

- [ ] Persistent settings with SharedPreferences
- [ ] Recalibrate button in UI
- [ ] Graph of crowd score over time
- [ ] Classic Bluetooth scanning in addition to BLE
- [ ] Home screen widget for quick start/stop
- [ ] Export session data for tuning signal weights

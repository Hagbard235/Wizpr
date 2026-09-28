# Ring Notes

Android-App für den [WIZPR Ring](https://wizpr.io/): verbindet sich per Bluetooth LE mit dem Ring
und speichert jede Aufnahme, die am Ring gestartet wird, als WAV-Datei auf dem Handy.

Unabhängiges Projekt auf Basis des Open-Source-[WizprRingSDK](https://github.com/vtouchio/wizpr-ring-sdk)
(Apache-2.0). Nicht mit VTouch verbunden.

## Funktionen

- Ringe suchen (gleiche Erkennung wie das SDK: Service-UUID oder Name „WIZPR RING“) und verbinden
- Aufnahmen vom Ring empfangen, IMA-ADPCM → 16 kHz PCM dekodieren, als WAV speichern
  (3× Verstärkung wie im SDK-Desktop-Beispiel). Ende einer Aufnahme: nach Stoppsignal oder
  „Mikrofon aus“ vom Ring, sobald 0,7 s kein Audio mehr kommt (max. 5 s Nachlauf); ohne Stoppsignal
  nach 3 s Funkstille. Der Grund steht im Log bei „Gespeichert“.
- Live-Pegel und Dauer während der Aufnahme
- **Transkription auf dem Gerät**: Jede neue Aufnahme wird mit der On-Device-Spracherkennung
  des Handys (auf Pixel: Googles Offline-Erkennung) in Text umgewandelt – ohne Cloud und ohne
  Mikrofon, das WAV wird direkt eingespeist. Braucht Android 13+; Sprache = Systemsprache.
  Läuft im Hintergrund, solange der Ring verbunden ist (der Foreground-Service ist dafür zusätzlich
  als Mikrofon-Dienst angemeldet, weil Android die Spracherkennung sonst nur im Vordergrund erlaubt).
  Was im Hintergrund scheitert, wird beim nächsten Öffnen der App nachgeholt.
- **KI-Weiterleitung** (Tab „KI“): Das Transkript jeder neuen Aufnahme geht automatisch an
  - **Claude** über die Anthropic-API (eigener API-Schlüssel, Modell und Anweisung einstellbar;
    Standard `claude-opus-5`, fasst zusammen und listet Aufgaben/Termine). Die Antwort erscheint
    unter der Aufnahme und als Benachrichtigung.
  - oder einen **Webhook** (POST, JSON `{recording, createdAt, durationMs, transcript}`), z. B. für
    n8n, Home Assistant oder Make – von dort aus an jede beliebige KI oder App.
- Aufnahmen abspielen, teilen (z. B. an eine Transkriptions-App) und löschen
- Akkustand (automatisch alle 5 min), Mikrofon-Status, Klick/Doppelklick im Log
- Sperren über den `LOCK`-Befehl mit host-seitiger Sperre (Aufnahmen werden ignoriert)
- Hintergrundbetrieb über einen Foreground-Service; nach Verbindungsabbruch wird automatisch
  wieder verbunden, sobald der Ring in Reichweite ist
- Merkt sich den zuletzt verbundenen Ring

## Aufbau

| Modul | Inhalt |
|---|---|
| `ringcore/` | Reines Kotlin/JVM, ohne Android: BLE-UUIDs, ADPCM-Decoder, Event-Parser, Klick-Erkennung, WAV-Writer. Portiert aus `wizpr-ring-core`; die Tests des SDK sind mitportiert. |
| `app/` | Android-App (Kotlin, Jetpack Compose): GATT-Client, Scanner, `RingController`, Foreground-Service, UI. |

Das Rust-SDK wird nicht per JNI eingebunden: Seine Android-Bindings sind laut README noch „TBD“,
und das Protokoll ist klein genug, um es direkt in Kotlin auf der Android-BLE-API umzusetzen.

## Bauen

Voraussetzungen: JDK 17, Android SDK (API 35).

```sh
./gradlew -p ringcore test   # Protokoll-Tests, braucht kein Android SDK
./gradlew assembleDebug      # APK unter app/build/outputs/apk/debug/
```

Ohne lokales Android SDK: GitHub Actions baut bei jedem Push eine Debug-APK und hängt sie als
Artefakt `ring-notes-debug-apk` an den Workflow-Lauf.

Mindestversion: Android 8.0 (API 26).

## Lizenz

Apache-2.0 (siehe `NOTICE`). WIZPR und WIZPR Ring sind Marken der VTouch Inc.

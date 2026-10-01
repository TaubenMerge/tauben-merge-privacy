# AutoSpiegel

Kostenlose, schlanke Alternative zu Android Auto für ältere Android-Autoradios.
Das Handy schickt sein Bild per WLAN ans Radio, und du bedienst das Handy über den
Touchscreen im Auto: tippen, wischen, mit zwei Fingern zoomen, außerdem gibt es
Tasten für Zurück, Startbildschirm und Letzte Apps.

- **Kostenlos**, ohne Abo, ohne Werbung und ohne Google-Konto.
- **Sehr leicht fürs Radio:** Die App ist ca. 60 KB groß. Das Radio spielt nur ein
  Video ab (Hardware-Decoder), die ganze Arbeit macht das Handy.
- **Radio:** ab Android 4.1. **Handy:** ab Android 7.
- Ton und Telefonate laufen wie gewohnt über **Bluetooth**.

Es ist **eine** App für beide Geräte. Beim ersten Start wählst du, ob das Gerät das
Handy oder das Radio ist.

## Installieren

`AutoSpiegel.apk` liegt in diesem Ordner.

**Aufs Handy:** APK herunterladen, öffnen und die Installation aus unbekannten
Quellen erlauben.

**Aufs Radio:** Dafür gibt es drei Wege, nimm den einfachsten:
1. **Direkt vom Handy:** Installiere die App zuerst auf dem Handy und starte dort
   „Spiegeln“. Schalte den Hotspot ein und verbinde das Radio damit. Öffne dann im
   Browser des Radios die Adresse, die die Handy-App anzeigt (z. B.
   `http://192.168.43.1:47802`).
2. **USB-Stick:** APK auf einen Stick kopieren, am Radio mit dem Dateimanager öffnen.
3. **Browser am Radio:**
   `https://github.com/taubenmerge/tauben-merge-privacy/raw/claude/youthful-goldberg-k8i8sh/autospiegel/AutoSpiegel.apk`

## Einrichten (einmalig)

1. **Radio:** AutoSpiegel öffnen → „Das ist das Autoradio“. Es zeigt einen
   6-stelligen **Radio-Code** an.
2. **Handy:** AutoSpiegel öffnen → „Das ist mein Handy“, dann:
   - **Bedienung erlauben:** „Bedienungshilfen öffnen“ → AutoSpiegel → einschalten.
     Ist der Schalter gesperrt („Eingeschränkte Einstellung“, ab Android 13)? Dann
     „App-Info öffnen“ → ⋮ oben rechts → „Eingeschränkte Einstellungen zulassen“
     und noch einmal versuchen.
   - **Radio-Code** eingeben → Speichern. Dadurch kann sich nur dein Radio verbinden.
   - **Querformat:** Erlaubnis „Über anderen Apps einblenden“ geben. Dann dreht sich
     das Handy beim Spiegeln ins Querformat, und das Bild füllt den Radio-Bildschirm.
3. Handy und Radio per **Bluetooth** koppeln (für Musik und Anrufe), falls noch nicht
   geschehen.

## Benutzen

1. Am Handy den **WLAN-Hotspot** einschalten. Das Radio verbindet sich damit (nach
   dem ersten Mal automatisch).
2. Am Handy in AutoSpiegel **„Spiegeln starten“** tippen und bestätigen.
3. Am Radio AutoSpiegel öffnen. Es findet das Handy von selbst.

Das Handy-Display bleibt beim Spiegeln gedimmt an. Am besten lädst du das Handy dabei.
Mit „Spiegeln beenden“ oder über die Benachrichtigung hörst du auf.

## Gut zu wissen

- **Wischen und Ziehen** werden ausgeführt, sobald du den Finger loslässt. Kurze
  Wischer fühlen sich normal an, langes Ziehen (z. B. eine Karte verschieben) kommt
  etwas verzögert an. Tippen geht sofort.
- **Ist die Schrift zu klein?** Dann am Handy unter Einstellungen → Anzeige die
  Schrift- bzw. Anzeigegröße erhöhen.
- Manche Apps (Banking, Netflix usw.) sperren Bildschirmaufnahmen. Sie bleiben am
  Radio schwarz.
- Falls das Radio das Handy nicht findet: Am Radio auf ⋮ tippen und die Handy-IP von
  Hand eintragen.

## Technik

- Das Handy nimmt den Bildschirm über `MediaProjection` auf, kodiert ihn als H.264
  (Baseline, 30 fps, max. 1280 px) und schickt ihn per TCP (Port 47800).
- Das Radio dekodiert mit `MediaCodec` direkt auf eine `SurfaceView` und schickt
  Touch-Ereignisse zurück. Das Handy führt sie als Bedienungshilfen-Geste aus
  (`dispatchGesture`).
- Das Radio findet das Handy über UDP-Broadcast (Port 47801) oder das Gateway des
  Hotspots. Die Kopplung läuft über den Radio-Code.
- Port 47802 liefert während des Spiegelns die APK für das Radio aus.

## Selbst bauen

```
export ANDROID_HOME=/pfad/zum/android-sdk
./gradlew assembleRelease testReleaseUnitTest
# → app/build/outputs/apk/release/app-release.apk
```

Der Signaturschlüssel in `signing/` ist absichtlich nicht geheim. So lässt sich jede
neue Version über die alte installieren.

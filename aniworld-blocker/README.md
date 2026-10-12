# Aniworld Blocker (Opera GX / Chromium, Manifest V3)

Blockt Werbung, Popups, neue Tabs, unsichtbare Overlays und Klick-Hijacking auf
aniworld.to – auch in den eingebetteten Hostern (VOE, Streamtape, Doodstream, Vidoza, Filemoon …).

## Installieren
1. `opera://extensions` öffnen
2. Oben rechts **Entwicklermodus** einschalten
3. **Entpackte Erweiterung laden** klicken und den Ordner `aniworld-blocker` wählen
4. (Optional) Icon an die Symbolleiste anheften. Auf aniworld.to einmal F5 drücken.

Nach jeder Code-Änderung: auf `opera://extensions` beim Blocker das Reload-Symbol klicken, dann die Seite neu laden.

## Wenn trotzdem etwas aufpoppt – Domain herausfinden
**A) Popup-Tab: Wohin wollte er?**
- In der Konsole (F12, Tab *Konsole*) siehst du `[AniBlock] window.open geblockt: <URL>` – das ist die Ziel-Domain.
- Beim Schließen von Tabs: `opera://extensions` → beim Blocker **Service Worker** (blauer Link) anklicken → Konsole zeigt `[AniBlock] Tab geschlossen: <URL>`.

**B) Anfragen anschauen:** F12 → Tab *Netzwerk* → Haken bei „Protokoll beibehalten“, dann Seite laden und klicken. Verdächtig sind Anfragen an unbekannte Domains direkt nach deinem Klick (Spalte „Domain“/„Initiator“).

**C) Overlay finden:** F12 → Pfeil-Symbol („Element auswählen“) → auf die Stelle klicken, wo der Klick „verschluckt“ wird. Ein `<div>`/`<a>` mit `position: fixed`, riesigem `z-index` und durchsichtigem Hintergrund ist das Overlay. Stehen Overlays im Hoster-Video: oben in der Konsole den Kontext (Dropdown „top“) auf den iframe umstellen.

**D) Domain zur Blockliste hinzufügen:** `rules/ads.json` öffnen und die Domain (ohne `https://` und ohne `www.`) in die Liste `requestDomains` eintragen, z.B. `"neue-werbe-domain.com"`. Erweiterung neu laden. Unterdomains sind automatisch mit abgedeckt.

## Grenzen / gut zu wissen
- Die Blockliste gilt **im ganzen Browser** (nicht nur auf Aniworld), weil Hoster-Domains ständig wechseln. Sie enthält nur Werbe-/Tracking-Netzwerke.
- Tabs werden nur geschlossen, wenn sie von Aniworld-/Hoster-Tabs geöffnet wurden **und** auf eine andere Domain zeigen. Öffnest du bewusst einen externen Link von Aniworld aus, wird er auch geschlossen → kurz im Popup ausschalten.
- Die Liste der Seiten in `background.js` (`SEITEN`) enthält ein paar Hoster; neue Hoster-Domains dort ergänzen.
- Der Treffer-Zähler für Netzwerk-Blocks funktioniert nur bei entpackt geladenen Erweiterungen.

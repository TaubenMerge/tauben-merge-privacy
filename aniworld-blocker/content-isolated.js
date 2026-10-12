// =====================================================================
// content-isolated.js  –  läuft in der "isolated world"
//
// Hier haben wir Zugriff auf chrome.* (Speicher, Nachrichten), sehen aber
// die JavaScript-Variablen der Seite nicht. Das DOM (die Elemente) ist
// dasselbe. Aufgaben:
//   - unsichtbare Overlays erkennen und entfernen
//   - neu eingefügte Werbe-Elemente per MutationObserver entfernen
//   - Klick-Wächter: Overlay unter dem Mauszeiger beim Klick beseitigen
//   - Treffer zählen und an den Service Worker melden
// =====================================================================
(function () {
  'use strict';

  // --- Nur auf Aniworld bzw. in Frames, die auf Aniworld eingebettet sind ---
  function obersterHost() {
    try {
      if (window.top === window) return location.hostname;
    } catch (e) {}
    const herkunft = location.ancestorOrigins;
    if (herkunft && herkunft.length) {
      try {
        return new URL(herkunft[herkunft.length - 1]).hostname;
      } catch (e) {}
    }
    return '';
  }
  if (!/^(www\.)?aniworld\.(to|info)$/.test(obersterHost())) return;

  let aktiv = true;

  // ===================================================================
  // Zähler: Treffer sammeln und gebündelt an background.js schicken
  // ===================================================================
  let ausstehend = 0;
  let sendeTimer = null;

  function zaehle(anzahl = 1) {
    ausstehend += anzahl;
    if (sendeTimer) return;
    sendeTimer = setTimeout(() => {
      const menge = ausstehend;
      ausstehend = 0;
      sendeTimer = null;
      try {
        chrome.runtime.sendMessage({ typ: 'geblockt', anzahl: menge });
      } catch (e) {
        // Passiert, wenn die Erweiterung neu geladen wurde -> einfach ignorieren
      }
    }, 500);
  }

  // Meldungen von content-main.js (z.B. "window.open geblockt")
  document.addEventListener('aniblock:geblockt', () => {
    if (aktiv) zaehle(1);
  });

  // ===================================================================
  // Overlay-Erkennung
  // ===================================================================

  // Elemente, die wir NIEMALS anfassen, weil sie zum Player gehören
  // (oder sonst etwas Bedienbares enthalten).
  const SICHERE_ELEMENTE =
    'video, audio, canvas, iframe, object, embed, input, button, select, textarea, [contenteditable]';

  // Der Player selbst hat oft eigene, durchsichtige Klick-Ebenen (Play/Pause).
  // Alles innerhalb solcher Container bleibt unangetastet.
  const PLAYER_CONTAINER =
    '.jwplayer, .video-js, .plyr, .vjs-tech, .flowplayer, .mejs__container, ' +
    '[class*="player" i], [id*="player" i]';

  // Eigene Dialoge der Seite (Login, Cookie-Hinweis ...) nicht löschen
  const SEITENEIGENE_NAMEN = /modal|backdrop|cookie|consent|lightbox|dialog/i;

  // Wie viel von Fläche b liegt unter Fläche a? (0 bis 1)
  function ueberdeckung(a, b) {
    const breite = Math.min(a.right, b.right) - Math.max(a.left, b.left);
    const hoehe = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
    if (breite <= 0 || hoehe <= 0 || b.width * b.height === 0) return 0;
    return (breite * hoehe) / (b.width * b.height);
  }

  // Ist dieses Element ein unsichtbares Klick-Overlay?
  function istOverlay(el) {
    if (!(el instanceof HTMLElement)) return false;
    if (el === document.body || el === document.documentElement) return false;

    // Vollbild: alles innerhalb des Vollbild-Elements bleibt unberührt
    const vollbild = document.fullscreenElement;
    if (vollbild && (el === vollbild || el.contains(vollbild) || vollbild.contains(el))) return false;

    // Player-Teile und bedienbare Elemente nie anfassen
    if (el.matches(SICHERE_ELEMENTE) || el.querySelector(SICHERE_ELEMENTE)) return false;
    if (el.closest(PLAYER_CONTAINER)) return false;
    if (SEITENEIGENE_NAMEN.test((el.className || '') + ' ' + (el.id || ''))) return false;

    // Liegt es neben einem <video>? Dann ist es sehr wahrscheinlich Teil des Players.
    const eltern = el.parentElement;
    if (
      eltern &&
      eltern !== document.body &&
      eltern !== document.documentElement &&
      eltern.querySelector('video')
    ) {
      return false;
    }

    const stil = getComputedStyle(el);
    if (stil.position !== 'fixed' && stil.position !== 'absolute') return false;
    if (stil.display === 'none' || stil.visibility === 'hidden') return false;
    if (stil.pointerEvents === 'none') return false; // fängt keine Klicks ab -> harmlos

    // Größe und z-index prüfen
    const z = parseInt(stil.zIndex, 10);
    const rechteck = el.getBoundingClientRect();
    const fastVollbild =
      rechteck.width >= window.innerWidth * 0.6 && rechteck.height >= window.innerHeight * 0.6;
    const hohesZ = !isNaN(z) && z >= 1000;

    // Zweiter Fall (Aniworld-Hauptseite): Ebene liegt fast exakt über dem Video-iframe
    const ueberIframe =
      !isNaN(z) &&
      z >= 1 &&
      eltern &&
      Array.from(eltern.children).some(
        (geschwister) =>
          geschwister.tagName === 'IFRAME' &&
          ueberdeckung(rechteck, geschwister.getBoundingClientRect()) >= 0.9
      );

    if (!(hohesZ && fastVollbild) && !ueberIframe) return false;

    // Durchsichtig? (Hintergrund transparent ohne Bild, oder opacity ~ 0)
    const bg = stil.backgroundColor;
    const transparenterHintergrund =
      (bg === 'transparent' || /rgba\(.*,\s*0\)$/.test(bg)) && stil.backgroundImage === 'none';
    const unsichtbar = parseFloat(stil.opacity) < 0.05;

    return transparenterHintergrund || unsichtbar;
  }

  // ===================================================================
  // Werbe-Elemente anhand von Stichwörtern in src/href
  // ===================================================================
  const WERBE_STICHWOERTER = [
    'doubleclick.net', 'googlesyndication.com', 'popads.net', 'popcash.net',
    'exoclick.com', 'exosrv.com', 'juicyads.com', 'adsterra', 'propellerads',
    'clickadu', 'hilltopads', 'onclickads', 'a-ads.com', 'mgid.com',
    'taboola.com', 'trafficjunky', 'adcash.com', 'highperformanceformat',
    'effectivegatecpm', 'profitablegatecpm',
  ];

  const WERBE_SELEKTOR = [
    'ins.adsbygoogle',
    '[id^="div-gpt-ad"]',
    ...WERBE_STICHWOERTER.map((w) => `iframe[src*="${w}"]`),
    ...WERBE_STICHWOERTER.map((w) => `img[src*="${w}"]`),
    ...WERBE_STICHWOERTER.map((w) => `a[href*="${w}"]`),
  ].join(',');

  // ===================================================================
  // Entfernen
  // ===================================================================
  const bereitsEntfernt = new WeakSet();

  function entferne(el, grund) {
    if (bereitsEntfernt.has(el)) return;
    bereitsEntfernt.add(el);
    // Diese Zeile hilft beim Debuggen: In der Konsole siehst du, WAS entfernt wurde.
    console.log('[AniBlock] entfernt (' + grund + '):', el);
    el.remove();
    zaehle(1);
  }

  // Ein einzelnes (neues) Element prüfen
  function pruefeElement(el) {
    if (!(el instanceof HTMLElement)) return;
    if (el.matches(WERBE_SELEKTOR)) {
      entferne(el, 'Werbe-Element');
      return;
    }
    el.querySelectorAll(WERBE_SELEKTOR).forEach((kind) => entferne(kind, 'Werbe-Element'));
    if (istOverlay(el)) entferne(el, 'Overlay');
  }

  // Die wichtigsten Stellen absuchen: direkte Kinder von <html>/<body> und
  // alles, was mitten auf dem Bildschirm liegt (dort sitzen Klick-Overlays).
  function ueberlagerungenSuchen() {
    if (!document.body) return;
    const kandidaten = new Set([...document.documentElement.children, ...document.body.children]);

    const punkte = [[0.5, 0.5], [0.25, 0.5], [0.75, 0.5], [0.5, 0.25], [0.5, 0.75]];
    for (const [x, y] of punkte) {
      let el = document.elementFromPoint(window.innerWidth * x, window.innerHeight * y);
      while (el && el !== document.body && el !== document.documentElement) {
        kandidaten.add(el);
        el = el.parentElement;
      }
    }
    kandidaten.forEach((el) => {
      if (istOverlay(el)) entferne(el, 'Overlay');
    });
  }

  // ===================================================================
  // MutationObserver: reagiert sofort, wenn die Seite etwas Neues einfügt
  // ===================================================================
  let beobachter = null;
  let intervall = null;
  let neueKnoten = new Set();
  let verarbeitungsTimer = null;

  function neueKnotenVerarbeiten() {
    verarbeitungsTimer = null;
    const liste = neueKnoten;
    neueKnoten = new Set();
    liste.forEach((el) => {
      if (el.isConnected) pruefeElement(el);
    });
  }

  function starte() {
    if (beobachter) return;

    beobachter = new MutationObserver((aenderungen) => {
      for (const a of aenderungen) {
        a.addedNodes.forEach((knoten) => {
          if (knoten.nodeType === 1) neueKnoten.add(knoten);
        });
      }
      // Kurz bündeln (30 ms), damit viele Änderungen auf einmal nicht bremsen
      if (!verarbeitungsTimer) verarbeitungsTimer = setTimeout(neueKnotenVerarbeiten, 30);
    });
    beobachter.observe(document, { childList: true, subtree: true });

    // Zusätzlich jede Sekunde nachsehen (für Elemente, die erst nachträglich
    // per Style-Änderung zum Overlay werden)
    intervall = setInterval(() => {
      if (!document.hidden) ueberlagerungenSuchen();
    }, 1000);

    // Was beim Start schon da ist, direkt prüfen
    if (document.documentElement) pruefeElement(document.documentElement);
  }

  function stoppe() {
    if (beobachter) beobachter.disconnect();
    beobachter = null;
    clearInterval(intervall);
    intervall = null;
  }

  // ===================================================================
  // Klick-Wächter: ganz früh (Capture-Phase) auf jeden Klick reagieren
  // ===================================================================
  function klickWaechter(e) {
    if (!aktiv) return;
    const ziel = e.target;
    if (!(ziel instanceof HTMLElement)) return;

    // Landet der Klick auf einem unsichtbaren Overlay? Dann Overlay weg,
    // Klick schlucken -> der nächste Klick erreicht den echten Player.
    if (istOverlay(ziel)) {
      e.preventDefault();
      e.stopImmediatePropagation();
      entferne(ziel, 'Klick-Overlay');
      return;
    }

    // Vom Skript erzeugter ("unechter") Klick auf einen Fremd-Link im neuen Tab
    const link = ziel.closest('a[target="_blank"]');
    if (link && !e.isTrusted) {
      try {
        if (new URL(link.href, location.href).hostname !== location.hostname) {
          e.preventDefault();
          e.stopImmediatePropagation();
          zaehle(1);
          console.log('[AniBlock] Skript-Klick auf Fremd-Link geblockt:', link.href);
        }
      } catch (err) {}
    }
  }
  ['pointerdown', 'mousedown', 'click'].forEach((name) =>
    window.addEventListener(name, klickWaechter, true)
  );

  // ===================================================================
  // Ein/Aus-Zustand
  // ===================================================================
  function zustandSetzen(an) {
    aktiv = an;
    // content-main.js informieren (der hat keinen Zugriff auf chrome.storage)
    document.dispatchEvent(new CustomEvent('aniblock:status', { detail: an ? 'an' : 'aus' }));
    if (an) starte();
    else stoppe();
  }

  // Standardmäßig sofort loslegen, dann ggf. korrigieren
  starte();
  chrome.storage.local.get('aktiv').then(({ aktiv: gespeichert = true }) => {
    if (gespeichert === false) zustandSetzen(false);
  });
  chrome.storage.onChanged.addListener((aenderungen, bereich) => {
    if (bereich === 'local' && aenderungen.aktiv) zustandSetzen(aenderungen.aktiv.newValue !== false);
  });
})();

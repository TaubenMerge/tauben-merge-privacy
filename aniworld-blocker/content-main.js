// =====================================================================
// content-main.js  –  läuft in der "MAIN world"
//
// MAIN world = derselbe JavaScript-Raum wie die Webseite selbst.
// Nur hier können wir window.open überschreiben, sonst sieht die Seite
// unsere Änderung nicht. Dafür haben wir hier KEINEN Zugriff auf chrome.*.
// Gemeldet wird deshalb über DOM-Events an content-isolated.js.
// =====================================================================
(function () {
  'use strict';

  // --- Nur auf Aniworld bzw. in Frames, die auf Aniworld eingebettet sind ---
  // Die oberste Seite ist bei iframes der letzte Eintrag in ancestorOrigins.
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

  // --- Ein/Aus-Zustand (wird von content-isolated.js gesetzt) ---
  let aktiv = true;
  document.addEventListener('aniblock:status', (e) => {
    aktiv = e.detail === 'an';
  });

  // Dem anderen Skript melden: "wieder etwas geblockt" (für den Zähler)
  function melde(grund) {
    try {
      document.dispatchEvent(new CustomEvent('aniblock:geblockt', { detail: String(grund) }));
    } catch (e) {}
  }

  // --- 1. window.open überschreiben ---
  const originalOpen = window.open;

  // Manche Popup-Skripte rufen danach w.focus(), w.blur() oder w.document.write()
  // auf. Würden wir null zurückgeben, gäbe das Fehler (oder sie versuchen einen
  // Ersatzweg). Darum liefern wir ein harmloses Schein-Fenster.
  function scheinFenster() {
    const nichts = function () {};
    return {
      closed: true,
      focus: nichts,
      blur: nichts,
      close: nichts,
      postMessage: nichts,
      document: { write: nichts, writeln: nichts, open: nichts, close: nichts },
      location: { href: '', assign: nichts, replace: nichts },
    };
  }

  function neuesOpen() {
    if (!aktiv) return originalOpen.apply(window, arguments);
    melde('window.open');
    console.log('[AniBlock] window.open geblockt:', arguments[0]);
    return scheinFenster();
  }

  // Per Getter/Setter festnageln: Die Seite kann window.open weder zurücksetzen
  // noch beim Versuch einen Fehler auslösen (Zuweisungen werden still ignoriert).
  try {
    Object.defineProperty(window, 'open', {
      get: () => neuesOpen,
      set: () => {},
      configurable: false,
    });
  } catch (e) {
    window.open = neuesOpen;
  }

  // --- 2. Unsichtbare Links per Skript anklicken: <a target="_blank">.click() ---
  // Beliebter Trick: Ein Skript baut einen Link zusammen und "klickt" ihn selbst.
  const originalKlick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    try {
      const fremd = new URL(this.href, location.href).hostname !== location.hostname;
      if (aktiv && this.target === '_blank' && fremd) {
        melde('a.click');
        console.log('[AniBlock] Skript-Klick auf Fremd-Link geblockt:', this.href);
        return;
      }
    } catch (e) {}
    return originalKlick.apply(this, arguments);
  };

  // --- 3. Formulare, die per Skript in einen neuen Tab abgeschickt werden ---
  const originalSubmit = HTMLFormElement.prototype.submit;
  HTMLFormElement.prototype.submit = function () {
    if (aktiv && this.target === '_blank') {
      melde('form.submit');
      console.log('[AniBlock] Formular in neuem Tab geblockt:', this.action);
      return;
    }
    return originalSubmit.apply(this, arguments);
  };
})();

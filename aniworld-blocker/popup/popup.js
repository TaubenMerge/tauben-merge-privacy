// =====================================================================
// popup.js  –  Logik für das kleine Fenster beim Klick aufs Icon
// =====================================================================

const schalter = document.getElementById('schalter');
const zahl = document.getElementById('zahl');
const zuruecksetzen = document.getElementById('zuruecksetzen');

// Beim Öffnen: gespeicherte Werte anzeigen
chrome.storage.local.get(['aktiv', 'zaehler']).then(({ aktiv = true, zaehler = 0 }) => {
  schalter.checked = aktiv;
  zahl.textContent = zaehler;
});

// Schalter -> in den Speicher schreiben (background.js und die Content-Skripte reagieren darauf)
schalter.addEventListener('change', () => {
  chrome.storage.local.set({ aktiv: schalter.checked });
});

// Zähler bei offenem Popup live aktualisieren
chrome.storage.onChanged.addListener((aenderungen, bereich) => {
  if (bereich === 'local' && aenderungen.zaehler) {
    zahl.textContent = aenderungen.zaehler.newValue;
  }
});

zuruecksetzen.addEventListener('click', () => {
  chrome.storage.local.set({ zaehler: 0 });
});

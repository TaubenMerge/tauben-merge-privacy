// =====================================================================
// background.js  –  Service Worker (läuft im Hintergrund, ohne Fenster)
//
// Aufgaben:
//  1. Ein/Aus-Zustand verwalten und die Blockliste (rules/ads.json) schalten
//  2. Neue Tabs schließen, die ein Aniworld-/Hoster-Tab geöffnet hat und die
//     auf eine fremde Domain zeigen (Popups / Popunder)
//  3. Den Zähler für geblockte Elemente führen
// =====================================================================

// Seiten, von denen aus geöffnete Fremd-Tabs automatisch geschlossen werden.
// Subdomains zählen automatisch mit (z.B. www.aniworld.to).
// Hoster-Domains wechseln oft – ergänze hier einfach neue.
const SEITEN = [
  'aniworld.to',
  'aniworld.info',
  'voe.sx',
  'streamtape.com',
  'doodstream.com',
  'dood.wf',
  'vidoza.net',
  'filemoon.sx',
];

// ---------------------------------------------------------------------
// Hilfsfunktionen
// ---------------------------------------------------------------------

// "https://www.aniworld.to/x" -> "www.aniworld.to"
function hostVon(url) {
  try {
    return new URL(url).hostname;
  } catch (e) {
    return '';
  }
}

// "www.aniworld.to" -> "aniworld.to" (einfache Variante: letzte zwei Teile)
function basisDomain(host) {
  return host.split('.').slice(-2).join('.');
}

function istErlaubteSeite(host) {
  return SEITEN.some((s) => host === s || host.endsWith('.' + s));
}

// Liest, ob die Erweiterung eingeschaltet ist (Standard: ein)
async function istAktiv() {
  const { aktiv = true } = await chrome.storage.local.get('aktiv');
  return aktiv;
}

// ---------------------------------------------------------------------
// 1. Ein/Aus: Blockliste und Badge ("AUS") anpassen
// ---------------------------------------------------------------------

async function zustandAnwenden(aktiv) {
  await chrome.declarativeNetRequest.updateEnabledRulesets(
    aktiv ? { enableRulesetIds: ['ads'] } : { disableRulesetIds: ['ads'] }
  );
  await chrome.action.setBadgeText({ text: aktiv ? '' : 'AUS' });
  await chrome.action.setBadgeBackgroundColor({ color: '#888888' });
}

chrome.runtime.onInstalled.addListener(async () => {
  const { aktiv } = await chrome.storage.local.get('aktiv');
  if (aktiv === undefined) await chrome.storage.local.set({ aktiv: true, zaehler: 0 });
  zustandAnwenden(await istAktiv());
});

chrome.runtime.onStartup.addListener(async () => {
  zustandAnwenden(await istAktiv());
});

// Das Popup schreibt "aktiv" in den Speicher -> wir reagieren hier darauf
chrome.storage.onChanged.addListener((aenderungen, bereich) => {
  if (bereich === 'local' && aenderungen.aktiv) {
    zustandAnwenden(aenderungen.aktiv.newValue !== false);
  }
});

// ---------------------------------------------------------------------
// 3. Zähler (vor 2., weil 2. ihn benutzt)
// Wir sammeln Treffer kurz und schreiben sie gebündelt in den Speicher,
// sonst gäbe es hunderte Schreibzugriffe pro Sekunde.
// ---------------------------------------------------------------------

let ausstehend = 0;
let zaehlerTimer = null;

function zaehlerErhoehen(anzahl = 1) {
  ausstehend += anzahl;
  if (zaehlerTimer) return;
  zaehlerTimer = setTimeout(async () => {
    const menge = ausstehend;
    ausstehend = 0;
    zaehlerTimer = null;
    const { zaehler = 0 } = await chrome.storage.local.get('zaehler');
    await chrome.storage.local.set({ zaehler: zaehler + menge });
  }, 500);
}

// Meldungen der Content-Skripte (Overlay entfernt, Popup geblockt, ...)
chrome.runtime.onMessage.addListener((nachricht) => {
  if (nachricht && nachricht.typ === 'geblockt') {
    zaehlerErhoehen(nachricht.anzahl || 1);
  }
});

// Netzwerk-Treffer der Blockliste zählen.
// Hinweis: Das funktioniert nur bei "entpackten" Erweiterungen (genau dein Fall).
if (chrome.declarativeNetRequest.onRuleMatchedDebug) {
  chrome.declarativeNetRequest.onRuleMatchedDebug.addListener(() => zaehlerErhoehen(1));
}

// ---------------------------------------------------------------------
// 2. Popup-/Popunder-Tabs schließen
// ---------------------------------------------------------------------

// Tabs, deren Ziel-URL noch nicht bekannt ist (oft startet ein Popup als
// "about:blank" und bekommt die echte Adresse erst Sekundenbruchteile später)
const beobachtet = new Map(); // tabId -> Host des öffnenden Tabs

async function pruefeTab(tabId, url, openerHost) {
  // Interne Seiten (chrome://, opera://, about:) lassen wir in Ruhe
  if (!/^https?:/i.test(url)) return;

  const zielHost = hostVon(url);
  const gleicheDomain = basisDomain(zielHost) === basisDomain(openerHost);
  if (gleicheDomain || istErlaubteSeite(zielHost)) return; // harmlos

  try {
    await chrome.tabs.remove(tabId);
    console.log('[AniBlock] Tab geschlossen:', url);
    zaehlerErhoehen(1);
  } catch (e) {
    // Tab war schon weg – kein Problem
  }
}

chrome.tabs.onCreated.addListener(async (tab) => {
  if (!(await istAktiv())) return;
  if (tab.openerTabId == null) return; // vom Nutzer selbst geöffnet (Strg+T) -> ignorieren

  let opener;
  try {
    opener = await chrome.tabs.get(tab.openerTabId);
  } catch (e) {
    return;
  }

  const openerHost = hostVon(opener.url);
  if (!istErlaubteSeite(openerHost)) return; // nur Aniworld-/Hoster-Tabs überwachen

  const url = tab.pendingUrl || tab.url || '';
  if (!url || url === 'about:blank') {
    beobachtet.set(tab.id, openerHost); // Ziel noch unbekannt -> später prüfen
  } else {
    pruefeTab(tab.id, url, openerHost);
  }
});

chrome.tabs.onUpdated.addListener((tabId, info, tab) => {
  if (!beobachtet.has(tabId)) return;
  const url = info.url || tab.pendingUrl || '';
  if (!/^https?:/i.test(url)) return; // noch about:blank -> weiter warten
  const openerHost = beobachtet.get(tabId);
  beobachtet.delete(tabId);
  pruefeTab(tabId, url, openerHost);
});

chrome.tabs.onRemoved.addListener((tabId) => beobachtet.delete(tabId));

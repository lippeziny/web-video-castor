(function () {
  if (window.__castorInstalled) { return; }
  window.__castorInstalled = true;

  var EXT = /\.(m3u8|mp4|mpd|webm|m4v|mov|mkv|flv|mp3|m4a|ogg|wav)(?:$|[?#&])/i;
  var URLRE = /https?:(?:\\?\/){2}[^\s"'<>\\)]+?\.(?:m3u8|mp4|mpd|webm|m4v|mov|mkv|flv)(?:\?[^\s"'<>\\)]*)?/gi;
  var seen = {};
  var seenFrames = {};
  var metadataByUrl = {};

  function clean(u) {
    return u.replace(/\\\//g, '/').replace(/\\u0026/g, '&').replace(/&amp;/g, '&');
  }
  function abs(u) {
    try { return new URL(u, document.baseURI).href; } catch (e) { return null; }
  }
  function add(list, u, meta) {
    if (!u || typeof u !== 'string') { return; }
    if (u.indexOf('blob:') === 0) { try { CastorBridge.onBlob(); } catch (e) {} return; }
    if (u.indexOf('data:') === 0) { return; }
    var a = abs(clean(u));
    if (!a || !/^https?:/i.test(a)) { return; }
    if (!seen[a]) { seen[a] = 1; list.push(a); }
    if (meta) {
      var old = metadataByUrl[a] || {url:a, quality:'', language:''};
      if (meta.quality) old.quality = meta.quality;
      if (meta.language) old.language = meta.language;
      old.url = a;
      metadataByUrl[a] = old;
    } else if (!metadataByUrl[a]) {
      metadataByUrl[a] = {url:a, quality:'', language:''};
    }
  }

  function metaForVideo(v) {
    var w = Number(v.videoWidth || 0);
    var h = Number(v.videoHeight || 0);
    var q = '';
    if (w > 0 && h > 0) {
      var longSide = Math.max(w, h);
      var p = longSide >= 3800 ? '4K' : (longSide >= 2500 ? '1440p' : (longSide >= 1900 ? '1080p' : (longSide >= 1200 ? '720p' : (longSide >= 850 ? '480p' : ''))));
      q = w + '×' + h + (p ? ' (' + p + ')' : '');
    }
    var lang = '';
    try {
      if (v.audioTracks && v.audioTracks.length && v.audioTracks[0].language) {
        lang = 'Áudio: ' + v.audioTracks[0].language;
      }
    } catch (e) {}
    if (!lang) {
      try {
        var tracks = v.querySelectorAll('track[kind="captions"],track[kind="subtitles"]');
        if (tracks.length && tracks[0].srclang) lang = 'Legenda: ' + tracks[0].srclang;
      } catch (e) {}
    }
    return {quality:q, language:lang};
  }

  function scanDoc(doc, list, force) {
    var i, j, els;
    els = doc.querySelectorAll('video,audio');
    for (i = 0; i < els.length; i++) {
      var v = els[i];
      var vm = metaForVideo(v);
      add(list, v.currentSrc, vm); add(list, v.src, vm);
      add(list, v.getAttribute('data-src'), vm); add(list, v.getAttribute('data-video-src'), vm);
      var srcs = v.querySelectorAll('source');
      for (j = 0; j < srcs.length; j++) { add(list, srcs[j].src, vm); }
      if (force) {
        try {
          var wasPaused = v.paused;
          v.muted = true;
          var p = v.play();
          if (p && p.catch) { p.catch(function () {}); }
          if (wasPaused) { setTimeout(function () { try { v.pause(); } catch (e) {} }, 1800); }
        } catch (e) {}
      }
    }
    els = doc.querySelectorAll('source[src]');
    for (i = 0; i < els.length; i++) { add(list, els[i].src); }
    els = doc.querySelectorAll('a[href]');
    for (i = 0; i < els.length; i++) { if (EXT.test(els[i].href)) { add(list, els[i].href); } }
    els = doc.querySelectorAll('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"],meta[name="twitter:player:stream"]');
    for (i = 0; i < els.length; i++) { add(list, els[i].content); }
    try {
      var html = doc.documentElement.innerHTML;
      var m = html.match(URLRE);
      if (m) { for (i = 0; i < m.length && i < 200; i++) { add(list, m[i]); } }
    } catch (e) {}
  }

  function scanFrames(doc, list, frames, force, depth) {
    if (depth > 4) { return; }
    var fr = doc.querySelectorAll('iframe');
    for (var i = 0; i < fr.length; i++) {
      var f = fr[i];
      try {
        var d = f.contentDocument;
        if (d) { scanDoc(d, list, force); scanFrames(d, list, frames, force, depth + 1); }
      } catch (e) {}
      var s = f.src;
      if (s && /^https?:/i.test(s) && !seenFrames[s]) { seenFrames[s] = 1; frames.push(s); }
    }
  }

  function scan(force) {
    try {
      var list = [], frames = [];
      scanDoc(document, list, force);
      scanFrames(document, list, frames, force, 0);
      try {
        var pe = performance.getEntriesByType('resource');
        for (var i = 0; i < pe.length; i++) { if (EXT.test(pe[i].name)) { add(list, pe[i].name); } }
      } catch (e) {}
      var allUrls = Object.keys(seen).slice(0, 200);
      var details = [];
      for (var k = 0; k < allUrls.length; k++) {
        var u = allUrls[k];
        var md = metadataByUrl[u] || {url:u, quality:'', language:''};
        details.push({url:u, quality:md.quality || '', language:md.language || ''});
      }
      try {
        if (CastorBridge.onMediaDetails) CastorBridge.onMediaDetails(JSON.stringify(details));
        else if (list.length) CastorBridge.onMedia(JSON.stringify(list));
      } catch (e) {}
      if (force) {
        if (frames.length) { CastorBridge.onFrames(JSON.stringify(frames)); }
        setTimeout(function () { try { CastorBridge.onScanDone(allUrls.length, frames.length); } catch (e) {} }, 1900);
      }
    } catch (e) {}
  }
  window.__castorScan = scan;

  // Pop-ups via window.open: bloqueia quando a proteção está ligada, exceto cliques reais no mesmo site.
  var origOpen = window.open;
  function reg(h) { var p = h.split('.'); return p.slice(-2).join('.'); }
  window.open = function (u) {
    try {
      if (CastorBridge.popupBlocked()) {
        var allowed = false;
        try {
          var active = navigator.userActivation && navigator.userActivation.isActive;
          var target = new URL(String(u || ''), location.href);
          if (active && reg(target.hostname) === reg(location.hostname)) { allowed = true; }
        } catch (e) {}
        if (!allowed) { CastorBridge.onPopup(String(u || '')); return null; }
      }
    } catch (e) {}
    return origOpen.apply(window, arguments);
  };

  var timer = null;
  function debounced() { if (timer) { clearTimeout(timer); } timer = setTimeout(function () { scan(false); }, 1200); }
  try { new MutationObserver(debounced).observe(document.documentElement, { childList: true, subtree: true }); } catch (e) {}
  setInterval(function () { scan(false); }, 4000);
  scan(false);
})();

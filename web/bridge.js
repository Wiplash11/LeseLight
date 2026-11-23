/* Leselicht Android bridge: native voices, Claude API, file saving. Loaded before the app script. */
(function(){
  "use strict";
  var A = window.LeselichtNative;
  if(!A) return;

  // ---- speechSynthesis on top of Android TextToSpeech ----
  var pending = {}, seq = 0, voices = [];
  function loadVoices(){
    try{
      voices = JSON.parse(A.getVoices()).map(function(v){
        return {name: v.label, voiceURI: v.name, lang: v.lang, localService: !v.network, default: false, quality: v.quality};
      });
    }catch(e){ voices = []; }
  }
  var synth = {
    onvoiceschanged: null, speaking: false, pending: false, paused: false,
    getVoices: function(){ if(!voices.length) loadVoices(); return voices; },
    speak: function(u){
      var id = "u" + (++seq); pending[id] = u;
      A.speak(id, String(u.text || ""), u.voice ? u.voice.voiceURI : "", u.lang || "", +u.rate || 1, +u.pitch || 1);
    },
    cancel: function(){ pending = {}; A.stop(); },
    pause: function(){ A.stop(); }, resume: function(){}
  };
  window.__leselichtTts = function(id, ev, err){
    var u = pending[id]; if(!u) return;
    if(ev === "start"){ if(u.onstart) u.onstart({}); return; }
    delete pending[id];
    if(ev === "end" && u.onend) u.onend({});
    if(ev === "error" && u.onerror) u.onerror({error: err || "synthesis-failed"});
  };
  window.__leselichtVoices = function(){ loadVoices(); if(typeof synth.onvoiceschanged === "function") synth.onvoiceschanged(); };
  try{ Object.defineProperty(window, "speechSynthesis", {value: synth, configurable: true, writable: true}); }catch(e){ window.speechSynthesis = synth; }
  window.SpeechSynthesisUtterance = function(t){ this.text = t; this.voice = null; this.lang = ""; this.rate = 1; this.pitch = 1; this.onend = null; this.onerror = null; this.onstart = null; };

  // ---- Claude API (key stays on the phone) ----
  var MODEL = "claude-sonnet-5-5";
  var http = {}, hseq = 0;
  window.__leselichtHttp = function(id, status, text){ var cb = http[id]; delete http[id]; if(cb) cb(status, text); };
  function post(url, headers, body){
    return new Promise(function(res){ var id = "h" + (++hseq); http[id] = function(s, t){ res({status: s, text: t}); }; A.post(id, url, JSON.stringify(headers), JSON.stringify(body)); });
  }
  function key(){ try{ return (localStorage.getItem("leselicht.apikey") || "").trim(); }catch(e){ return ""; } }
  function err(code, message, text){ var e = new Error(message); e.code = code; if(text) e.text = text; return e; }
  function toJpegBase64(blob){
    return new Promise(function(res, rej){
      var url = URL.createObjectURL(blob), img = new Image();
      img.onload = function(){
        var max = 1800, w = img.naturalWidth, h = img.naturalHeight, k = Math.min(1, max / Math.max(w, h));
        var c = document.createElement("canvas"); c.width = Math.round(w * k); c.height = Math.round(h * k);
        c.getContext("2d").drawImage(img, 0, 0, c.width, c.height); URL.revokeObjectURL(url);
        res(c.toDataURL("image/jpeg", 0.85).split(",")[1]);
      };
      img.onerror = function(){ URL.revokeObjectURL(url); rej(err("image_rejected", "Bild konnte nicht gelesen werden")); };
      img.src = url;
    });
  }
  async function sample(input, opts){
    opts = opts || {};
    var k = key();
    if(!k) throw err("no_key", "Bitte trage in den Einstellungen (Regler-Symbol oben rechts) deinen Claude-API-Schlüssel ein.");
    if(opts.signal && opts.signal.aborted) throw err("cancelled", "Abgebrochen");
    var prompt = typeof input === "string" ? input : input.map(function(m){ return m.content; }).join("\n\n");
    var content = [];
    var imgs = opts.images ? Array.from(opts.images.length !== undefined ? opts.images : [opts.images]) : [];
    for(var i = 0; i < imgs.length; i++) content.push({type: "image", source: {type: "base64", media_type: "image/jpeg", data: await toJpegBase64(imgs[i])}});
    content.push({type: "text", text: prompt});
    var r = await post("https://api.anthropic.com/v1/messages",
      {"x-api-key": k, "anthropic-version": "2023-06-01", "content-type": "application/json"},
      {model: MODEL, max_tokens: 16000, messages: [{role: "user", content: content}]});
    if(opts.signal && opts.signal.aborted) throw err("cancelled", "Abgebrochen");
    var data = null; try{ data = JSON.parse(r.text); }catch(e){}
    if(r.status === 401 || r.status === 403) throw err("no_key", "Der Claude-API-Schlüssel wurde nicht akzeptiert. Prüfe ihn in den Einstellungen.");
    if(r.status === 429 || r.status === 529) throw err("rate_limited", "Gerade zu viele Anfragen.");
    if(r.status === 0) throw err("upstream_error", "Keine Internetverbindung.");
    if(r.status >= 400 || !data) throw err("upstream_error", (data && data.error && data.error.message) || ("Fehler " + r.status));
    var text = (data.content || []).filter(function(b){ return b.type === "text"; }).map(function(b){ return b.text; }).join("");
    if(opts.onText && text) try{ opts.onText({text: text, delta: text}); }catch(e){}
    return {text: text, truncated: data.stop_reason === "max_tokens"};
  }
  sample.json = async function(input, opts){
    var r = await sample(input, opts), t = r.text.trim();
    var f = t.match(/```(?:json)?\s*([\s\S]*?)```/); if(f) t = f[1];
    try{ return JSON.parse(t); }catch(e){}
    var a = t.search(/[\[{]/), b = Math.max(t.lastIndexOf("}"), t.lastIndexOf("]"));
    try{ return JSON.parse(t.slice(a, b + 1)); }catch(e){ throw err("invalid_json", "Antwort war kein JSON", r.text); }
  };
  sample.limits = async function(){ return {images: {maxCount: 5, mediaTypes: ["image/jpeg", "image/png", "image/webp"]}}; };

  var downloads = {
    save: async function(o){
      var data = typeof o.data === "string" ? o.data : await new Blob([o.data]).text();
      var p = A.saveText(o.filename, data);
      if(!p) throw err("unavailable", "Speichern fehlgeschlagen");
      return {filename: p};
    }
  };

  window.claude = { use: function(name){ return Promise.resolve(name === "sample" ? sample : name === "downloads" ? downloads : null); } };
})();

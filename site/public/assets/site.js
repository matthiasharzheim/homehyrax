// HomeHyrax – Leiterbahnen im Hintergrund, Menue, aktiver Eintrag im Inhaltsverzeichnis
(function () {
  "use strict";
  var NS = "http://www.w3.org/2000/svg";

  // fester Zufall, damit die Bahnen bei jedem Laden gleich aussehen
  function rng(seed) {
    return function () {
      seed = (seed * 1664525 + 1013904223) >>> 0;
      return seed / 4294967296;
    };
  }

  function el(name, attrs) {
    var e = document.createElementNS(NS, name);
    for (var k in attrs) e.setAttribute(k, attrs[k]);
    return e;
  }

  // Bahn: waagerecht vom Rand, ein 45-Grad-Knick, wieder waagerecht, Knoten am Ende
  function circuit(box) {
    var w = box.clientWidth, h = box.clientHeight;
    if (!w || !h) return;
    var r = rng(+(box.getAttribute("data-seed") || 7));
    var svg = el("svg", { viewBox: "0 0 " + w + " " + h, preserveAspectRatio: "none", "aria-hidden": "true" });
    var n = Math.max(6, Math.round(h / 70));
    var pulses = 0;
    for (var i = 0; i < n; i++) {
      var left = i % 2 === 0;
      var y = (h * (i + 0.5)) / n + (r() - 0.5) * 30;
      var x0 = left ? -10 : w + 10;
      var dir = left ? 1 : -1;
      var len1 = w * (0.06 + r() * 0.16);
      var dy = (r() < 0.5 ? -1 : 1) * (20 + r() * 50);
      var len2 = w * (0.04 + r() * 0.12);
      var x1 = x0 + dir * len1, x2 = x1 + dir * Math.abs(dy), y2 = y + dy, x3 = x2 + dir * len2;
      var d = "M" + x0 + " " + y + " H" + x1 + " L" + x2 + " " + y2 + " H" + x3;
      svg.appendChild(el("path", { d: d, class: "g" }));
      svg.appendChild(el("path", { d: d, class: "t" }));
      svg.appendChild(el("circle", { cx: x3, cy: y2, r: 4.5, class: "n" }));
      if (r() < 0.45) {
        // Abzweig mit eigenem Knoten
        var bx = x1 + dir * (len1 * 0.0), by = y + (dy > 0 ? -1 : 1) * (16 + r() * 22);
        var b = "M" + (x0 + dir * len1 * 0.55) + " " + y + " L" + (x0 + dir * len1 * 0.55 + dir * Math.abs(by - y)) + " " + by + " H" + (bx + dir * 30);
        svg.appendChild(el("path", { d: b, class: "t" }));
        svg.appendChild(el("circle", { cx: bx + dir * 30, cy: by, r: 3.5, class: "n" }));
      }
      if (pulses < 4 && r() < 0.55) {
        pulses++;
        var p = el("path", { d: d, class: "p" });
        p.style.animationDelay = (-r() * 7).toFixed(2) + "s";
        p.style.animationDuration = (5 + r() * 4).toFixed(2) + "s";
        svg.appendChild(p);
      }
    }
    box.innerHTML = "";
    box.appendChild(svg);
  }

  var boxes = document.querySelectorAll(".circuit");
  function drawAll() { for (var i = 0; i < boxes.length; i++) circuit(boxes[i]); }
  drawAll();
  var t;
  window.addEventListener("resize", function () { clearTimeout(t); t = setTimeout(drawAll, 200); });

  // Menue auf dem Handy
  var btn = document.querySelector(".menu-btn"), nav = document.querySelector(".nav");
  if (btn && nav) {
    btn.addEventListener("click", function () {
      var open = nav.classList.toggle("open");
      btn.setAttribute("aria-expanded", open ? "true" : "false");
    });
    nav.addEventListener("click", function (e) {
      if (e.target.tagName === "A") { nav.classList.remove("open"); btn.setAttribute("aria-expanded", "false"); }
    });
  }

  // Inhaltsverzeichnis der Anleitung: aktuellen Abschnitt markieren
  var links = document.querySelectorAll(".toc a");
  if (links.length && "IntersectionObserver" in window) {
    var map = {};
    for (var i = 0; i < links.length; i++) map[links[i].getAttribute("href").slice(1)] = links[i];
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting && map[en.target.id]) {
          for (var j = 0; j < links.length; j++) links[j].classList.remove("on");
          map[en.target.id].classList.add("on");
        }
      });
    }, { rootMargin: "-20% 0px -70% 0px" });
    Object.keys(map).forEach(function (id) { var s = document.getElementById(id); if (s) io.observe(s); });
  }

  // Video im Handy oben: erst beim Klick laden (kein Datenverbrauch vorher), selbst gehostet (keine Dritten).
  // Alle Tasten mit data-play starten dasselbe Video und scrollen zum Handy.
  var vbtn = document.querySelector(".vplay");
  if (vbtn) {
    var screen = vbtn.parentNode, stage = screen.closest(".stage"), video = null;
    function play(e) {
      if (e) e.preventDefault();
      if (!video) {
        video = document.createElement("video");
        video.src = vbtn.getAttribute("data-src");
        video.poster = vbtn.getAttribute("data-poster");
        video.controls = true;
        video.playsInline = true;
        video.setAttribute("playsinline", "");
        video.addEventListener("ended", function () { stage.classList.remove("playing"); });
        screen.innerHTML = "";
        screen.appendChild(video);
        // Tastatur: der Knopf ist weg, Fokus auf das Video statt auf body
        if (e && e.currentTarget === vbtn) video.focus();
      }
      stage.classList.add("playing");
      var still = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      stage.scrollIntoView({ behavior: still ? "auto" : "smooth", block: "center" });
      var p = video.play();
      if (p && p.catch) p.catch(function () {});
    }
    var starts = document.querySelectorAll("[data-play]");
    for (var k = 0; k < starts.length; k++) starts[k].addEventListener("click", play);
  }
})();

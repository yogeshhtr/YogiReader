/* Yogi Reader: added to every book page (EPUB chapter, Word, Excel, PowerPoint, Kindle, text). */
(function () {
  if (window.YR) return;
  var YR = window.YR = {};
  var style = document.createElement('style');
  (document.head || document.documentElement).appendChild(style);

  YR.theme = function (bg, fg, link, size, family, line, margin) {
    style.textContent =
      'html,body{background:' + bg + ' !important;color:' + fg + ' !important;}' +
      'body{font-size:' + size + 'px !important;line-height:' + line + ' !important;margin:0 !important;' +
      'padding:70px ' + margin + 'px 110px ' + margin + 'px !important;word-wrap:break-word;-webkit-text-size-adjust:none;}' +
      (family ? 'body,p,div,span,li,td,th,blockquote,h1,h2,h3,h4,h5,h6{font-family:' + family + ' !important;}' : '') +
      'p,div,span,li,td,th,blockquote,h1,h2,h3,h4,h5,h6,section,article{color:inherit !important;background-color:transparent !important;}' +
      'p,li{line-height:inherit !important;}' +
      'a{color:' + link + ' !important;}' +
      'img,svg,video,canvas{max-width:100% !important;height:auto !important;}' +
      'pre{white-space:pre-wrap !important;}' +
      'table{border-collapse:collapse;max-width:100%;}td,th{border:1px solid rgba(128,128,128,.35);padding:4px 7px;vertical-align:top;}' +
      '.yr-scroll{overflow-x:auto;}' +
      '.yr-slide{border:1px solid rgba(128,128,128,.35);border-radius:14px;padding:14px 16px;margin:14px 0;}' +
      '.yr-nav{display:block;text-align:center;margin:36px 0 10px;padding:14px;border-radius:14px;border:1px solid rgba(128,128,128,.4);' +
      'font-family:sans-serif !important;font-size:15px !important;color:' + link + ' !important;}' +
      'mark.yr{color:inherit !important;border-radius:3px;}' +
      'mark.yr.c0{background:rgba(255,214,0,.45) !important;}mark.yr.c1{background:rgba(90,200,120,.42) !important;}' +
      'mark.yr.c2{background:rgba(80,160,255,.42) !important;}mark.yr.c3{background:rgba(255,105,150,.42) !important;}' +
      'mark.yr.n{border-bottom:2px dotted ' + link + ';}';
  };

  /** Previous / next chapter buttons at the ends of the page. */
  YR.nav = function (prev, next) {
    var old = document.querySelectorAll('.yr-nav');
    for (var i = 0; i < old.length; i++) old[i].remove();
    if (prev) {
      var p = document.createElement('a'); p.className = 'yr-nav'; p.textContent = '‹  Previous chapter';
      p.onclick = function (e) { e.preventDefault(); Android.prev(); };
      document.body.insertBefore(p, document.body.firstChild);
    }
    if (next) {
      var n = document.createElement('a'); n.className = 'yr-nav'; n.textContent = 'Next chapter  ›';
      n.onclick = function (e) { e.preventDefault(); Android.next(); };
      document.body.appendChild(n);
    }
  };

  YR.sel = function () {
    var s = YM.selection(null);
    if (!s) return '';
    delete s.root;
    return JSON.stringify(s);
  };

  YR.marks = function (list) { for (var i = 0; i < list.length; i++) YM.mark(document.body, list[i]); };

  function maxScroll() { return Math.max(1, document.documentElement.scrollHeight - window.innerHeight); }
  YR.ratio = function () { return window.scrollY / maxScroll(); };
  YR.toRatio = function (r) { window.scrollTo(0, r * maxScroll()); };
  YR.toAnchor = function (id) {
    var e = document.getElementById(id) || document.querySelector('[name="' + id + '"]');
    if (e) e.scrollIntoView();
  };
  YR.text = function () { return document.body.innerText; };

  /** text from the top of the screen on (read aloud from here) */
  YR.textFromHere = function () {
    var t = document.body.innerText;
    return t.substring(Math.floor(t.length * YR.ratio() * 0.98));
  };

  var timer = null;
  window.addEventListener('scroll', function () {
    if (timer) return;
    timer = setTimeout(function () { timer = null; Android.progress(YR.ratio()); }, 250);
  });
})();

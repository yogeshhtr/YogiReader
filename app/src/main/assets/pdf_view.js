/* Yogi Reader PDF view: pages drawn by pdf.js when they come near the screen, with a selectable text layer. */
(function () {
  pdfjsLib.GlobalWorkerOptions.workerSrc = 'pdfjs/pdf.worker.min.js';
  var YR = window.YR = { doc: null, pages: [], marks: {}, current: 1 };
  var holder = document.getElementById('pages');
  var dpr = Math.min(window.devicePixelRatio || 1, 3);

  YR.theme = function (name) { document.body.className = name; };

  YR.open = function (url, startPage, marks) {
    YR.setMarks(marks || []);
    pdfjsLib.getDocument({ url: url, isEvalSupported: false }).promise.then(function (doc) {
      YR.doc = doc;
      document.getElementById('msg').remove();
      return doc.getPage(1).then(function (p1) {
        var base = p1.getViewport({ scale: 1 });
        var w = window.innerWidth - 12;
        for (var i = 1; i <= doc.numPages; i++) {
          var d = document.createElement('div');
          d.className = 'page';
          d.style.width = w + 'px';
          d.style.height = (w * base.height / base.width) + 'px';
          d.setAttribute('data-page', i);
          var num = document.createElement('div'); num.className = 'num'; num.textContent = i;
          d.appendChild(num);
          holder.appendChild(d);
          YR.pages.push(d);
          observer.observe(d);
        }
        Android.pdfInfo(doc.numPages);
        if (startPage > 1) YR.go(startPage);
        outline(doc);
      });
    }).catch(function (e) {
      document.getElementById('msg').textContent = (e && e.name === 'PasswordException')
        ? 'This PDF is protected with a password.' : 'Couldn\'t open this PDF.';
      Android.failed(String(e && e.message));
    });
  };

  var rendering = {};
  function render(div) {
    var n = +div.getAttribute('data-page');
    if (div.getAttribute('data-done') || rendering[n]) return;
    rendering[n] = true;
    YR.doc.getPage(n).then(function (page) {
      var w = parseFloat(div.style.width);
      var vp1 = page.getViewport({ scale: 1 });
      var scale = w / vp1.width;
      var vp = page.getViewport({ scale: scale });
      div.style.height = vp.height + 'px';
      var canvas = document.createElement('canvas');
      canvas.width = Math.floor(vp.width * dpr * 1.25);
      canvas.height = Math.floor(vp.height * dpr * 1.25);
      div.insertBefore(canvas, div.firstChild);
      var ctx = canvas.getContext('2d');
      var task = page.render({ canvasContext: ctx, viewport: page.getViewport({ scale: scale * dpr * 1.25 }) });
      return task.promise.then(function () {
        var tl = document.createElement('div');
        tl.className = 'textLayer';
        tl.style.setProperty('--scale-factor', scale);
        div.appendChild(tl);
        return page.getTextContent().then(function (tc) {
          var t = pdfjsLib.renderTextLayer({ textContentSource: tc, container: tl, viewport: vp, textDivs: [] });
          return t.promise.then(function () {
            var list = YR.marks[n] || [];
            for (var i = 0; i < list.length; i++) YM.mark(tl, list[i]);
          });
        });
      }).then(function () { div.setAttribute('data-done', '1'); rendering[n] = false; });
    }).catch(function () { rendering[n] = false; });
  }

  /** frees pages far from the screen, so long PDFs stay light */
  function release(div) {
    if (!div.getAttribute('data-done')) return;
    var c = div.querySelector('canvas'); if (c) { c.width = 0; c.height = 0; c.remove(); }
    var t = div.querySelector('.textLayer'); if (t) t.remove();
    div.removeAttribute('data-done');
  }

  var observer = new IntersectionObserver(function (es) {
    es.forEach(function (e) { if (e.isIntersecting) render(e.target); });
  }, { rootMargin: '1200px 0px' });
  var far = new IntersectionObserver(function (es) {
    es.forEach(function (e) { if (!e.isIntersecting) release(e.target); });
  }, { rootMargin: '6000px 0px' });
  setTimeout(function () { YR.pages.forEach(function (p) { far.observe(p); }); }, 1500);

  function pageAtCenter() {
    var y = window.scrollY + window.innerHeight / 2;
    for (var i = 0; i < YR.pages.length; i++) {
      var p = YR.pages[i];
      if (p.offsetTop + p.offsetHeight >= y) return i + 1;
    }
    return YR.pages.length;
  }
  var timer = null;
  window.addEventListener('scroll', function () {
    if (timer) return;
    timer = setTimeout(function () {
      timer = null;
      var p = pageAtCenter();
      if (p !== YR.current) { YR.current = p; Android.page(p); }
    }, 200);
  });

  YR.go = function (n) {
    var p = YR.pages[Math.max(1, Math.min(n, YR.pages.length)) - 1];
    if (p) { window.scrollTo(0, p.offsetTop - 60); YR.current = n; Android.page(n); }
  };

  YR.setMarks = function (list) {
    YR.marks = {};
    for (var i = 0; i < list.length; i++) (YR.marks[list[i].ch] = YR.marks[list[i].ch] || []).push(list[i]);
  };

  YR.addMark = function (h) {
    (YR.marks[h.ch] = YR.marks[h.ch] || []).push(h);
    var p = YR.pages[h.ch - 1];
    var tl = p && p.querySelector('.textLayer');
    if (tl) YM.mark(tl, h);
  };

  YR.sel = function () {
    var s = YM.selection(function (node) {
      var el = node.nodeType === 1 ? node : node.parentElement;
      return el && el.closest('.textLayer');
    });
    if (!s) return '';
    s.ch = +s.root.parentElement.getAttribute('data-page');
    delete s.root;
    return JSON.stringify(s);
  };

  YR.toMark = function (id, page) {
    YR.go(page);
    setTimeout(function () { YM.scrollToMark(id); }, 900);
  };

  /** text of a page (read aloud) */
  YR.pageText = function (n) {
    YR.doc.getPage(n).then(function (p) { return p.getTextContent(); }).then(function (tc) {
      Android.text(tc.items.map(function (i) { return i.str + (i.hasEOL ? '\n' : ''); }).join(''));
    });
  };

  /** search every page; results go back as [{page, snippet}] */
  YR.search = function (q) {
    q = q.toLowerCase();
    var out = [], doc = YR.doc, i = 1;
    function next() {
      if (i > doc.numPages || out.length >= 200) { Android.results(JSON.stringify(out)); return; }
      var n = i++;
      doc.getPage(n).then(function (p) { return p.getTextContent(); }).then(function (tc) {
        var t = tc.items.map(function (x) { return x.str; }).join(' ');
        var low = t.toLowerCase(), at = low.indexOf(q);
        while (at >= 0 && out.length < 200) {
          out.push({ page: n, snippet: t.substring(Math.max(0, at - 40), at + q.length + 40) });
          at = low.indexOf(q, at + q.length);
        }
        next();
      }).catch(next);
    }
    next();
  };

  function outline(doc) {
    doc.getOutline().then(function (items) {
      if (!items || !items.length) return;
      var flat = [], jobs = [];
      function walk(list, depth) {
        list.forEach(function (it) {
          var e = { title: it.title, depth: depth, page: 0 };
          flat.push(e);
          var d = it.dest;
          jobs.push((typeof d === 'string' ? doc.getDestination(d) : Promise.resolve(d)).then(function (dest) {
            if (dest && dest[0]) return doc.getPageIndex(dest[0]).then(function (ix) { e.page = ix + 1; });
          }).catch(function () {}));
          if (it.items && it.items.length && depth < 3) walk(it.items, depth + 1);
        });
      }
      walk(items, 0);
      Promise.all(jobs).then(function () { Android.outline(JSON.stringify(flat.filter(function (e) { return e.page > 0; }))); });
    });
  }
})();

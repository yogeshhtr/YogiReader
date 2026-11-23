/* Yogi Reader: highlights and selection, shared by the book reader and the PDF viewer.
   A highlight is stored as a character range inside a root element (the book chapter, or one PDF page's text layer). */
(function () {
  if (window.YM) return;
  var YM = window.YM = {};

  function nodes(root) {
    var out = [], w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: function (n) {
        var p = n.parentNode && n.parentNode.nodeName;
        return (p === 'SCRIPT' || p === 'STYLE') ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
      }
    });
    var n, pos = 0;
    while ((n = w.nextNode())) { out.push({ n: n, s: pos }); pos += n.data.length; }
    return out;
  }

  YM.fullText = function (root) {
    var a = nodes(root), t = '';
    for (var i = 0; i < a.length; i++) t += a[i].n.data;
    return t;
  };

  /** offset of a DOM point inside root, in characters */
  function offsetOf(root, node, off) {
    var r = document.createRange();
    r.setStart(root, 0);
    r.setEnd(node, off);
    return r.toString().length;
  }

  /** The current selection, relative to [root] (or the root found by [findRoot]). */
  YM.selection = function (findRoot) {
    var s = window.getSelection();
    if (!s || s.rangeCount === 0 || s.isCollapsed) return null;
    var r = s.getRangeAt(0);
    var root = findRoot ? findRoot(r.startContainer) : document.body;
    if (!root) return null;
    var start = offsetOf(root, r.startContainer, r.startOffset);
    var text = r.toString();
    var full = YM.fullText(root);
    return {
      text: text, start: start, len: text.length,
      pre: full.substring(Math.max(0, start - 32), start),
      suf: full.substring(start + text.length, start + text.length + 32),
      root: root
    };
  };

  /** Finds where a saved highlight is now: exact offset first, then by its words and context. */
  function locate(full, h) {
    if (full.substr(h.start, h.len) === h.text) return h.start;
    var i = full.indexOf((h.pre || '') + h.text + (h.suf || ''));
    if (i >= 0) return i + (h.pre || '').length;
    i = full.indexOf(h.text);
    return i;
  }

  YM.mark = function (root, h) {
    if (!root || document.querySelector('mark.yr[data-id="' + h.id + '"]')) return;
    var full = YM.fullText(root);
    var at = locate(full, h);
    if (at < 0) return;
    var end = at + h.len;
    var a = nodes(root);
    for (var i = 0; i < a.length; i++) {
      var n = a[i].n, s = a[i].s, e = s + n.data.length;
      if (e <= at || s >= end) continue;
      var from = Math.max(at, s) - s, to = Math.min(end, e) - s;
      if (to <= from) continue;
      var r = document.createRange();
      r.setStart(n, from);
      r.setEnd(n, to);
      var m = document.createElement('mark');
      m.className = 'yr c' + (h.color || 0) + (h.note ? ' n' : '');
      m.setAttribute('data-id', h.id);
      try { r.surroundContents(m); } catch (x) {}
    }
  };

  YM.unmark = function (id) {
    var ms = document.querySelectorAll('mark.yr[data-id="' + id + '"]');
    for (var i = 0; i < ms.length; i++) {
      var m = ms[i], p = m.parentNode;
      while (m.firstChild) p.insertBefore(m.firstChild, m);
      p.removeChild(m);
      p.normalize();
    }
  };

  YM.scrollToMark = function (id) {
    var m = document.querySelector('mark.yr[data-id="' + id + '"]');
    if (m) { m.scrollIntoView({ block: 'center' }); return true; }
    return false;
  };

  /** taps: on a highlight → show it; elsewhere → show or hide the bars */
  document.addEventListener('click', function (e) {
    var m = e.target.closest && e.target.closest('mark.yr');
    if (m) { e.preventDefault(); Android.markTap(m.getAttribute('data-id')); return; }
    if (e.target.closest && e.target.closest('a')) return;
    if (!window.getSelection().isCollapsed) return;
    Android.tap();
  }, true);
})();

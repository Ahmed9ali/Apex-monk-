package com.example.devtools

import org.json.JSONArray
import org.json.JSONObject

object DevToolsScripts {

    fun buildConsoleEvalScript(expression: String): String {
        val quotedExpr = JSONObject.quote(expression)
        return """
            (function() {
                function apexFmt(val, depth) {
                    if (val === undefined) return 'undefined';
                    if (val === null) return 'null';
                    var t = typeof val;
                    if (t === 'string') return JSON.stringify(val);
                    if (t === 'number' || t === 'boolean' || t === 'bigint') return String(val);
                    if (t === 'function') return '[Function: ' + (val.name || 'anonymous') + ']';
                    if (t === 'symbol') return val.toString();
                    if (depth > 2) return Array.isArray(val) ? '[Array(' + val.length + ')]' : '[Object]';
                    if (val instanceof Error) return val.name + ': ' + val.message;
                    if (val instanceof Element) {
                        var idPart = val.id ? ('#' + val.id) : '';
                        var clsPart = (typeof val.className === 'string' && val.className.trim())
                            ? ('.' + val.className.trim().split(/\s+/).slice(0, 2).join('.'))
                            : '';
                        return '<' + val.tagName.toLowerCase() + idPart + clsPart + '>';
                    }
                    if (val instanceof NodeList || val instanceof HTMLCollection) {
                        var arr = [];
                        for (var k = 0; k < Math.min(val.length, 12); k++) arr.push(apexFmt(val[k], depth + 1));
                        if (val.length > 12) arr.push('...(' + (val.length - 12) + ' more)');
                        return '[' + arr.join(', ') + ']';
                    }
                    if (Array.isArray(val)) {
                        var items = [];
                        for (var i = 0; i < Math.min(val.length, 20); i++) items.push(apexFmt(val[i], depth + 1));
                        if (val.length > 20) items.push('...(' + (val.length - 20) + ' more)');
                        return '[' + items.join(', ') + ']';
                    }
                    try {
                        var keys = Object.keys(val).slice(0, 15);
                        var pairs = [];
                        for (var j = 0; j < keys.length; j++) {
                            pairs.push(keys[j] + ': ' + apexFmt(val[keys[j]], depth + 1));
                        }
                        var totalKeys = Object.keys(val).length;
                        if (totalKeys > 15) pairs.push('...(' + (totalKeys - 15) + ' more keys)');
                        return '{ ' + pairs.join(', ') + ' }';
                    } catch (err) {
                        return String(val);
                    }
                }
                try {
                    var expr = $quotedExpr;
                    var result = (0, eval)(expr);
                    var isPromise = result && typeof result.then === 'function';
                    return JSON.stringify({
                        ok: true,
                        type: isPromise ? 'Promise' : typeof result,
                        formatted: isPromise ? '[Promise { <pending> }]' : apexFmt(result, 0)
                    });
                } catch (e) {
                    return JSON.stringify({
                        ok: false,
                        type: 'Error',
                        formatted: (e && e.stack) ? String(e.stack).split('\n').slice(0, 3).join(' | ') : String(e)
                    });
                }
            })();
        """.trimIndent()
    }

    fun buildDomTreeSnapshotScript(filterQuery: String = "", maxNodes: Int = 140): String {
        val qFilter = JSONObject.quote(filterQuery.trim())
        return """
            (function() {
                try {
                    function cssEsc(v) {
                        if (window.CSS && typeof window.CSS.escape === 'function') return window.CSS.escape(String(v));
                        return String(v).replace(/([ !"#$%&'()*+,.\/:;<=>?@\[\\\]^`{|}~])/g, '\\$1');
                    }
                    function buildSelector(el) {
                        if (!el || el.nodeType !== 1) return '';
                        if (el === document.documentElement) return 'html';
                        if (el === document.body) return 'body';
                        if (el.id && document.querySelectorAll('#' + cssEsc(el.id)).length === 1) {
                            return '#' + cssEsc(el.id);
                        }
                        var parts = [];
                        var cur = el;
                        while (cur && cur.nodeType === 1 && parts.length < 5) {
                            var tag = cur.tagName.toLowerCase();
                            if (cur === document.body) { parts.unshift('body'); break; }
                            if (cur === document.documentElement) { parts.unshift('html'); break; }
                            if (cur.id && document.querySelectorAll('#' + cssEsc(cur.id)).length === 1) {
                                parts.unshift('#' + cssEsc(cur.id));
                                break;
                            }
                            var seg = tag;
                            var cls = (typeof cur.className === 'string') ? cur.className.trim().split(/\s+/).filter(Boolean) : [];
                            if (cls.length > 0 && cls[0].length <= 32) {
                                seg += '.' + cssEsc(cls[0]);
                            }
                            var parent = cur.parentElement;
                            if (parent) {
                                var same = 0, idx = 1;
                                for (var i = 0; i < parent.children.length; i++) {
                                    var ch = parent.children[i];
                                    if (ch.tagName === cur.tagName) {
                                        same++;
                                        if (ch === cur) idx = same;
                                    }
                                }
                                if (same > 1) seg += ':nth-of-type(' + idx + ')';
                            }
                            parts.unshift(seg);
                            cur = parent;
                        }
                        return parts.join(' > ');
                    }
                    function serializeNode(el, depth) {
                        var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
                        var style = window.getComputedStyle ? window.getComputedStyle(el) : null;
                        var attrs = {};
                        if (el.attributes) {
                            for (var a = 0; a < Math.min(el.attributes.length, 8); a++) {
                                var at = el.attributes[a];
                                if (at && at.name) attrs[at.name] = String(at.value || '').slice(0, 120);
                            }
                        }
                        var stylesMap = {};
                        if (style) {
                            stylesMap['display'] = style.display || '';
                            stylesMap['position'] = style.position || '';
                            stylesMap['size'] = Math.round(rect ? rect.width : 0) + 'x' + Math.round(rect ? rect.height : 0);
                            stylesMap['color'] = style.color || '';
                            stylesMap['background'] = style.backgroundColor || '';
                            stylesMap['fontSize'] = style.fontSize || '';
                            stylesMap['zIndex'] = style.zIndex || '';
                        }
                        var directText = '';
                        for (var c = 0; c < el.childNodes.length; c++) {
                            if (el.childNodes[c].nodeType === 3) {
                                var t = (el.childNodes[c].nodeValue || '').replace(/\s+/g, ' ').trim();
                                if (t) { directText = t.slice(0, 80); break; }
                            }
                        }
                        if (!directText && el.children.length === 0) {
                            directText = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 80);
                        }
                        var htmlPreview = '';
                        try {
                            htmlPreview = (el.outerHTML || '').replace(/\s+/g, ' ').trim().slice(0, 320);
                        } catch (_) {}
                        var sel = buildSelector(el);
                        return {
                            id: sel + '@' + depth,
                            tagName: el.tagName.toLowerCase(),
                            idAttr: el.id || '',
                            classAttr: (typeof el.className === 'string' ? el.className.trim() : ''),
                            selector: sel,
                            depth: depth,
                            childCount: el.children ? el.children.length : 0,
                            textPreview: directText,
                            attributes: attrs,
                            styles: stylesMap,
                            bbox: rect ? (Math.round(rect.left) + ',' + Math.round(rect.top) + ' • ' + Math.round(rect.width) + '×' + Math.round(rect.height)) : '',
                            outerHtml: htmlPreview
                        };
                    }

                    var query = $qFilter;
                    var out = [];
                    if (query) {
                        var matched = [];
                        try {
                            matched = Array.from(document.querySelectorAll(query)).slice(0, $maxNodes);
                        } catch (_) {
                            matched = [];
                        }
                        if (matched.length === 0) {
                            var lowerQ = query.toLowerCase();
                            var all = document.querySelectorAll('*');
                            for (var i = 0; i < all.length && matched.length < $maxNodes; i++) {
                                var n = all[i];
                                if (n.id === '__apex_devtools_highlight__') continue;
                                var tag = (n.tagName || '').toLowerCase();
                                var idS = (n.id || '').toLowerCase();
                                var clsS = (typeof n.className === 'string' ? n.className : '').toLowerCase();
                                var txtS = (n.innerText || '').slice(0, 120).toLowerCase();
                                if (tag.indexOf(lowerQ) >= 0 || idS.indexOf(lowerQ) >= 0 || clsS.indexOf(lowerQ) >= 0 || txtS.indexOf(lowerQ) >= 0) {
                                    matched.push(n);
                                }
                            }
                        }
                        for (var m = 0; m < matched.length; m++) {
                            if (matched[m].id === '__apex_devtools_highlight__') continue;
                            out.push(serializeNode(matched[m], 0));
                        }
                    } else {
                        var queue = [];
                        if (document.documentElement) queue.push({ el: document.documentElement, d: 0 });
                        while (queue.length > 0 && out.length < $maxNodes) {
                            var item = queue.shift();
                            var curEl = item.el;
                            if (!curEl || curEl.nodeType !== 1) continue;
                            if (curEl.id === '__apex_devtools_highlight__') continue;
                            out.push(serializeNode(curEl, item.d));
                            var children = curEl.children || [];
                            for (var ch = children.length - 1; ch >= 0; ch--) {
                                queue.unshift({ el: children[ch], d: item.d + 1 });
                            }
                        }
                    }
                    return JSON.stringify({ ok: true, nodes: out });
                } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e), nodes: [] });
                }
            })();
        """.trimIndent()
    }

    fun buildHighlightAndInspectSelectorScript(selector: String): String {
        val qSelector = JSONObject.quote(selector)
        return """
            (function() {
                try {
                    var sel = $qSelector;
                    var el = document.querySelector(sel);
                    var old = document.getElementById('__apex_devtools_highlight__');
                    if (old && old.parentNode) old.parentNode.removeChild(old);
                    if (!el) return JSON.stringify({ ok: false });
                    if (el.scrollIntoView) {
                        el.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'nearest' });
                    }
                    var rect = el.getBoundingClientRect();
                    var overlay = document.createElement('div');
                    overlay.id = '__apex_devtools_highlight__';
                    overlay.style.cssText =
                        'position:fixed;pointer-events:none;z-index:2147483647;' +
                        'left:' + rect.left + 'px;top:' + rect.top + 'px;' +
                        'width:' + Math.max(rect.width, 4) + 'px;height:' + Math.max(rect.height, 4) + 'px;' +
                        'background:rgba(59,130,246,0.20);border:2px solid #2563EB;border-radius:4px;box-sizing:border-box;';
                    var badge = document.createElement('div');
                    var label = el.tagName.toLowerCase() +
                        (el.id ? '#' + el.id : '') +
                        ' • ' + Math.round(rect.width) + '×' + Math.round(rect.height);
                    badge.textContent = label;
                    badge.style.cssText =
                        'position:absolute;left:0;top:-22px;background:#1D4ED8;color:#fff;' +
                        'font:600 11px/16px monospace;padding:2px 6px;border-radius:4px;white-space:nowrap;' +
                        'box-shadow:0 2px 6px rgba(0,0,0,0.3);';
                    if (rect.top < 26) badge.style.top = (Math.max(rect.height, 4) + 4) + 'px';
                    overlay.appendChild(badge);
                    (document.body || document.documentElement).appendChild(overlay);
                    setTimeout(function() {
                        var cur = document.getElementById('__apex_devtools_highlight__');
                        if (cur && cur.parentNode) cur.parentNode.removeChild(cur);
                    }, 3500);
                    return JSON.stringify({
                        ok: true,
                        bbox: Math.round(rect.left) + ',' + Math.round(rect.top) + ' • ' + Math.round(rect.width) + '×' + Math.round(rect.height)
                    });
                } catch (e) {
                    return JSON.stringify({ ok: false });
                }
            })();
        """.trimIndent()
    }

    fun buildClearHighlightScript(): String = """
        (function() {
            var old = document.getElementById('__apex_devtools_highlight__');
            if (old && old.parentNode) old.parentNode.removeChild(old);
        })();
    """.trimIndent()

    fun buildTapToInspectModeScript(enabled: Boolean): String {
        return if (enabled) {
            """
            (function() {
                if (window.__apexDevToolsInspectHandler) {
                    document.removeEventListener('click', window.__apexDevToolsInspectHandler, true);
                }
                function cssEsc(v) {
                    if (window.CSS && typeof window.CSS.escape === 'function') return window.CSS.escape(String(v));
                    return String(v).replace(/([ !"#$%&'()*+,.\/:;<=>?@\[\\\]^`{|}~])/g, '\\$1');
                }
                function buildSelector(el) {
                    if (!el || el.nodeType !== 1) return '';
                    if (el === document.documentElement) return 'html';
                    if (el === document.body) return 'body';
                    if (el.id && document.querySelectorAll('#' + cssEsc(el.id)).length === 1) {
                        return '#' + cssEsc(el.id);
                    }
                    var parts = [];
                    var cur = el;
                    while (cur && cur.nodeType === 1 && parts.length < 5) {
                        var tag = cur.tagName.toLowerCase();
                        if (cur === document.body) { parts.unshift('body'); break; }
                        if (cur === document.documentElement) { parts.unshift('html'); break; }
                        if (cur.id && document.querySelectorAll('#' + cssEsc(cur.id)).length === 1) {
                            parts.unshift('#' + cssEsc(cur.id));
                            break;
                        }
                        var seg = tag;
                        var cls = (typeof cur.className === 'string') ? cur.className.trim().split(/\s+/).filter(Boolean) : [];
                        if (cls.length > 0 && cls[0].length <= 32) seg += '.' + cssEsc(cls[0]);
                        var parent = cur.parentElement;
                        if (parent) {
                            var same = 0, idx = 1;
                            for (var i = 0; i < parent.children.length; i++) {
                                var ch = parent.children[i];
                                if (ch.tagName === cur.tagName) {
                                    same++;
                                    if (ch === cur) idx = same;
                                }
                            }
                            if (same > 1) seg += ':nth-of-type(' + idx + ')';
                        }
                        parts.unshift(seg);
                        cur = parent;
                    }
                    return parts.join(' > ');
                }
                window.__apexDevToolsInspectHandler = function(ev) {
                    try {
                        ev.preventDefault();
                        ev.stopPropagation();
                        ev.stopImmediatePropagation();
                        var target = ev.target;
                        if (target && target.id === '__apex_devtools_highlight__') return;
                        var sel = buildSelector(target);
                        document.removeEventListener('click', window.__apexDevToolsInspectHandler, true);
                        window.__apexDevToolsInspectHandler = null;
                        if (window.ApexDevToolsBridge && sel) {
                            window.ApexDevToolsBridge.onElementPicked(sel);
                        }
                    } catch (_) {}
                };
                document.addEventListener('click', window.__apexDevToolsInspectHandler, true);
                return 'armed';
            })();
            """.trimIndent()
        } else {
            """
            (function() {
                if (window.__apexDevToolsInspectHandler) {
                    document.removeEventListener('click', window.__apexDevToolsInspectHandler, true);
                    window.__apexDevToolsInspectHandler = null;
                }
                return 'disarmed';
            })();
            """.trimIndent()
        }
    }

    fun buildToggleLayoutOutlinesScript(enabled: Boolean): String {
        return if (enabled) {
            """
            (function() {
                var styleId = '__apex_devtools_outlines__';
                if (!document.getElementById(styleId)) {
                    var st = document.createElement('style');
                    st.id = styleId;
                    st.textContent = '*:not(#__apex_devtools_highlight__) { outline: 1px solid rgba(59, 130, 246, 0.35) !important; }';
                    (document.head || document.documentElement).appendChild(st);
                }
            })();
            """.trimIndent()
        } else {
            """
            (function() {
                var st = document.getElementById('__apex_devtools_outlines__');
                if (st && st.parentNode) st.parentNode.removeChild(st);
            })();
            """.trimIndent()
        }
    }

    fun buildPerformanceResourcesScript(): String = """
        (function() {
            try {
                if (!window.performance || !window.performance.getEntriesByType) {
                    return JSON.stringify([]);
                }
                var entries = window.performance.getEntriesByType('resource') || [];
                var out = [];
                for (var i = Math.max(0, entries.length - 120); i < entries.length; i++) {
                    var e = entries[i];
                    out.push({
                        url: String(e.name || ''),
                        initiatorType: String(e.initiatorType || 'other'),
                        durationMs: Math.round(e.duration || 0),
                        transferSize: (typeof e.transferSize === 'number') ? e.transferSize : -1
                    });
                }
                return JSON.stringify(out);
            } catch (err) {
                return JSON.stringify([]);
            }
        })();
    """.trimIndent()

    fun buildStorageSnapshotScript(): String = """
        (function() {
            function readStorage(store) {
                var list = [];
                try {
                    if (!store) return list;
                    for (var i = 0; i < Math.min(store.length, 80); i++) {
                        var k = store.key(i);
                        list.push({ key: String(k), value: String(store.getItem(k) || '').slice(0, 400) });
                    }
                } catch (_) {}
                return list;
            }
            return JSON.stringify({
                title: String(document.title || ''),
                readyState: String(document.readyState || ''),
                userAgent: String(navigator.userAgent || ''),
                innerWidth: Number(window.innerWidth || 0),
                innerHeight: Number(window.innerHeight || 0),
                dpr: Number(window.devicePixelRatio || 1),
                localStorage: readStorage(window.localStorage),
                sessionStorage: readStorage(window.sessionStorage)
            });
        })();
    """.trimIndent()

    fun buildSetStorageItemScript(isSession: Boolean, key: String, value: String): String {
        val target = if (isSession) "window.sessionStorage" else "window.localStorage"
        return """
            (function() {
                try {
                    $target.setItem(${JSONObject.quote(key)}, ${JSONObject.quote(value)});
                    return JSON.stringify({ ok: true });
                } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                }
            })();
        """.trimIndent()
    }

    fun buildRemoveStorageItemScript(isSession: Boolean, key: String): String {
        val target = if (isSession) "window.sessionStorage" else "window.localStorage"
        return """
            (function() {
                try {
                    $target.removeItem(${JSONObject.quote(key)});
                    return JSON.stringify({ ok: true });
                } catch (e) {
                    return JSON.stringify({ ok: false, error: String(e) });
                }
            })();
        """.trimIndent()
    }

    fun buildClearStorageScript(scope: String): String = when (scope) {
        "local" -> "(function(){ try { window.localStorage.clear(); } catch(_){} })();"
        "session" -> "(function(){ try { window.sessionStorage.clear(); } catch(_){} })();"
        else -> "(function(){ try { window.localStorage.clear(); window.sessionStorage.clear(); } catch(_){} })();"
    }

    fun parseJsonUnquoted(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        val trimmed = raw.trim()
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return runCatching {
                org.json.JSONTokener(trimmed).nextValue()?.toString().orEmpty()
            }.getOrElse { trimmed }
        }
        return trimmed
    }

    fun parseDomNodesResponse(raw: String?): List<DevToolsDomNode> {
        val jsonStr = parseJsonUnquoted(raw)
        if (jsonStr.isBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(jsonStr)
            val arr = root.optJSONArray("nodes") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val attrsObj = obj.optJSONObject("attributes")
                    val attrs = buildMap {
                        if (attrsObj != null) {
                            val keys = attrsObj.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                put(k, attrsObj.optString(k, ""))
                            }
                        }
                    }
                    val stylesObj = obj.optJSONObject("styles")
                    val styles = buildMap {
                        if (stylesObj != null) {
                            val keys = stylesObj.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                val v = stylesObj.optString(k, "")
                                if (v.isNotBlank()) put(k, v)
                            }
                        }
                    }
                    add(
                        DevToolsDomNode(
                            id = obj.optString("id", "node_$i"),
                            tagName = obj.optString("tagName", "div"),
                            idAttr = obj.optString("idAttr", ""),
                            classAttr = obj.optString("classAttr", ""),
                            selector = obj.optString("selector", ""),
                            depth = obj.optInt("depth", 0),
                            childCount = obj.optInt("childCount", 0),
                            textPreview = obj.optString("textPreview", ""),
                            attributesSummary = attrs,
                            computedStyles = styles,
                            boundingBoxText = obj.optString("bbox", ""),
                            outerHtmlPreview = obj.optString("outerHtml", "")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun parseCookiesHeader(cookieHeader: String?): List<DevToolsKeyValueEntry> {
        if (cookieHeader.isNullOrBlank()) return emptyList()
        return cookieHeader.split(';')
            .mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val eqIdx = trimmed.indexOf('=')
                if (eqIdx <= 0) {
                    DevToolsKeyValueEntry(key = trimmed, value = "")
                } else {
                    DevToolsKeyValueEntry(
                        key = trimmed.substring(0, eqIdx).trim(),
                        value = trimmed.substring(eqIdx + 1).trim()
                    )
                }
            }
    }

    fun parsePerformanceResources(raw: String?): List<Triple<String, String, Pair<Long, Long?>>> {
        val jsonStr = parseJsonUnquoted(raw)
        if (jsonStr.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(jsonStr)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val url = obj.optString("url", "")
                    if (url.isBlank()) continue
                    val type = obj.optString("initiatorType", "other")
                    val dur = obj.optLong("durationMs", 0L)
                    val size = obj.optLong("transferSize", -1L).takeIf { it >= 0L }
                    add(Triple(url, type, dur to size))
                }
            }
        }.getOrDefault(emptyList())
    }
}

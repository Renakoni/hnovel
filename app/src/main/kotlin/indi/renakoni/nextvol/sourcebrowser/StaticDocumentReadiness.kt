package indi.renakoni.nextvol.sourcebrowser

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** Observe from document start: a script can remove its own element before onPageFinished. */
internal fun WebView.observeStaticDocument(): String? {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return null
    val key = JsonPrimitive("__sourceDocument_${UUID.randomUUID()}")
    WebViewCompat.addDocumentStartJavaScript(this, """
        (function(){
            if (window !== window.top) return;
            var dynamic = false, seen = new WeakSet();
            function inspect(node) {
                if (dynamic || node.nodeType !== 1 || seen.has(node)) return;
                seen.add(node);
                if (node.matches('script,iframe,frame,object,embed,template,meta[http-equiv],link[rel="import"]')) dynamic = true;
                for (var i = 0; !dynamic && i < node.attributes.length; i++) {
                    if (/^on/i.test(node.attributes[i].name)) dynamic = true;
                }
                for (var child = node.firstElementChild; !dynamic && child; child = child.nextElementSibling) inspect(child);
            }
            function changes(records) {
                try {
                    for (var i = 0; !dynamic && i < records.length; i++) {
                        var record = records[i];
                        if (record.type === 'attributes') {
                            if (/^on/i.test(record.attributeName)) dynamic = true;
                        } else {
                            for (var j = 0; !dynamic && j < record.addedNodes.length; j++) inspect(record.addedNodes[j]);
                        }
                    }
                } catch (e) { dynamic = true; }
                if (dynamic) observer.disconnect();
            }
            var observer = new MutationObserver(changes);
            observer.observe(document, {childList:true, subtree:true, attributes:true});
            if (document.documentElement) inspect(document.documentElement);
            Object.defineProperty(window, $key, {value:function(){
                changes(observer.takeRecords());
                var complete = document.readyState === 'complete';
                if (complete) observer.disconnect();
                return !dynamic && complete && document.contentType === 'text/html';
            }});
        })();
    """.trimIndent(), setOf("*"))
    return "(function(){try{return typeof window[$key] === 'function' && window[$key]();}catch(e){return false;}})()"
}

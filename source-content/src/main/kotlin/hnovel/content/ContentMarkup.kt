package hnovel.content

/** Trusted traversal code; parsing, node walking and source regexes stay in the isolated worker. */
internal val contentMarkupScript = """
    (function(){
        var root=java.getElements('@css:body').first(),out=[],text='';
        function flush(){text.split(/\r?\n/).forEach(function(line){line=line.trim();if(line)out.push({text:line})});text=''}
        function visit(node){
            var name=node.nodeName();
            if(name==='#text'){text+=node.getWholeText();return}
            if(name==='script'||name==='style'||name==='noscript')return;
            if(name==='img'){flush();var src=node.attr('src');if(src)out.push({image:src});return}
            if(name==='br'){text+='\n';return}
            var block=/^(body|div|p|li|section|article|h[1-6]|blockquote|tr|pre)$/.test(name);
            if(block)text+='\n';node.childNodes().forEach(visit);if(block)text+='\n';
        }
        visit(root);flush();return out;
    })()
""".trimIndent()

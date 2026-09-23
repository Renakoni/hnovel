package indi.renakoni.nextvol.sourcebrowser

/** Inspect the live document before running extraction, in either browser transport. */
internal val websiteChallengeScript = """
    (function(){
        if (Array.prototype.some.call(document.querySelectorAll('script[src]'), function(script) {
            try {
                var url = new URL(script.src, location.href);
                return url.origin === location.origin && url.pathname === '/@wafjs';
            } catch(e) { return false; }
        })) return 'SiteVerification';
        if (Array.prototype.some.call(document.querySelectorAll('form[action]'), function(form) {
            try {
                var url = new URL(form.action, location.href);
                return url.origin === location.origin && url.searchParams.has('_waform') &&
                    form.querySelector('input[name=__input]');
            } catch(e) { return false; }
        })) return 'SiteVerification';
        return window._cf_chl_opt || /^\s*Just a moment/i.test(document.title) &&
            document.querySelector('script[src*="/cdn-cgi/challenge-platform/"]') ? 'Cloudflare' :
            /^\/WAF\/VERIFY\/CAPTCHA/i.test(location.pathname) &&
            (/^\s*Verify Yourself\s*${'$'}/i.test(document.title) || document.querySelector('form#ui-form')) ? 'SiteVerification' :
            /^\/antibot(\/|${'$'})/.test(location.pathname) || /^\s*人机校验/.test(document.title) ||
            document.querySelector('form#J_ManMachineVerify') ? 'SiteVerification' :
            /^\/login(\/|${'$'})/.test(location.pathname) && document.querySelector('input[type=password]') ? 'Login' : null;
    })()
""".trimIndent()

@js:
(function () {
  let voiceName = 'zh-CN-XiaochenNeural';
  try { let h = JSON.parse(source.getHeader() || '{}'); if (h.voice) voiceName = String(h.voice); } catch (e) {}
  let lang = voiceName.substring(0, 5);
  let rate = Math.max(-50, Math.min(50, Math.round((speakSpeed - 50) * 2)));
  let tok = null;
  try { tok = JSON.parse(cache.get('msDragonTtsToken') || 'null'); } catch (e) {}
  if (!tok || !tok.t || (Date.now() - (tok.ts || 0)) > 480000) {
    let g = String(Packages.java.net.URLEncoder.encode('dev.microsofttranslator.com/apps/endpoint?api-version=1.0', 'UTF-8'));
    let days = ['sun','mon','tue','wed','thu','fri','sat'], mon = ['jan','feb','mar','apr','may','jun','jul','aug','sep','oct','nov','dec'];
    let D = new Date(), p = n => ('0' + n).slice(-2);
    let date = (days[D.getUTCDay()] + ', ' + p(D.getUTCDate()) + ' ' + mon[D.getUTCMonth()] + ' ' + D.getUTCFullYear() + ' ' + p(D.getUTCHours()) + ':' + p(D.getUTCMinutes()) + ':' + p(D.getUTCSeconds())).toLowerCase() + 'GMT';
    let uid = ''; for (let i = 0; i < 32; i++) uid += '0123456789abcdef'[Math.floor(Math.random() * 16)];
    let mac = new Packages.cn.hutool.crypto.digest.HMac(Packages.cn.hutool.crypto.digest.HmacAlgorithm.HmacSHA256, java.base64DecodeToByteArray('dGVzdC1zaWduaW5nLWtleQ=='));
    let sig = String(Packages.cn.hutool.core.codec.Base64.encode(mac.digest(('MSTranslatorAndroidApp' + g + date + uid).toLowerCase())));
    let res = String(java.ajax('https://dev.microsofttranslator.com/apps/endpoint?api-version=1.0,' + JSON.stringify({'method':'post','headers':{'Accept-Language':'zh-Hans','X-ClientVersion':'4.0.530a 5fe1dc6c','X-UserId':'test-id','X-HomeGeographicRegion':'zh-Hans-CN','X-ClientTraceId':'test-id','X-MT-Signature':sig,'User-Agent':'okhttp/4.5.0'}})));
    tok = JSON.parse(res);
    tok.ts = Date.now();
    cache.put('msDragonTtsToken', JSON.stringify(tok), 600);
  }
  let esc = String(speakText).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  let inner = rate ? '<prosody rate="' + rate + '%">' + esc + '</prosody>' : esc;
  let ssml = '<speak xmlns="http://www.w3.org/2001/10/synthesis" version="1.0" xml:lang="' + lang + '"><voice name="' + voiceName + '">' + inner + '</voice></speak>';
  let audio = java.ajaxBytes('https://' + tok.r + '.tts.speech.microsoft.com/cognitiveservices/v1,' + JSON.stringify({'method':'post','headers':{'Authorization':tok.t,'Content-Type':'application/ssml+xml','X-Microsoft-OutputFormat':'audio-24khz-160kbitrate-mono-mp3'},'body':ssml}));
  'data:audio/mpeg;base64,' + Packages.cn.hutool.core.codec.Base64.encode(audio)
})()
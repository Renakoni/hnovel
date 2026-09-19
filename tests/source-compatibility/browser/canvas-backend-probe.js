// Controlled drawing variations on an owned fixture. No browser API overrides.
function collectCanvasBackend() {
  const hash = bytes => { let h = 2166136261; for (const b of bytes) h = Math.imul(h ^ b, 16777619); return (h >>> 0).toString(16); };
  const output = {};
  for (const kind of typeof document === 'undefined' ? ['offscreen'] : ['html', 'offscreen']) {
    for (const [name, options] of Object.entries({default: {}, readFrequently: {willReadFrequently: true},
      opaque: {alpha: false}, opaqueReadFrequently: {alpha: false, willReadFrequently: true}})) {
      try {
        const canvas = kind === 'html' ? document.createElement('canvas') : new OffscreenCanvas(64, 32);
        canvas.width = 64; canvas.height = 32;
        const ctx = canvas.getContext('2d', options);
        const reads = [];
        for (let index = 0; index < 3; index++) {
          ctx.fillStyle = '#2468ac'; ctx.fillRect(0, 0, 64, 32);
          ctx.fillStyle = 'rgba(240,120,30,.5)'; ctx.fillRect(5, 5, 20, 10);
          const bytes = ctx.getImageData(0, 0, 64, 32).data;
          reads.push({hash: hash(bytes), blend: Array.from(bytes.slice((6 * 64 + 6) * 4, (6 * 64 + 6) * 4 + 4))});
        }
        output[kind + ':' + name] = {attributes: ctx.getContextAttributes?.(), reads};
      } catch (error) { output[kind + ':' + name] = {error: error.name}; }
    }
  }
  return output;
}

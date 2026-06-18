// Shared swipe-row gesture for the mockups.
// Markup: <div class="swipeable" data-right="＋ Queued" data-left="Removed" data-left-action="remove">
//           <div class="sw-bg sw-bg-l">label</div><div class="sw-bg sw-bg-r">label</div>
//           <div class="sw-inner"> ...row content... </div>
//         </div>
// Omit data-left/data-right to disable that direction. action="remove" animates the row away.
(function () {
  const css = document.createElement('style');
  css.textContent = `
  .swipeable { position:relative; overflow:hidden; touch-action:pan-y; user-select:none;
    -webkit-user-select:none; max-height:220px; transition:max-height .25s ease, opacity .25s ease; }
  .sw-inner { position:relative; z-index:2; }
  .sw-bg { position:absolute; inset:0; display:flex; align-items:center; font-size:13px;
    font-weight:800; color:#fff; padding:0 18px; z-index:1; opacity:0;
    font-family:system-ui,sans-serif; }
  .sw-bg-l { justify-content:flex-end; }
  .sw-bg-r { justify-content:flex-start; }
  .swipeable.show-left .sw-bg-l { opacity:1; }
  .swipeable.show-right .sw-bg-r { opacity:1; }
  .sw-toast { position:absolute; bottom:104px; left:50%; transform:translateX(-50%) translateY(8px);
    background:rgba(18,18,24,.93); color:#fff; font-size:12.5px; font-weight:700; padding:9px 16px;
    border-radius:999px; opacity:0; transition:opacity .2s, transform .2s; z-index:99;
    pointer-events:none; white-space:nowrap; font-family:system-ui,sans-serif; }
  .sw-toast.on { opacity:1; transform:translateX(-50%) translateY(0); }`;
  document.head.appendChild(css);

  let toastT;
  window.swToast = function (msg, host) {
    host = host || document.querySelector('.phone') || document.body;
    let el = host.querySelector('.sw-toast');
    if (!el) { el = document.createElement('div'); el.className = 'sw-toast'; host.appendChild(el); }
    el.textContent = msg; el.classList.add('on');
    clearTimeout(toastT); toastT = setTimeout(() => el.classList.remove('on'), 1500);
  };

  window.initSwipes = function () {
    document.querySelectorAll('.swipeable:not([data-sw])').forEach(row => {
      row.dataset.sw = '1';
      const inner = row.querySelector('.sw-inner');
      let sx = null, dx = 0;
      row.addEventListener('pointerdown', e => {
        sx = e.clientX; dx = 0; inner.style.transition = 'none';
        try { row.setPointerCapture(e.pointerId); } catch (_) {}
      });
      row.addEventListener('pointermove', e => {
        if (sx === null) return;
        dx = e.clientX - sx;
        if (dx > 0 && !row.dataset.right) dx = 0;
        if (dx < 0 && !row.dataset.left) dx = 0;
        inner.style.transform = 'translateX(' + dx + 'px)';
        row.classList.toggle('show-right', dx > 20);
        row.classList.toggle('show-left', dx < -20);
      });
      const end = () => {
        if (sx === null) return;
        const fired = Math.abs(dx) > 90;
        const dir = dx > 0 ? 'right' : 'left';
        inner.style.transition = 'transform .22s';
        if (fired) {
          const label = dir === 'right' ? row.dataset.right : row.dataset.left;
          const act = dir === 'right' ? row.dataset.rightAction : row.dataset.leftAction;
          if (act === 'remove') {
            inner.style.transform = 'translateX(' + (dx > 0 ? 440 : -440) + 'px)';
            setTimeout(() => {
              row.style.maxHeight = '0'; row.style.opacity = '0';
              setTimeout(() => row.remove(), 260);
            }, 160);
          } else {
            inner.style.transform = 'translateX(0)';
          }
          swToast(label, row.closest('.phone'));
        } else {
          inner.style.transform = 'translateX(0)';
        }
        if (Math.abs(dx) > 6) { row.dataset.sup = '1'; setTimeout(() => delete row.dataset.sup, 120); }
        sx = null; row.classList.remove('show-right', 'show-left');
      };
      row.addEventListener('pointerup', end);
      row.addEventListener('pointercancel', end);
      row.addEventListener('click', e => {
        if (row.dataset.sup) { e.stopPropagation(); e.preventDefault(); }
      }, true);
    });
  };
  document.addEventListener('DOMContentLoaded', initSwipes);
})();

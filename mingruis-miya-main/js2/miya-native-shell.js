/* Miya 小手机 · Android 壳适配层
 * 仅在 APK（window.MiyaNative 存在）内生效，浏览器打开时自动跳过。
 * 负责：通知桥接、导出落盘、返回手势分层关闭、通知点击深链。
 */
(function (global) {
  'use strict';

  var native = global.MiyaNative || null;
  if (!native) return;

  var doc = global.document;
  doc.documentElement.classList.add('miya-native-shell');

  function safe(fn, fallback) {
    try { return fn(); } catch (e) { return fallback; }
  }

  /* ============================ 视觉补丁 ============================ */

  /* 网页打开 App 时会给桌面 .phone 加 `transform: scale(0.96)` + `filter: blur(10px)`
   * （miya-apps.css 的 body.miya-app-open 规则），App 层关闭时又有 0.38s 淡出，
   * 于是返回的瞬间会露出一大片"磨砂玻璃"。APK 里 App 层本来就是全屏不透明的，
   * 这层虚化完全看不到，所以直接在原生壳里关掉：返回时看到的是干净的桌面。
   */
  safe(function () {
    var style = doc.createElement('style');
    style.id = 'miya-native-shell-style';
    style.appendChild(doc.createTextNode(
      'html.miya-native-shell body.miya-app-open .phone{' +
      'transform:none !important;filter:none !important;transition:none !important;' +
      '}'
    ));
    (doc.head || doc.documentElement).appendChild(style);
  });

  /* ============================ 通知 ============================ */

  function extractChatId(opts) {
    return safe(function () {
      if (opts && opts.chatId) return String(opts.chatId);
      var data = opts && opts.data;
      if (data) {
        if (data.chatId) return String(data.chatId);
        var m = String(data.url || '').match(/miya-open-chat=([^&]+)/);
        if (m) return decodeURIComponent(m[1] || '');
      }
    }, '') || '';
  }

  function nativeNotify(title, opts) {
    safe(function () {
      native.notify(JSON.stringify({
        title: String(title == null ? '' : title),
        body: String(opts && opts.body ? opts.body : ''),
        tag: String(opts && opts.tag ? opts.tag : ''),
        chatId: extractChatId(opts)
      }));
    });
  }

  var NativeNotification = function (title, opts) {
    nativeNotify(title, opts);
  };

  safe(function () {
    Object.defineProperty(NativeNotification, 'permission', {
      configurable: true,
      get: function () {
        return safe(function () {
          return native.notificationPermission() === 'granted' ? 'granted' : 'default';
        }, 'default');
      }
    });
  });

  NativeNotification.requestPermission = function () {
    return new Promise(function (resolve) {
      var settled = false;
      function finish() {
        if (settled) return;
        settled = true;
        resolve(NativeNotification.permission);
      }
      if (NativeNotification.permission === 'granted') {
        safe(function () { native.requestNotifications(); });
        finish();
        return;
      }
      // 等系统授权弹窗的结果（原生侧会派发事件），最多等 8 秒
      global.addEventListener('miya-notification-permission', finish, { once: true });
      safe(function () { native.requestNotifications(); });
      global.setTimeout(finish, 8000);
    });
  };

  try {
    global.Notification = NativeNotification;
  } catch (e) {
    /* 某些 WebView 不允许覆盖时忽略 */
  }

  try {
    if (global.ServiceWorkerRegistration && ServiceWorkerRegistration.prototype.showNotification) {
      ServiceWorkerRegistration.prototype.showNotification = function (title, opts) {
        nativeNotify(title, opts);
        return Promise.resolve();
      };
    }
  } catch (e) {
    /* noop */
  }

  /* 系统授权结果回调：通知页面刷新开关状态 */
  global.addEventListener('miya-notification-permission', function (ev) {
    safe(function () {
      doc.dispatchEvent(new CustomEvent('miya-notification-permission', {
        detail: ev && ev.detail ? ev.detail : {}
      }));
    });
  });

  /* ============================ 导出落盘 ============================ */

  var CHUNK_SIZE = 256 * 1024;

  function bufferToBase64(buffer) {
    var bytes = new Uint8Array(buffer);
    var binary = '';
    var step = 0x8000;
    for (var i = 0; i < bytes.length; i += step) {
      binary += String.fromCharCode.apply(null, bytes.subarray(i, i + step));
    }
    return global.btoa(binary);
  }

  function saveBlobToNative(blob, filename) {
    return new Promise(function (resolve) {
      var name = String(filename || 'miya-export.bin');
      var mime = (blob && blob.type) || 'application/octet-stream';
      var started = safe(function () { return native.downloadBegin(name, mime); }, false);
      if (!started) { resolve(false); return; }

      var offset = 0;
      function finish(success) {
        var res = '';
        if (success) {
          res = safe(function () { return String(native.downloadFinish() || ''); }, '');
        } else {
          safe(function () { native.downloadAbort(); });
        }
        if (success && res.indexOf('ok:') === 0) {
          var where = res.slice(3);
          if (typeof global.miyaToast === 'function') {
            global.miyaToast('已保存到 ' + where);
          } else {
            safe(function () { native.toast('已保存到 ' + where); });
          }
          resolve(true);
        } else {
          resolve(false);
        }
      }

      function next() {
        if (!blob || offset >= blob.size) { finish(true); return; }
        var end = Math.min(offset + CHUNK_SIZE, blob.size);
        var slice = blob.slice(offset, end);
        offset = end;
        var reader = new FileReader();
        reader.onload = function () {
          var ok = safe(function () {
            return native.downloadChunk(bufferToBase64(reader.result));
          }, false);
          if (!ok) { finish(false); return; }
          setTimeout(next, 0);
        };
        reader.onerror = function () { finish(false); };
        reader.readAsArrayBuffer(slice);
      }

      next();
    });
  }

  function installDownloadBridge() {
    var origAsync = global.miyaDownloadBlobAsync;
    if (typeof origAsync !== 'function' || origAsync.__miyaNativeWrapped) return false;
    var wrapped = function (blob, filename) {
      if (!blob) return Promise.resolve(false);
      return saveBlobToNative(blob, filename).then(function (ok) {
        if (ok) return true;
        return origAsync.call(global, blob, filename);
      }).catch(function () {
        return origAsync.call(global, blob, filename);
      });
    };
    wrapped.__miyaNativeWrapped = true;
    global.miyaDownloadBlobAsync = wrapped;
    return true;
  }

  if (!installDownloadBridge()) {
    // storage 模块可能尚未执行或版本更新后重定义函数，多补几次兜底
    doc.addEventListener('DOMContentLoaded', installDownloadBridge);
    global.setTimeout(installDownloadBridge, 1500);
    global.setTimeout(installDownloadBridge, 4000);
  }

  /* ============================ 通知桥接 ============================ */

  function installNotifyBridge() {
    var orig = global.miyaShowSystemNotification;
    if (typeof orig === 'function' && orig.__miyaNativeWrapped) return true;
    // 原生壳内直接走 Android 通知：WebView 不支持网页通知，
    // 且 serviceWorker.ready 在离线安装失败时会一直挂起。
    var wrapped = function (title, opts) {
      nativeNotify(title, opts);
      return Promise.resolve({ close: function () {} });
    };
    wrapped.__miyaNativeWrapped = true;
    global.miyaShowSystemNotification = wrapped;
    return true;
  }

  if (doc.readyState === 'loading') {
    doc.addEventListener('DOMContentLoaded', installNotifyBridge);
  }
  global.setTimeout(installNotifyBridge, 800);
  global.setTimeout(installNotifyBridge, 3000);

  /* ============================ 返回手势 ============================ */

  function isVisible(el) {
    if (!el || !el.isConnected || el.hidden) return false;
    var s = global.getComputedStyle(el);
    if (!s) return false;
    if (s.display === 'none' || s.visibility === 'hidden') return false;
    if (s.pointerEvents === 'none') return false;
    if (parseFloat(s.opacity || '1') === 0) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 24 || r.height < 24) return false;
    if (r.bottom <= 0 || r.top >= global.innerHeight) return false;
    if (r.right <= 0 || r.left >= global.innerWidth) return false;
    return true;
  }

  function closeDialog() {
    var rootEl = doc.querySelector('.miya-dialog-root');
    if (!rootEl || rootEl.hidden) return false;
    var closer = rootEl.querySelector('[data-miya-dialog-close]');
    if (!closer) return false;
    closer.click();
    return true;
  }

  function closeSlidePanel() {
    var panel = global.miyaSlidePanel;
    if (!panel || typeof panel.close !== 'function') return false;
    var nodes = doc.querySelectorAll('.is-open[aria-hidden="false"]');
    for (var i = nodes.length - 1; i >= 0; i--) {
      var el = nodes[i];
      if (!isVisible(el)) continue;
      var r = el.getBoundingClientRect();
      if (r.height < global.innerHeight * 0.3) continue;
      var pos = global.getComputedStyle(el).position;
      if (pos !== 'fixed' && pos !== 'absolute') continue;
      try { panel.close(el); return true; } catch (e) { /* continue */ }
    }
    return false;
  }

  function closeChatRoom() {
    var room = doc.getElementById('qq-room');
    if (!room || !isVisible(room)) return false;
    var api = global.miyaChatRoom;
    if (api && typeof api.close === 'function') {
      try { api.close(); return true; } catch (e) { /* fallthrough */ }
    }
    return false;
  }

  function findCloseApi(el) {
    var id = el.id;
    if (!id) return null;
    var camel = id.replace(/-([a-z0-9])/g, function (m, c) { return c.toUpperCase(); });
    var names = /App$/.test(camel) ? [camel] : [camel, camel + 'App'];
    for (var i = 0; i < names.length; i++) {
      var api = global[names[i]];
      if (api && typeof api.close === 'function') return api;
    }
    return null;
  }

  function clickBackButton(el) {
    var btns = el.querySelectorAll('[id*="-back"], [data-miya-back]');
    for (var i = 0; i < btns.length; i++) {
      var b = btns[i];
      if (!isVisible(b)) continue;
      try { b.click(); return true; } catch (e) { /* continue */ }
    }
    return false;
  }

  function closeTopLayer() {
    var list = doc.querySelectorAll('[id^="miya-"]');
    for (var i = list.length - 1; i >= 0; i--) {
      var el = list[i];
      if (!isVisible(el)) continue;
      var r = el.getBoundingClientRect();
      if (r.height < global.innerHeight * 0.45) continue;
      var api = findCloseApi(el);
      if (api) {
        try { api.close(); return true; } catch (e) { /* fallthrough */ }
      }
      if (clickBackButton(el)) return true;
    }
    return false;
  }

  global.__miyaHandleBack = function () {
    if (closeDialog()) return true;
    if (closeSlidePanel()) return true;
    if (closeChatRoom()) return true;
    return closeTopLayer();
  };

  /* ============================ 通知深链 ============================ */

  global.__miyaDeepLink = function (link) {
    var m = String(link || '').match(/miya-open-chat=([^&]+)/);
    if (!m) return;
    var chatId = safe(function () { return decodeURIComponent(m[1] || ''); }, '') || '';
    if (!chatId) return;
    var tries = 0;
    (function tryOpen() {
      tries += 1;
      var notify = global.MiyaChatNotify;
      if (notify && typeof notify.openChat === 'function') {
        var ok = safe(function () { notify.openChat(chatId); return true; }, false);
        if (ok) return;
      }
      if (tries < 40) global.setTimeout(tryOpen, 500);
    })();
  };

  /* ============================ 键盘 ============================ */

  global.addEventListener('miya-keyboard', function (ev) {
    var px = ev && ev.detail && typeof ev.detail.height === 'number' ? ev.detail.height : 0;
    var dpr = global.devicePixelRatio || 1;
    doc.documentElement.style.setProperty('--miya-keyboard-height', (px / dpr) + 'px');
    doc.documentElement.classList.toggle('miya-keyboard-open', px > 0);
  });

})(window);


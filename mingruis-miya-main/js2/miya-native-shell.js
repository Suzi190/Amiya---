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

  function styleOf(el) {
    return safe(function () { return global.getComputedStyle(el); }, null);
  }

  function rectOf(el) {
    return safe(function () { return el.getBoundingClientRect(); }, null);
  }

  /* 判断"真的可见"。两个坑：
   * 1. 非活跃玩法层（.miya-mode-layer:not(.is-active)）被 content-visibility:hidden 跳过绘制，
   *    但 getBoundingClientRect 依旧是全屏尺寸 —— 只看尺寸会把它当成打开着的 App，
   *    于是返回时去点它内部的按钮，真正的 App 层反而关不掉。
   * 2. 抽屉 / 面板常驻 DOM，靠 opacity:0 隐藏，也必须排除。
   */
  function isVisible(el) {
    if (!el || !el.isConnected || el.hidden) return false;
    if (el.getAttribute && el.getAttribute('aria-hidden') === 'true' &&
        !el.classList.contains('is-active')) return false;
    if (el.closest && el.closest('.miya-mode-layer:not(.is-active):not(.is-leaving)')) return false;
    var s = styleOf(el);
    if (!s) return false;
    if (s.display === 'none' || s.visibility === 'hidden' || s.visibility === 'collapse') return false;
    if (s.contentVisibility === 'hidden') return false;
    if (parseFloat(s.opacity || '1') < 0.05) return false;
    var r = rectOf(el);
    if (!r) return false;
    if (r.width < 24 || r.height < 24) return false;
    if (r.bottom <= 0 || r.top >= global.innerHeight) return false;
    if (r.right <= 0 || r.left >= global.innerWidth) return false;
    return true;
  }

  function isClickable(el) {
    if (!isVisible(el)) return false;
    var s = styleOf(el);
    if (!s || s.pointerEvents === 'none') return false;
    return true;
  }

  function coversScreen(el) {
    var r = rectOf(el);
    if (!r) return false;
    return r.width >= global.innerWidth * 0.6 && r.height >= global.innerHeight * 0.6;
  }

  function closeDialog() {
    var rootEl = doc.querySelector('.miya-dialog-root');
    if (!rootEl || rootEl.hidden || !isVisible(rootEl)) return false;
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
      /* App 容器绝不能交给 miyaSlidePanel.close：它会把元素 hidden=true，
       * 而这些 App 的 open() 只加类、不会取消 hidden，之后就再也打不开了。 */
      if (/(^|-)app$/.test(String(el.id || '')) || el.hasAttribute('data-miya-app')) continue;
      if (el.closest && el.closest('[id$="-app"]')) continue;
      // 桌面/玩法层也绝不能交给它，否则整个小手机层会被 hidden 掉
      if (el.classList.contains('miya-mode-layer')) continue;
      if (el.classList.contains('phone')) continue;
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

  /* id → 全局 API 名。个别 App 的导出名和 id 推导不一致，显式列出。 */
  var APP_API_ALIAS = {
    'miya-offline-app': 'miyaOfflineApp',
    'miya-fun-sayguess-app': 'miyaFunSayGuessApp'
  };

  /* 依次尝试的关闭方法。back 只回上一级（如设置里的二级页面），排在最前；
   * backToHub / closeToHome 属于玩法层内部跳转，不算关闭 App，故不列入。 */
  var CLOSE_FNS = ['back', 'close', 'closeApp', 'xBack', 'hide', 'exit'];

  function apiNamesFor(el) {
    var id = String((el && el.id) || '');
    if (!id) return [];
    var camel = id.replace(/-([a-z0-9])/g, function (m, c) { return c.toUpperCase(); });
    var cap = camel.charAt(0).toUpperCase() + camel.slice(1);
    var names = [];
    function push(n) {
      if (n && names.indexOf(n) < 0) names.push(n);
    }
    push(APP_API_ALIAS[id]);
    push(camel);
    push(camel + 'App');
    push(cap);
    push(cap + 'App');
    return names;
  }

  function callCloseApi(el) {
    var names = apiNamesFor(el);
    for (var i = 0; i < names.length; i++) {
      var api = global[names[i]];
      if (!api) continue;
      for (var j = 0; j < CLOSE_FNS.length; j++) {
        var fn = CLOSE_FNS[j];
        if (typeof api[fn] !== 'function') continue;
        // 返回 false 表示"没有可退的上一级"（如设置已在列表页），继续试下一个方法
        var ok = safe(function () { return api[fn]() !== false; }, false);
        if (ok) return true;
      }
    }
    return false;
  }

  function looksInteractive(el) {
    var tag = String(el.tagName || '').toLowerCase();
    if (tag === 'button' || tag === 'a' || tag === 'input') return true;
    if (el.getAttribute('role') === 'button') return true;
    return /back|close/i.test(String(el.className || ''));
  }

  function clickBackButton(root) {
    if (!root || !root.querySelectorAll) return false;
    // 精确到"以 -back / -close 结尾"或"-back-" 段：避免命中 miya-st-backup-* 这类同前缀元素
    var btns = root.querySelectorAll(
      '[data-miya-back], [data-miya-close], [id$="-back"], [id*="-back-"], [id$="-close"], ' +
      '[aria-label="返回"], [aria-label="关闭"]'
    );
    for (var i = 0; i < btns.length; i++) {
      var b = btns[i];
      if (!looksInteractive(b) || !isClickable(b)) continue;
      var ok = safe(function () { b.click(); return true; }, false);
      if (ok) return true;
    }
    return false;
  }

  /* 只扫真正的 App 容器。旧写法 `[id^="miya-"]` 会把设置里成百上千个 miya-st-*
   * 子元素也拿去量尺寸，每次返回都触发上百次强制重排 —— 低端机上就是"滑了没反应"。
   */
  var APP_LAYER_SELECTOR = '[id$="-app"], [id$="-overlay"], [data-miya-app]';

  function markedOpen(el) {
    if (el.hidden) return false;
    if (el.classList.contains('is-open') || el.classList.contains('is-active')) return true;
    return el.getAttribute('aria-hidden') === 'false';
  }

  /* 当前真正打开着的 App 层（玩法模式层不算，它们由 closeModeLayer 处理）。 */
  function appLayersOpen() {
    var out = [];
    var nodes = doc.querySelectorAll(APP_LAYER_SELECTOR);
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (el.classList.contains('miya-mode-layer')) continue;
      // 玩法层交给 closeModeLayer 走模式切换，别当成普通 App 关掉
      if (el.id === 'miya-simulator-app' || el.id === 'miya-world-app') continue;
      if (!markedOpen(el)) continue; // 便宜判断在前，避免无谓的布局测量
      if (!coversScreen(el) || !isVisible(el)) continue;
      out.push(el);
    }
    return out;
  }

  /* 兜底关闭：只摘类和 aria-hidden，绝不设 hidden。
   * miyaSlidePanel.close() 会把元素 hidden=true，而这些 App 的 open() 只加类、
   * 不会去取消 hidden —— 一旦被它关过，设置 App 就再也打不开了。
   */
  function hardCloseLayer(el) {
    el.classList.remove('is-open', 'is-active');
    el.setAttribute('aria-hidden', 'true');
    return true;
  }

  function closeTopLayer() {
    var layers = appLayersOpen();
    for (var i = layers.length - 1; i >= 0; i--) {
      var el = layers[i];
      // back()/close() 最可靠；退不到就点 App 自己的返回按钮；再不行硬收层
      if (callCloseApi(el)) return true;
      if (clickBackButton(el)) return true;
      hardCloseLayer(el);
      if (!appLayersOpen().length) doc.body.classList.remove('miya-app-open');
      return true;
    }
    return false;
  }

  /* 玩法层（模拟器 / 世界）不是普通 App，走模式切换退出：
   * 播放页 → 先回 hub；hub → 切回小手机层。这样滑动返回不会把整个 APK 退到后台。
   */
  function closeModeLayer() {
    var bodyCls = doc.body.classList;
    var ms = global.miyaModeSwitch;
    var simMode = bodyCls.contains('miya-mode-sim');
    var worldMode = bodyCls.contains('miya-mode-world');
    if (!simMode && !worldMode) return false;

    if (simMode) {
      var sim = doc.getElementById('miya-simulator-app');
      if (sim) {
        var inner = sim.querySelectorAll('.is-open');
        for (var i = inner.length - 1; i >= 0; i--) {
          var el = inner[i];
          if (!isVisible(el)) continue;
          if (clickBackButton(el)) return true;
        }
        var api = global.miyaSimulatorApp;
        if (sim.classList.contains('is-in-play') && api && typeof api.backToHub === 'function') {
          safe(function () { api.backToHub(); });
          return true;
        }
      }
    }

    /* setMode 走 Promise 过渡，body 上的模式类要到微任务里才改，
     * 所以这里不能同步校验结果 —— 调用即视为已处理，避免落到"退后台"。 */
    if (ms && typeof ms.setMode === 'function') {
      var called = safe(function () { ms.setMode('phone'); return true; }, false);
      if (called) return true;
    }

    /* 连模式切换 API 都没有时，直接把玩法层切回小手机层 */
    if (simMode) {
      bodyCls.remove('miya-mode-sim');
      var sim2 = doc.getElementById('miya-simulator-app');
      if (sim2) {
        sim2.classList.remove('is-active');
        sim2.classList.add('is-leaving');
      }
    } else {
      bodyCls.remove('miya-mode-world');
      var world = doc.getElementById('miya-world-app');
      if (world) {
        world.classList.remove('is-active');
        world.classList.add('is-leaving');
      }
    }
    bodyCls.add('miya-mode-phone');
    var phone = doc.getElementById('miya-phone-layer') || doc.querySelector('.phone');
    if (phone) {
      phone.classList.remove('is-leaving');
      phone.classList.add('is-active');
    }
    return true;
  }

  /* 兜底：真正在 App 里、但所有关闭手段都没生效时，把打开着的层全部收回桌面。
   * 不能再依赖 body.miya-app-open（某些 App 关闭时会误删这个类，设置还开着就没了），
   * 否则返回会一路落到 moveTaskToBack —— 在手机上看起来就是"App 被关掉了"。
   */
  function forceBackToDesk() {
    var layers = appLayersOpen();
    if (!layers.length) return false;
    for (var i = 0; i < layers.length; i++) hardCloseLayer(layers[i]);
    doc.body.classList.remove('miya-app-open');
    return true;
  }

  function backDone(step) {
    // 留一行调试输出：真机连 chrome://inspect 就能看到是哪一个分支收的尾
    safe(function () {
      if (global.console && global.console.debug) global.console.debug('[miya-back] ' + step);
    });
    return true;
  }

  global.__miyaHandleBack = function () {
    if (closeDialog()) return backDone('dialog');
    if (closeChatRoom()) return backDone('chat-room');
    if (closeTopLayer()) return backDone('app-layer');
    if (closeSlidePanel()) return backDone('slide-panel');
    if (closeModeLayer()) return backDone('mode-layer');
    if (forceBackToDesk()) return backDone('force-desk');
    /* 状态不一致（body 上还挂着 miya-app-open，但没有可识别的大层）：
     * 清掉标记，宁可"这次不 Back 到桌面"，也不要一滑就退到后台。 */
    if (doc.body.classList.contains('miya-app-open')) {
      doc.body.classList.remove('miya-app-open');
      return backDone('clear-app-open');
    }
    return false;
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


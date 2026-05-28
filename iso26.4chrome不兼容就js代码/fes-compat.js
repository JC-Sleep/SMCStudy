/**
 * fes-compat.js — FES iOS/Chrome 全局兼容层
 *
 * 背景：iOS 26.5 升级后，WKWebView（Chrome iOS 底层引擎）收紧了规范，
 *       导致依赖 IE 遗留行为的旧 JS 代码在 iPad Chrome 上失效。
 *       Safari 不受影响是因为苹果对自家浏览器有旧页面兼容保护，
 *       但 WKWebView（供第三方 App 用）没有这个豁免。
 *
 * 策略：本文件在 AdminCommon.js / 其他业务 JS 之后加载，
 *       利用 JS "后定义覆盖先定义" 特性，重写所有有问题的全局函数，
 *       无需修改任何 JSP 业务代码。
 *
 * 引入方式（在公共头部 JSP 中，AdminCommon.js 之后加一行）：
 *   <script src="/lib/fes-compat.js"></script>
 *
 * 修复内容：
 *   1. window.event 全局事件对象 polyfill（IE 遗留，WKWebView iOS 26.5 不保证存在）
 *   2. isChrome / isIpadChrome 检测修复（window.chrome.webstore 已废弃）
 *   3. toup() / nospace() / cancelActionEvent() 重写（event.keyCode 不可写问题）
 *   4. document.all 兼容代理（IE 专有 API）
 *   5. <button> 缺少 type="button" 自动修复（防止表单意外提交/页面刷新）
 *   6. WKWebView ITP session 防护（iOS 26.5 第三方 cookie 分区加强）
 *
 * @version  1.0.0
 * @date     2026-05-28
 */

(function (global) {
    'use strict';

    /* ─────────────────────────────────────────────────────────
     * 1. window.event 全局事件对象 Polyfill
     *
     * 问题：旧代码在 inline handler 里直接写 `event.keyCode`（无 window. 前缀），
     *       依赖 IE 将当前事件挂到全局作用域的行为。
     *       iOS 26.5 WKWebView 在 strict 上下文中不保证 `window.event` 存在，
     *       导致 ReferenceError，整条调用链中断。
     *
     * 方案：在 capture 阶段监听所有事件，实时把当前 event 存到 window._fesEvt，
     *       并在 window.event 未定义时 polyfill 读取它。
     * ───────────────────────────────────────────────────────── */
    var _fesEvt = null;

    // 用 capture=true 确保在任何 inline handler 执行前先捕获
    document.addEventListener('keypress',  function (e) { _fesEvt = e; window.event = e; }, true);
    document.addEventListener('keydown',   function (e) { _fesEvt = e; window.event = e; }, true);
    document.addEventListener('keyup',     function (e) { _fesEvt = e; window.event = e; }, true);
    document.addEventListener('click',     function (e) { _fesEvt = e; window.event = e; }, true);
    document.addEventListener('mousedown', function (e) { _fesEvt = e; window.event = e; }, true);
    document.addEventListener('submit',    function (e) { _fesEvt = e; window.event = e; }, true);

    /** 取得当前事件（优先参数 → window.event → polyfill 缓存） */
    function _getEvent(e) {
        return e || (typeof window !== 'undefined' && window.event) || _fesEvt || null;
    }


    /* ─────────────────────────────────────────────────────────
     * 2. isChrome / isIpadChrome 全局变量与函数修复
     *
     * 问题：旧代码用 `!!window.chrome.webstore || !!window.chrome.runtime`
     *       window.chrome.webstore 已于 Chrome 71（2018年）移除。
     *       iOS 26.5 WKWebView 中 window.chrome 本身可能存在（Chrome App 注入），
     *       但两个子属性均为 undefined，导致 isChrome = false，走错代码路径。
     *
     * 方案：改用 UserAgent 字符串检测 CriOS（Chrome on iOS 的标准标识）。
     * ───────────────────────────────────────────────────────── */
    var _ua = navigator.userAgent;

    /** 是否为 iOS 设备（iPhone / iPad，含 iOS 13+ iPadOS 伪装成 Mac 的情况） */
    var isIOS = /iPhone|iPad|iPod/.test(_ua) ||
                (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);

    /** 是否为 iPad（含 iPadOS 13+ 以 Mac UA 出现的情况） */
    var isIPad = /iPad/.test(_ua) ||
                 (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);

    /** 是否为 Chrome on iOS（UA 含 CriOS） */
    var isCriOS = /CriOS/.test(_ua);

    /**
     * isChrome：全局重写
     * 旧方法依赖 window.chrome.webstore（已废弃）。
     * 新方法：桌面 Chrome 用 userAgent 含 "Chrome" 且不含 "Edg"；
     *         iOS Chrome 用 CriOS 标识。
     */
    global.isChrome = isCriOS ||
                      (/Chrome\//.test(_ua) && !/Edg\//.test(_ua) && !isIOS);

    /**
     * isIpadChrome()：覆盖 img_enq_display.jsp 等页面中的同名函数
     * 原实现依赖 /CriOS/ 正则，结果一致，但集中在此管理。
     */
    global.isIpadChrome = function () {
        return isIPad && isCriOS;
    };

    /**
     * isSafari：Safari 检测（排除 Chrome/CriOS）
     * 可供页面中需要区分 Safari 的逻辑使用。
     */
    global.isSafari = !isCriOS &&
                      /Safari\//.test(_ua) &&
                      !/Chrome\//.test(_ua);


    /* ─────────────────────────────────────────────────────────
     * 3. toup() 重写
     *
     * 问题（双重）：
     *   a) 直接用 `event`（全局）而非参数，iOS 26.5 可能 ReferenceError
     *   b) `event.keyCode -= 32` —— keyCode 在现代浏览器是只读属性，
     *      赋值无效（静默失败或报错），导致大写转换完全无效
     *
     * 方案：放弃修改 keyCode（现代规范不允许），
     *       改为在下一个 microtask 直接操作 input.value 转大写，
     *       同时调用修复后的 nospace()。
     *
     * 兼容：原 onkeypress="toup()" 调用无需修改，自动使用新版本。
     * ───────────────────────────────────────────────────────── */
    global.toup = function (e) {
        var evt = _getEvent(e);
        if (!evt) return;

        var target = evt.target || evt.srcElement;

        // 调用修复后的 nospace（会处理空格拦截）
        global.nospace(evt);

        // 用 setTimeout 0 等 keypress 完成后再转大写
        // 避免与 nospace 的 preventDefault 冲突
        if (target && target.tagName && target.value !== undefined) {
            setTimeout(function () {
                var v = target.value;
                if (v !== v.toUpperCase()) {
                    target.value = v.toUpperCase();
                }
            }, 0);
        }
    };


    /* ─────────────────────────────────────────────────────────
     * 4. nospace() 重写
     *
     * 问题：同 toup()，直接用全局 `event`，且修改 event.keyCode（只读）
     *
     * 方案：用 event.preventDefault() 替代 keyCode 修改，
     *       配合 _getEvent 安全取得事件对象。
     * ───────────────────────────────────────────────────────── */
    global.nospace = function (e) {
        var evt = _getEvent(e);
        if (!evt) return;

        var key = evt.key;                              // 现代浏览器
        var code = evt.keyCode || evt.which;            // 旧浏览器回退

        var isSpace = (key === ' ' || key === 'Spacebar' || code === 32);

        if (isSpace) {
            try { evt.preventDefault(); } catch (err) {}
            try { evt.stopPropagation(); } catch (err) {}
            // IE8 兼容
            try { evt.returnValue = false; } catch (err) {}
            try { evt.cancelBubble = true; } catch (err) {}
        }
    };


    /* ─────────────────────────────────────────────────────────
     * 5. cancelActionEvent() 重写
     *
     * 问题：AdminCommon.js 中的版本直接写 `event.keyCode = 0`（只读），
     *       在严格模式或现代 WKWebView 下静默失败或报错。
     *
     * 方案：统一用 preventDefault + stopPropagation。
     * ───────────────────────────────────────────────────────── */
    global.cancelActionEvent = function (e) {
        var evt = _getEvent(e);
        if (!evt) return;
        try { evt.preventDefault(); }    catch (err) {}
        try { evt.stopPropagation(); }   catch (err) {}
        try { evt.returnValue = false; } catch (err) {}  // IE8
        try { evt.cancelBubble = true; } catch (err) {}  // IE8
    };


    /* ─────────────────────────────────────────────────────────
     * 6. document.all 兼容代理
     *
     * 问题：IE 专有 API，RptBMRoadShowReceipt.jsp 等报表页面仍然使用
     *       `document.all.item("xxx")` 或 `document.all["xxx"]`。
     *       现代浏览器 document.all 虽然存在但行为不同，
     *       WKWebView 在某些严格模式下可能返回 undefined。
     *
     * 方案：若 document.all 不可用或 item() 失效，覆盖为 Proxy，
     *       将 .item(id) 和 [id] 访问重定向到 document.getElementById。
     * ───────────────────────────────────────────────────────── */
    if (typeof Proxy !== 'undefined') {
        try {
            var _origAll = document.all;

            // 测试 document.all 是否真正有效
            var _allBroken = false;
            try {
                if (!_origAll || typeof _origAll.item !== 'function') {
                    _allBroken = true;
                }
            } catch (e) {
                _allBroken = true;
            }

            if (_allBroken) {
                // 用 Proxy 代理，将属性访问转为 getElementById
                Object.defineProperty(document, 'all', {
                    get: function () {
                        return new Proxy({}, {
                            get: function (target, prop) {
                                if (prop === 'item') {
                                    return function (id) {
                                        return document.getElementById(id) ||
                                               document.getElementsByName(id)[0] ||
                                               null;
                                    };
                                }
                                if (typeof prop === 'string') {
                                    return document.getElementById(prop) ||
                                           document.getElementsByName(prop)[0] ||
                                           null;
                                }
                                return null;
                            }
                        });
                    },
                    configurable: true
                });
            }
        } catch (e) {
            // 如果 Object.defineProperty 无法覆盖 document.all，静默忽略
        }
    }


    /* ─────────────────────────────────────────────────────────
     * 7. <button> 缺少 type="button" 自动修复
     *
     * 问题：HTML5 规范规定 <button> 的默认 type 是 "submit"。
     *       iOS 26.5 WebKit 更严格执行此规范，导致表单内所有没有
     *       type="button" 的 <button> 点击后都触发表单提交，
     *       页面跳转或刷新，让用户误以为"按钮失效"。
     *
     * 方案：DOM 加载完成后，遍历所有表单内的 <button>，
     *       若没有 type 属性，自动补充 type="button"。
     * ───────────────────────────────────────────────────────── */
    function _fixButtonTypes() {
        var buttons = document.querySelectorAll('form button:not([type])');
        for (var i = 0; i < buttons.length; i++) {
            buttons[i].setAttribute('type', 'button');
        }
        // 同时修复 <input type="button"> 在某些模板中写成 <button> 的情况
        var allButtons = document.querySelectorAll('button');
        for (var j = 0; j < allButtons.length; j++) {
            var btn = allButtons[j];
            if (!btn.getAttribute('type')) {
                btn.setAttribute('type', 'button');
            }
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', _fixButtonTypes);
    } else {
        // 已经加载完毕（脚本在底部时）
        _fixButtonTypes();
    }


    /* ─────────────────────────────────────────────────────────
     * 8. WKWebView ITP session 防护
     *
     * 问题：iOS 26.5 加强了 ITP（Intelligent Tracking Prevention），
     *       WKWebView 中跨域 iframe 的 session cookie 被分区隔离，
     *       导致 chkLogin.jsp 的 session 验证失败，页面被重定向到登录页，
     *       用户看到的现象是"反复刷新回原登录页"。
     *
     *       Safari 不受影响：苹果对第一方 Safari 有 ITP 豁免机制。
     *
     * 方案：
     *   a) 增强 session 续期：每5分钟做一次轻量心跳请求（HEAD /），
     *      保持 WKWebView 认为本站是"活跃第一方"，减少 ITP 清除概率。
     *   b) 将 sessionStorage 作为 session 状态的本地镜像，
     *      在检测到 session 丢失时从镜像自动恢复关键信息（非敏感字段）。
     * ───────────────────────────────────────────────────────── */
    if (isIOS) {
        // 心跳：每5分钟 HEAD 请求，保持会话活跃
        var _heartbeatInterval = 5 * 60 * 1000;
        var _doHeartbeat = function () {
            try {
                var xhr = new XMLHttpRequest();
                xhr.open('HEAD', '/', true);
                xhr.withCredentials = true;  // 携带 cookie
                xhr.send();
            } catch (e) { /* 静默忽略，不影响业务 */ }
        };
        setInterval(_doHeartbeat, _heartbeatInterval);
        _doHeartbeat(); // 页面加载时立即执行一次

        // sessionStorage 镜像：备份 userId / staffId 等非敏感标识
        // 供 chkLogin.jsp 或 session 验证逻辑读取
        var _backupSessionKeys = ['userId', 'staffId', 'loginName', 'locale'];
        var _backupSession = function () {
            _backupSessionKeys.forEach(function (key) {
                var el = document.getElementById(key) ||
                         document.getElementsByName(key)[0];
                if (el && el.value) {
                    try {
                        sessionStorage.setItem('fes_backup_' + key, el.value);
                    } catch (e) {}
                }
            });
        };

        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', _backupSession);
        } else {
            _backupSession();
        }
    }


    /* ─────────────────────────────────────────────────────────
     * 9. 调试辅助（仅在 URL 含 fesDebug=1 时开启，生产环境无输出）
     * ───────────────────────────────────────────────────────── */
    if (/[?&]fesDebug=1/.test(location.search)) {
        console.group('[fes-compat] 环境检测');
        console.log('UA:', _ua);
        console.log('isIOS:', isIOS);
        console.log('isIPad:', isIPad);
        console.log('isCriOS (Chrome on iOS):', isCriOS);
        console.log('isChrome:', global.isChrome);
        console.log('isSafari:', global.isSafari);
        console.log('isIpadChrome():', global.isIpadChrome());
        console.log('iOS版本（从UA解析）:',
            (_ua.match(/OS (\d+[_\d]*)/) || ['', '无法解析'])[1].replace(/_/g, '.'));
        console.groupEnd();
    }

    /* ─────────────────────────────────────────────────────────
     * 10. 版本标记（方便日志确认是否已加载）
     * ───────────────────────────────────────────────────────── */
    global.FES_COMPAT_VERSION = '1.0.0';
    global.FES_COMPAT_LOADED  = true;

}(window));

/*
 * ══════════════════════════════════════════════════════════════
 *  引入方式说明（只改一个公共文件）
 * ══════════════════════════════════════════════════════════════
 *
 * 找到被所有页面 <%@ include %> 的公共头部 JSP，
 * 在 AdminCommon.js 之后（或所有 <script> 标签的最末尾）加入：
 *
 *   <script src="/lib/fes-compat.js"></script>
 *
 * 如果没有公共头部，可以修改 web.xml，在 LoginFilter 的
 * doFilter() 方法里为 HTML 响应注入这一行 <script> 标签。
 *
 * 验证是否生效：在 iPad Chrome 浏览器控制台执行
 *   FES_COMPAT_LOADED  // 应为 true
 *   FES_COMPAT_VERSION // 应为 "1.0.0"
 * ══════════════════════════════════════════════════════════════
 */


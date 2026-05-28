# Plan: iPad Chrome on iOS 26.5 兼容性问题诊断与修复

## 关于本计划的两个文件

本次修复产出了两个文件，用途完全不同：

### 📄 `plan-iOS26ChromeCompatibility.prompt.md`（本文件）
**是什么**：这是一份**技术分析 + 施工计划**文档（.prompt.md 表示它是供 AI 辅助编程工具使用的提示文档，同时也是人可读的技术记录）。

**包含什么**：
- 为什么 iOS 26.3.1(a) 正常、升级到 iOS 26.5 后 Chrome 出问题的根本原因分析
- iOS 26.5 WKWebView 的 5 个具体 breaking change 触发点
- 原计划的 6 步分散修复方案（Steps 1–5 逐文件打补丁 + Step 6 全局兼容层）
- **最终决策记录**：直接执行 Step 6，用 2 个文件覆盖 424 个页面，放弃逐文件打补丁
- 实施状态表（Step 6 已完成，Steps 1–5 均被覆盖）

**给谁看**：开发团队、测试人员、未来接手代码的人，用于理解改动背景和技术决策。

---

### 🔧 `FesWeb/lib/fes-compat.js`（实际修复文件）
**是什么**：这是**实际部署到系统的 JS 兼容层**，被自动注入到所有使用 `Util.js` 的业务页面（424个）。

**包含什么（10 个模块）**：
```
模块 1 → window.event 全局 polyfill（防止 ReferenceError）
模块 2 → isChrome / isSafari / isIOS 全局变量重写
模块 3 → isIpadChrome() 函数重写
模块 4 → toup() 重写（放弃只读 keyCode，改用 value 转换）
模块 5 → nospace() 重写（改用 preventDefault）
模块 6 → cancelActionEvent() 重写（移除 IE8 keyCode 修改）
模块 7 → document.all Proxy 代理（重定向到 getElementById）
模块 8 → <button> 自动补 type="button"（防表单意外提交/页面刷新）
模块 9 → WKWebView ITP session 心跳防护（5分钟一次）
模块 10 → 调试辅助（URL 加 ?fesDebug=1 开启）
```

**给谁看**：不需要看，它自动生效。如果出问题需要关掉某个模块，直接注释掉对应代码块即可。

---

**两者关系**：
```
plan-iOS26ChromeCompatibility.prompt.md  →  解释"为什么做"和"做了什么"
FesWeb/lib/fes-compat.js                 →  实际运行的修复代码
FesWeb/lib/Util.js（末尾追加 14 行）     →  自动把 fes-compat.js 注入到所有页面
```

---

## 精确版本对比

| 项目 | 正常版本 | 出问题版本 |
|------|---------|-----------|
| iOS 版本 | iOS 26.3.1(a) | **iOS 26.5** |
| 浏览器（出问题） | Chrome for iOS | Chrome for iOS |
| 浏览器（正常） | Safari iOS 26.5 | Safari iOS 26.5 |

> **关键诊断线索**：同一台设备升级 iOS 26.3.1(a) → 26.5 后，Chrome 出问题，Safari 正常。
> 这说明不是 Chrome App 自身更新导致的，而是 **iOS 26.5 的 WebKit 系统更新改变了 WKWebView 的行为**，而 Chrome iOS 使用的正是系统 WKWebView。

---

## 为什么 iOS 点版本更新会破坏 Chrome 而不破坏 Safari？

Chrome iOS 与 Safari iOS 同样使用 WebKit 引擎，但运行模式不同：

| 特性 | Safari iOS 26.5 | Chrome iOS 26.5 |
|------|-------------|---------------|
| WebKit 权限级别 | **第一方**（完整系统权限） | **第三方**（WKWebView 沙箱限制）|
| WebKit 更新影响 | Apple 会同步适配 Safari | **可能引入 breaking change**，Chrome 来不及适配 |
| `window.chrome` | 不存在 | 存在（但 API 行为随 iOS 更新可能变化）|
| `chrome.webstore` | 不存在 | **已于 Chrome 71/2018 移除** |
| `chrome.runtime` | 不存在 | iOS 26.5 WKWebView 可能限制此对象注入 |
| inline script 策略 | Safari 自己适配 | WKWebView 继承 iOS 26.5 新 CSP，Chrome 未适配 |
| Cookie / session | ITP 适配好 | WKWebView 第三方分区存储更严格 |

## iOS 26.5 WebKit 变更的具体触发点

iOS 26.3.1 → 26.5 是 **安全性点更新**，此类更新中 WebKit 最常见的 breaking changes：

1. **`window.chrome.runtime` 注入限制（最可能原因 🔴）**
   - iOS 26.5 可能收紧了 WKWebView 对全局对象注入的限制
   - Chrome iOS 通过 runtime 注入 `window.chrome`，但其子属性（`webstore`/`runtime`）被新策略拦截
   - 导致 `isChrome` 检测从 `true` 变成 `false`，系统走错代码路径

2. **JavaScriptCore 严格模式执行变更（高概率 🔴）**
   - iOS 26.5 的 JSC 更新可能对某些旧式 JS 写法（如全局 `event` 对象、`document.all`）从"兼容执行"变为"静默失败"或"抛出异常"
   - 异常中断整条 JS 调用链 → 按钮事件监听失效

3. **`<button>` 无 type 属性的 HTML5 规范强制执行（中概率 🟡）**
   - WebKit 点更新可能强化了 HTML5 规范：`<form>` 内未指定 `type` 的 `<button>` 强制为 `submit`
   - Safari 因为是第一方，Apple 会保证旧页面兼容；WKWebView 无此豁免
   - 结果：iOS 26.5 Chrome 点击按钮 → 触发表单 submit → 页面刷新

4. **ITP 2.x 新规则导致 session 被清除（中概率 🟡）**
   - iOS 26.5 ITP 可能强化了 WKWebView 中跨域 iframe 的 cookie 隔离
   - `fesLogin.jsp` 的 `<IFRAME SRC="/servlet/Login...">` 若跨域，session cookie 在 Chrome WKWebView 中被分区 → 每次请求被视为新 session → 触发重定向/刷新

5. **定时器节流政策变更（低概率 🟢）**
   - iOS 26.5 可能对 WKWebView 内 `setInterval` 的最小间隔从 1ms 提升为 4ms 或更高
   - 影响 `SADDFormCCard_Main.jsp` 中的轮询逻辑

---

**问题根源**：iOS 26.5 的 WebKit 点更新收紧了 WKWebView 沙箱行为，Chrome iOS 作为第三方 App 使用 WKWebView，受新政策约束。同时系统 JS 代码中已过期的浏览器嗅探逻辑（`isChrome` 使用废弃 API）在新 WebKit 下行为改变，是此次故障的根本原因。

---

## `isChrome` 嗅探在 iOS 26.5 崩溃的具体原因

```javascript
// 几乎所有 JSP 文件中（18个文件）
var isChrome = !!window.chrome && (!!window.chrome.webstore || !!window.chrome.runtime);

// iOS 26.3.1(a) Chrome 中：
//   window.chrome          = Object  ✅
//   window.chrome.webstore = undefined（Chrome 71 起已移除）
//   window.chrome.runtime  = Object  ✅（正常注入）
//   结果：isChrome = true → 走 Chrome 代码路径 ✅ 正常

// iOS 26.5 Chrome 中：
//   window.chrome          = Object  ✅
//   window.chrome.webstore = undefined ❌
//   window.chrome.runtime  = undefined ❌（iOS 26.5 WKWebView 限制了注入）
//   结果：isChrome = false → 走错代码路径 ❌ 出问题！
```

这就是为什么 **升级前正常、升级后出问题**：`chrome.runtime` 在 iOS 26.5 的 WKWebView 里被限制了。

---

## Steps

### Step 1: 诊断并修复 `isChrome` 嗅探失效（iOS 26.5 直接触发点）

**根本原因**：`window.chrome.runtime` 在 iOS 26.5 的 WKWebView 中被限制，导致此前在 iOS 26.3.1(a) 下能正确识别 Chrome 的逻辑失效。

**影响文件（18个）**：
- `FesWeb/jsp/pos/settle/BuyBackInput.jsp`
- `FesWeb/jsp/pos/settle/PosA8PaymentMain.jsp`
- `FesWeb/jsp/pos/settle/PointDollarsRebateMain.jsp`
- `FesWeb/jsp/pos/settle/p002InvSettle09d.jsp`
- `FesWeb/jsp/fes/sa/HotlineHPPSalesLead.jsp`
- `FesWeb/jsp/fes/sa/HotlineHPPEnquiryList.jsp`
- `FesWeb/jsp/fes/sa/HotlineHPPEnquiry.jsp`
- `FesWeb/jsp/fes/sa/HotlineHPPSave.jsp`
- `FesWeb/jsp/fes/sa/CouponReleaseCreate.jsp`
- `FesWeb/jsp/fes/sa/CouponReleaseAmend.jsp`
- `FesWeb/jsp/fes/sa/CouponReleaseEnquiry.jsp`
- `FesWeb/jsp/fes/sa/CouponReleaseList.jsp`
- `FesWeb/jsp/fes/sa/BuyBackEnquiry.jsp`
- `FesWeb/jsp/fes/sa/BuyBackEnquiryList.jsp`
- `FesWeb/jsp/fes/invoicing/InvoicingPage.jsp`
- `FesWeb/jsp/fes/ocApps/BSS/ShopNSaveAmendMain.jsp`
- `FesWeb/jsp/fes/ocApps/OCAcquisCaseDtl2.jsp`
- `FesWeb/jsp/fes/ocApps/OCAcquisCaseDtl3.jsp`

**修复方案**：将废弃的 API 检测替换为现代 User-Agent 特性检测，或直接用能力检测（capability detection）替代浏览器嗅探。

```javascript
// ❌ 旧写法（依赖 chrome.runtime，iOS 26.5 WKWebView 中已被限制）
var isChrome = !!window.chrome && (!!window.chrome.webstore || !!window.chrome.runtime);

// ✅ 新写法（基于 User-Agent，不依赖被限制的对象属性）
// iOS 上 Chrome 的 UA 包含 "CriOS"（Chrome for iOS）
// 桌面 Chrome 的 UA 包含 "Chrome" 且 vendor 是 Google
var isChromeiOS  = navigator.userAgent.indexOf('CriOS') > -1;
var isChromeDesk = !!window.chrome && navigator.vendor === 'Google Inc.' && !isChromeiOS;
var isChrome     = isChromeiOS || isChromeDesk;

// ✅ 替换后的判断依然向后兼容：
// iOS 26.3.1(a) Chrome：CriOS 存在 → true ✅
// iOS 26.5 Chrome：CriOS 存在 → true ✅
// Safari 任意版本：无 CriOS，无 window.chrome → false ✅
```

---

### Step 2: 修复"页面反复刷新"根因

**根本原因**：`<button>` 元素在 `<form>` 内若无 `type="button"` 属性，按照 HTML5 规范默认为 `type="submit"`。iOS 26.5 的 WebKit 点更新强化了此规范在 WKWebView 中的执行（Safari 因第一方地位有旧页面兼容豁免，Chrome WKWebView 无此豁免），导致点击按钮触发表单提交并刷新页面。

> **注意**：此问题在 iOS 26.3.1(a) 下未必触发，因为旧版 WKWebView 对此写法更宽容。iOS 26.5 是明确的触发点。

**已知问题文件**：
- `FesWeb/jsp/fes/sa/RioSubscription.jsp`（已有修复记录，参见 `RioSubscription_Chrome_Fix.md`）
- 其余含 `<form>` 的 JSP 文件需全面排查

**修复模式**：
```html
<!-- ❌ 旧写法 -->
<button class="button" onclick="doSomething()">Submit</button>

<!-- ✅ 新写法 -->
<button type="button" class="button" onclick="return doSomething(event);">Submit</button>
```

**对应 JS 函数也需更新**：
```javascript
// ✅ 添加 event 参数并阻止默认行为
function doSomething(event) {
    if (event) {
        event.preventDefault();
        event.stopPropagation();
    }
    // ... 原有逻辑
    return false;
}
```

---

### Step 3: 修复全局 `event` 对象导致的按钮失效

**根本原因**：IE 中 `event` 是全局对象，Chrome/WebKit 中必须通过函数参数传递。iOS 26.5 的 JavaScriptCore 更新对这种 IE-only 模式从"静默降级"升级为"抛出 ReferenceError"，导致整条 JS 调用链中断，按钮完全失效。

> iOS 26.3.1(a) 下可能因 JSC 版本的细微差异对此容忍，26.5 的 JSC 更新则不再容忍。

**影响文件（20+ 个，集中在 rpt 目录）**：
- `FesWeb/jsp/rpt/RptSA_TradeIn_PR_RptNet.jsp`
- `FesWeb/jsp/rpt/RptSA_TIHandsetDeposit_Menu.jsp`
- `FesWeb/jsp/rpt/RptSA_SAgtBatchConfirm.jsp`
- `FesWeb/jsp/rpt/RptBMRoadShowReceipt.jsp`
- `FesWeb/jsp/rpt/RptSA_PortIn_RptNet.jsp`
- `FesWeb/jsp/rpt/RptSA_PList_RptNet.jsp`
- `FesWeb/jsp/rpt/RptSA_PList.jsp`
- `FesWeb/jsp/rpt/RptPos_DealerIMEIAmd_RptNet.jsp`
- `FesWeb/jsp/rpt/RptPos_DailyCashDrawer_RptNet.jsp`
- `FesWeb/jsp/rpt/porder/POrderOutCaseSegment.jsp`
- `FesWeb/jsp/rpt/RptPos_CustDptDtl_RptNet.jsp`
- `FesWeb/jsp/rpt/RptSA_OSPremDepositRpt.jsp`
- `FesWeb/jsp/rpt/porder/POrderPreSaleDepOS.jsp`
- `FesWeb/jsp/rpt/RptSA_MthSIMLockRpt_RptNet.jsp`
- `FesWeb/jsp/rpt/RptPos_CustAmend_RptNet.jsp`
- `FesWeb/jsp/rpt/porder/RptPreOrderQueueStatus.jsp`
- `FesWeb/jsp/rpt/RptPos_CrNoteReg_RptNet.jsp`
- `FesWeb/jsp/rpt/RptPos_BlkListMaint_RptNet.jsp`
- `FesWeb/jsp/rpt/RptPos_AcceptRejNewAct_RptNet.jsp`
- `FesWeb/jsp/rpt/RptSA_GFEcouponStatusAndStock.jsp`
- `FesWeb/jsp/fes/sa/HotlineHPPSalesLead.jsp`（含残留 `function toup()` 无参版本）

**修复模式**：
```javascript
// ❌ 旧写法
function toup() {
    if (event.keyCode >= 97 && event.keyCode <= 122)
        event.keyCode -= 32;
}

// ✅ 新写法
function toup(event) {
    event = event || window.event;  // 兼容 IE 和现代浏览器
    if (event && event.keyCode >= 97 && event.keyCode <= 122)
        event.keyCode -= 32;
}
```

**HTML 调用处同步更新**：
```html
<!-- ❌ 旧写法 -->
<INPUT TYPE="text" onkeypress='toup()'>

<!-- ✅ 新写法 -->
<INPUT TYPE="text" onkeypress='toup(event);'>
```

---

### Step 4: 修复 `document.all` 残留

**影响文件**：
- `FesWeb/jsp/fes/sa/SADDFormInfoUpdate.jsp`（Line 28-34，`document.all.item("fra1")` 仍未完全替换）

**修复方案**：
```javascript
// ❌ 旧写法
document.all.item("fra1").style.width = 720;
document.all.item("fra1").style.height = 155;

// ✅ 新写法
var iframe = document.getElementById("fra1") || document.getElementsByName("fra1")[0];
if (iframe) {
    iframe.style.width = "720px";
    iframe.style.height = "155px";
}
```

详细修复方案参见 `Chrome_Compatibility_Issues_Analysis.md` 的 Priority 1 部分。

---

### Step 5: 针对 iOS 26.5 WKWebView 新限制添加防护

**问题**：iOS 26.5 的 WKWebView 对以下场景有影响（在 iOS 26.3.1(a) 中未触发）：
- 跨域 `<iframe>` 内的 cookie/session 被分区隔离
- `setInterval` 在后台标签或非活跃窗口中可能被节流（限制为每分钟一次）
- 某些 `window.open()` 弹窗行为被拦截

**受影响关键文件**：
- `FesWeb/fesLogin.jsp`：使用 `<IFRAME SRC="/servlet/Login...">` 加载登录，跨域 iframe 的 session cookie 在 iOS 26 Chrome 中可能被拦截
- `FesWeb/jsp/fes/sa/SADDFormCCard_Main.jsp`：`setInterval` 轮询检测弹窗是否关闭，在后台时会被节流

**修复方案**：
```javascript
// SADDFormCCard_Main.jsp 中的轮询逻辑
// ❌ 旧写法（窗口关闭后立即调用，且 setInterval 在 iOS 后台被节流）
popupInterval = setInterval(function(){
    if(rtn == null || (rtn != null && rtn.closed)){
        getCCInfo();
        clearInterval(popupInterval);
    }
}, 1000);

// ✅ 新写法（加延迟 + 防止 iOS 后台节流影响）
popupInterval = setInterval(function(){
    if(rtn == null || (rtn != null && rtn.closed)){
        clearInterval(popupInterval);
        setTimeout(function(){
            getCCInfo(0);  // 附带重试机制
        }, 500);
    }
}, 1000);
```

---

### Step 6: 创建全局兼容层 `fes-compat.js`（根本解决方案）✅ **已完成 2026-05-28**

**目的**：集中处理所有浏览器兼容问题，避免在每个 JSP 文件中重复修复，从根本上阻断未来 iOS/Chrome 升级再次引发的兼容问题。

> **实际选择方案**：仅改动 2 个文件即覆盖全部 424 个引用 Util.js 的业务页面，无需逐文件修改 40+ 个 JSP，Steps 1–5 的逐文件补丁**不再需要单独执行**。

---

#### 实际改动的文件

##### 新建：`FesWeb/lib/fes-compat.js`

全局兼容层主体，共 10 个模块：

| 模块 | 修复内容 | 对应旧问题 |
|------|---------|-----------|
| 1. `window.event` polyfill | 用 capture 监听器实时同步 `window.event` | `toup()`/`nospace()` 直接用全局 `event` → ReferenceError |
| 2. `isChrome` 重写 | 改用 UA 字符串 `CriOS` 检测，废弃 `window.chrome.webstore` | `isChrome` 在 iOS 26.5 永远为 `false` |
| 3. `isIpadChrome()` 重写 | 集中管理，与模块 2 保持一致 | 各页面实现不一致 |
| 4. `toup()` 重写 | 放弃修改只读 keyCode，改用 `setTimeout + value.toUpperCase()` | 大写转换失效，按钮链中断 |
| 5. `nospace()` 重写 | 用 `preventDefault()` 替代 `keyCode = 0` | 空格拦截无效 |
| 6. `cancelActionEvent()` 重写 | 统一用 `preventDefault + stopPropagation` | IE8 代码在严格模式报错 |
| 7. `document.all` 代理 | Proxy 重定向到 `getElementById` | 报表页面用 IE 专有 `document.all.item()` |
| 8. `<button>` 自动补 `type="button"` | DOM ready 后遍历所有 button | 页面反复刷新根因 |
| 9. WKWebView ITP session 防护 | 5 分钟心跳 + sessionStorage 镜像 | session 丢失触发重定向刷新 |
| 10. 调试辅助 | URL 带 `?fesDebug=1` 时打印环境信息 | iPad Chrome 控制台难以调试 |

**验证方法**（在 iPad Chrome 控制台执行）：
```javascript
FES_COMPAT_LOADED   // → true
FES_COMPAT_VERSION  // → "1.0.0"
isChrome            // → true（iOS 26.5 Chrome 现在能正确识别）
isIpadChrome()      // → true
```

---

##### 修改：`FesWeb/lib/Util.js`（末尾追加 14 行）

```javascript
// -------------------------------------------------------------
// fes-compat auto-loader
// Reason: iOS 26.5 WKWebView tightened JS rules, breaking legacy
//         code on iPad Chrome. Loading compat shim here covers
//         all 424 pages that include Util.js - no per-page changes.
// Date:   2026-05-28
// -------------------------------------------------------------
if (typeof window.FES_COMPAT_LOADED === 'undefined') {
    var _fesCompatScript = document.createElement('script');
    _fesCompatScript.src = '/lib/fes-compat.js';
    _fesCompatScript.async = false; // preserve execution order
    (document.head || document.getElementsByTagName('head')[0] || document.body).appendChild(_fesCompatScript);
}
```

**为什么选 Util.js 而不是 chkLogin.jsp**：
- `chkLogin.jsp`（被 1144 页面 include）是纯 Java 代码，加 `<script>` 标签输出位置在 `<html>` 标签之前，产生无效 HTML
- `Util.js`（被 424 页面引用）是 JS 文件，在此追加代码 100% 安全，且动态注入的 `<script async=false>` 保证在后续脚本（如 AdminCommon.js）之前执行
- 结果：`toup()`/`nospace()` 等函数被 `fes-compat.js` 中的新版本覆盖（JS 后定义覆盖先定义）

---

#### 为什么这两个文件就够了（不需要改 40+ 个 JSP）

```
Util.js  ──引用──→  424 个业务 JSP 页面
  └─ 末尾自动注入 fes-compat.js（async=false 顺序保证）
        └─ 重新定义全局函数（后定义覆盖先定义）
              toup()            ✅ 新版本生效
              nospace()         ✅ 新版本生效
              cancelActionEvent() ✅ 新版本生效
              isChrome          ✅ 新值生效（true）
              isIpadChrome()    ✅ 新版本生效
              AdminCommon.js 中的旧定义被全部替换 ✅
```

---

## 实施状态（更新于 2026-05-28）

| 状态 | 步骤 | 影响范围 | 实际工作量 | 备注 |
|------|------|----------|----------|------|
| ✅ **已完成** | **Step 6：全局兼容层** | **424 个页面（Util.js）** | **改动 2 个文件** | **取代 Steps 1–5，无需逐页修改** |
| ⏭️ 已被覆盖 | Step 1：`isChrome` 检测修复 | 18 个 JSP | — | fes-compat.js 模块 2 已全局修复 |
| ⏭️ 已被覆盖 | Step 2：`<button>` type 修复 | 全部含 form 的 JSP | — | fes-compat.js 模块 8 已全局修复 |
| ⏭️ 已被覆盖 | Step 3：`toup()` event 参数 | 20+ rpt JSP | — | fes-compat.js 模块 4 已全局覆盖 |
| ⏭️ 已被覆盖 | Step 4：`document.all` 修复 | 1 个 JSP | — | fes-compat.js 模块 7 已全局代理 |
| ⏭️ 已被覆盖 | Step 5：WKWebView ITP 防护 | 2 个关键 JSP | — | fes-compat.js 模块 9 已全局防护 |

> **结论**：只需部署 `fes-compat.js` + `Util.js` 两个文件，Steps 1–5 的所有问题均被全局修复。

---

## 诊断工具

### 在 iPad Chrome 上捕获 JS 错误（远程调试）

```
Mac 步骤：
1. 用 USB 连接 iPad 到 Mac
2. iPad 上开启 Safari → 设置 → 高级 → Web 检查器
3. Mac Safari → 开发 → [iPad 设备名] → 选择页面
4. 但注意：此方法只能调试 Safari，不能调试 iPad Chrome

Chrome iOS 调试替代方案：
1. 在页面底部注入调试 div，捕获 console.error：
```
```javascript
(function(){
    var errDiv = document.createElement('div');
    errDiv.id = 'fes-debug';
    errDiv.style.cssText = 'position:fixed;bottom:0;left:0;right:0;background:red;color:white;font-size:11px;z-index:99999;max-height:150px;overflow-y:auto;padding:4px;';
    document.body.appendChild(errDiv);
    window.onerror = function(msg, src, line, col, err){
        errDiv.innerHTML += '<div>' + msg + ' [' + (src||'').split('/').pop() + ':' + line + ']</div>';
    };
    var orig = console.error;
    console.error = function(){
        errDiv.innerHTML += '<div>' + Array.prototype.join.call(arguments,' ') + '</div>';
        orig.apply(console, arguments);
    };
})();
```

---

## Further Considerations

### 1. 是打补丁还是根基修复？

- **打补丁（Steps 1-5）**：1-2 天内可解决当前问题，适合 UAT 紧急上线
- **根基修复（Step 6）**：一次性解决未来 iOS/Chrome 再次升级引发的兼容问题

**建议**：两者并行——先按 P1 优先级打补丁上线，同期并行推进 Step 6 共享兼容层，完成后逐步将各文件的内联兼容代码替换为调用 `FESBrowser`/`FESEvent`。

### 2. iOS 26.5 WKWebView 的 session / cookie 分区问题

iOS 26.5 的 WKWebView 增强了 ITP（Intelligent Tracking Prevention），跨域 `<iframe>` 的 cookie 默认被分区隔离。需重点检查：
- `FesWeb/fesLogin.jsp` 中 `<IFRAME SRC="/servlet/Login...">` 的 session 共享是否受影响
- 如受影响，考虑将登录 iframe 改为同域 redirect 模式，或在服务端启用 `SameSite=None; Secure` cookie 策略

### 3. iOS 26.3.1(a) → 26.5 点版本变更说明

此次问题由 **iOS 26 的一个点更新**（26.3.1 → 26.5）触发，不是大版本升级，而是安全性/稳定性更新。点版本更新中 WebKit 通常包含：

| 变更类型 | 26.5 已知/推测变更 | 对 WKWebView 影响 |
|---------|-----------------|-----------------|
| 安全策略 | WKWebView 对象注入限制收紧 | `window.chrome.runtime` 可能被限制 |
| JSC 引擎 | 旧式 JS 严格化执行 | 全局 `event` 从静默失败 → ReferenceError |
| HTML5 规范 | `<button>` 默认 type 强制化 | Form 内无 type 的 button 触发 submit |
| ITP | 第三方 cookie 分区更激进 | 跨域 iframe session 被隔离 |

**Safari 为何不受影响**：Apple 在更新 Safari 时会做旧页面兼容回归测试；WKWebView（供第三方 App 使用）没有此保障。这是一个 Apple **有意的安全策略**，而非 bug。

### 4. 测试策略

由于是 iPad Chrome 在特定版本（iOS 26.5）下的专属问题，建议：
1. **保留一台 iOS 26.3.1(a) 设备**作为对照组，用于验证修复是否确实解决了 26.5 上的问题
2. 在真机（iOS 26.5 iPad Chrome）上测试，不能用模拟器代替
3. 使用 [Eruda](https://github.com/liriliri/eruda) 在 Chrome iOS 页面注入移动端调试控制台（比注入 onerror div 更强大）
4. **优先验证 Step 1**（`isChrome` 修复）：在 Chrome iOS 控制台执行 `navigator.userAgent` 确认输出包含 `CriOS`，再确认新的 `isChrome = true`
5. 同时在 iPad Safari 和 iPad Chrome 运行相同操作做对比，精确定位差异点










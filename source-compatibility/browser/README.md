# 原生书源浏览器：设计、验证与后续工作

更新：2026-09-14。主要目标：[Issue #184](https://github.com/Renakoni/hnovel/issues/184)。复用已合并的解释器 [PR #183](https://github.com/Renakoni/hnovel/pull/183) 的真实 WebView UA 与数据预算接口，基于 main 提交。普通 HTTP 与原生 WebView 按来源分工；本目录记录实际支持范围，不把一次通过验证当作长期免验证保证。

## 当前状态

当前实施设计见 [职责、Chromix 映射与验收计划](IMPLEMENTATION_PLAN.md)，新增 [稳定性实测](STABILITY.md)。原生基础 PR #187 的 JVM、API 24、API 35 CI 均已通过；本分支接续 #185，加入前台自动打开验证及完成后一次重试、后台停止提示、入口/版本/账号取消和真实布局。MuMu 已通过新版自动恢复 fixture。下面的实站计数来自 #187 基线；#185 的新增测试与环境数据单独记录在实施计划中。

- 已实现原生联网、iframe、fetch、Worker、POST 表单和持续来源账号 profile，不向网页暴露 `SourceBrowser` 特权桥。
- 新 MuMu Android 12 / WebView 110 完成外层 Cloudflare 验证、hlib 登录、站内 `/antibot` 验证并返回原搜索页面。真实用户菜单与搜索结果已确认。
- 当前会话观察到持久 `cf_clearance`、`connect.sid`、`__nuvt`、`__suvt`；只记录名称和属性，不导出值，不借用桌面 Chrome 或 MD3 的 Cookie。
- 搜索 → 详情 → 目录 → 正文已通过真实生产执行：搜索 30 项、目录 63 章、首章 608 段／34,346 字符，耗时 45.273 秒。安装更新和进程重启后复用本 App 的持久登录，未重输密码。此前 `loginCheckJs` 数据预算限制由前置 #183 解决，未在本 PR 重复实现预算框架。
- 最后设备回归后重启再读，搜索入口再次收到外层 Cloudflare，返回 `BrowserRequired: searchUrl`；此时仍有上述持久 Cookie。正常点击验证后返回 `/search`，存在退出登录链接、无登录链接、结果 30 项；随后完整生产读取再次通过，仍为 63 章、608 段／34,346 字符，耗时 101.507 秒，未重输密码。**验证确实复发，恢复也已验证；不能描述成重启后稳定免验证。** Cookie 存在只证明存储仍在，不能证明挑战前服务端已接受该 clearance；未确认复发的唯一原因。
- 参考 MD3 本次回归也通过搜索 30 项、目录 63 章／3 页、首章 4 页，耗时 18.226 秒。此处比较结构与流程，未声称正文逐字相同或这是公平的性能基准。
- 最终设备回归 18 项通过（原生 8、旧浏览器 9、账号存储 1），另 1 项环境采样未提供参数而跳过；已知超时的旧图片验证码 UI 用例未重跑，未计入通过数。叠加 #183 后仓库 JVM CI 共 820 项通过，无失败或跳过；最后的账号/管理界面相关单测也通过。桌面/MD3/两条 App 浏览器路径的中性环境采样已完成。

## 声明与责任边界

来源 JSON 使用布尔扩展 `"browserRead": true`，默认值为 false。它使 Document 请求使用同一原生浏览器账号。**纠正此前说明：普通规则 `java.connect/ajax` 当前编译为 API 请求，仍走 HTTP；需要原生会话时必须显式声明 URL 的 `webView:true` 选项。** 图片、二进制和导入请求也有明确的 HTTP 路径。原生路径只接受 GET 导航；页面自己发出的 POST/fetch 由 Chromium 执行。传入 HTML、显式 Cookie 请求头、`followRedirects=false`、只读缓存、十六进制响应或顶层非 GET 请求会被拒绝，不静默改变含义。DOM 默认上限 512 KiB；显式较大预算也不超过 1 MiB。

`NativeSourceBrowser` 负责宿主准入、请求串行、进程所有权、取消及结果提交；`NativeBrowserFiles` 负责停止进程后的 profile 文件切换；`NativeSourceBrowserService` 只运行网站和提取结果；`NativeSourceBrowserActivity` 提供前台窗口。使用一个专用进程，所以当前不同原生来源不能并行浏览。

按源网络路线由 [#219](https://github.com/Renakoni/hnovel/issues/219) 接入：支持 API 28+ 且提供 `PROXY_OVERRIDE` 的 WebView。绕过模式仅在专用浏览器进程初始化 Chromium 前绑定本次捕获的非 VPN 网络，并等待 direct proxy override 生效；主进程不绑定网络。来源会话或路线代次改变时先结束旧进程再重用对应账号目录。路线失效、来源关闭也会结束空闲进程及其后台 Service Worker。模式切换保留持久 Cookie/localStorage，不轮换账号；内存 session Cookie 仍遵循进程生命周期。具体限制和实测范围见 [按源网络路线](../SOURCE_NETWORK_MODES.md#native-browser-route)。

入口 URL 仍经过来源授权和地址检查。进入原生 WebView 后，重定向、iframe、子资源、页面 POST、Worker 遵循 Chromium 的网络和同源安全模型；公共 WebView API **无法提供** HTTP broker 对所有请求和实际对端的同等检查。页面可以向其正常浏览器允许访问的网络地址发请求。此边界由 #184 明确修正，不能把它宣称为 #173 的严格逐请求权限验收已完成。

页面没有 JavaScriptInterface；禁用文件/content 访问、混合内容、设备权限、下载和外部 scheme 跳转；内部 frame 的 `about:blank`、`about:srcdoc` 可用；证书错误使用正常拒绝处理。Debug 构建允许开发者调试 WebView，Release 不主动开放调试。

DOM 结果使用 `ResponseKind.BrowserDocument`，保存同一次快照中的实际最终 URL。状态码为 0，协议/响应头为空，`raw()` 为 null，`isBrowserDocument()` 为 true，`isSuccessful()` 为 false。它是提取到的文档，不是原始 HTTP 响应；规则不能据此推断网络使用了 HTTP/1.1 或返回了 200。现有 HTTP 结果保持原始状态和协议语义。

## Cookie 与来源账号

所有权键由 namespace、sourceId、App profile 和 accountGeneration 构成并哈希成目录名；网页不能自行指定其他来源的 profile。普通任务结束保留 Chromium 进程，维持同一浏览器会话内的 session Cookie。持久 Cookie/localStorage 等由 WebView 原生存储。

支持目录重定位的 provider 使用 AndroidX `ProcessGlobalConfig`。不支持时，在 API 28+ 使用固定 `setDataDirectorySuffix`：**先关闭旧浏览器进程并等待死亡，再整体重命名其目录到对应账号**。不复制运行中的 SQLite 数据库；固定 WebView HTTP 缓存在切换账号时删除，避免跨账号复用。API 24–27 且不支持重定位时明确返回 BrowserRequired，不能假装账号已隔离。

此轮 MuMu 不支持 `MULTI_PROFILE`、目录重定位、UA metadata，支持 `GET_COOKIE_INFO`；因此实际验收覆盖的是 API 28+ 文件切换方案。新 provider 的重定位方案仍需相应设备验收。切换进程/账号及系统杀进程后的 session Cookie 生命周期遵从 Chromium，未把 session Cookie 强行改成持久 Cookie；需要网站的“保持登录”时应使用网站本身的选项。

原生登录/再次验证复用当前账号代次，关闭窗口不会自动退出已有账号。显式退出登录先作废旧账号、取消请求，再清理原生状态；旧回调不能回写。临时书源诊断结束也显式清理其专属 profile。profile 位于应用私有目录并排除备份/设备迁移；这不代表 WebView 数据已通过普通账号存储的 Keystore/AES-GCM 再加密。

不实现通用 Cookie 双向同步。Android CookieManager 是原生路径的权威；普通 HTTP jar 是 HTTP 路径的权威。`cookie.*` 脚本接口不会神奇地变成原生 Cookie 导入/导出接口。依赖裸 Cookie 注入或受保护图片 HTTP 的来源仍需单独适配。

## 参考项目如何完成“验证透传”

对照 MD3 `fb01a76` 的 `WebBook`、`JsExtensions.startBrowserAwait`、`SourceVerificationHelp` 和 `WebViewModel.saveVerificationResult`：

1. 书源通过 `loginCheckJs` 或辅助函数识别挑战/登录页面。hlib 调用 `java.startBrowserAwait(target, title, false)`。
2. 参考 App 打开正常 WebView，并等待验证结果；网页在真实 URL、正常 Cookie 与 iframe 中完成验证。
3. false 参数令完成按钮直接提取当前 DOM；true 则可再次请求原 URL。等待中的规则拿到结果继续解析。
4. 它不是所有 HTTP 403 都自动弹窗。来源是否识别挑战、调用了哪种接口，决定了实际行为。

本轮源码核对：`WebBook.kt:73` 在请求结果返回后执行 `loginCheckJs`；`JsExtensions.kt:358` 调用 `getVerificationResult`；`SourceVerificationHelp.kt:33` 打开窗口、等待结果，`checkResult` 唤醒等待线程；`WebViewModel.kt:97` 处理确认按钮的 DOM/重新请求分支。确认按钮不等于网站已经认证，hlib 辅助函数还会重新检查登录/挑战标记。参考 App 本轮第一次调试也曾等待验证失败，经正常登录窗口确认后重试才完成全流程，不能据此声称它从不重复验证。

我们的非交互原生请求在 service 识别挑战后返回 `BrowserRequired`，`RuleSource.executeRequest` 会在执行 `loginCheckJs` **之前**结束。本分支用宿主拥有的 `SourceVerification` 保留确切请求与来源权限，`SourceVerificationCoordinator` 在规则预算之外自动打开前台验证，同账号窗口满足原始就绪条件后自动返回并重试读取一次。再次被挑战则返回错误。脚本显式 WebView 请求的失败也保留这一恢复操作。

恢复绑定来源、版本、账号代次和具体请求，不使用可能被另一个请求覆盖的全局 URL。搜索、发现列表、详情和当前阅读请求由 UI 授予交互权限；后台下载、预取、相邻章节不继承该权限。后台返回认证错误并停止，前台提示验证入口，完成后用户重新发起下载；不自动复活 WorkManager。通知队列仅在进程内保存，失败原因由既有 WorkManager output 保留。发现按钮脚本可能有副作用，不自动重放这类动作。

## 官方论坛对结论的修正

已在我们 App 的登录会话读取[用户指定的官方帖](https://hlib.cc/forum/t/qOwnYI_f)，标题为《【官方】如果你被伊蕾娜频繁检查，请先确认以下内容》。正文及回复来自 2023–2024 年，属于站方的历史说明，不是当前后台策略或本次故障归因证明。

- 帖子指出频繁申请新 Cookie、页面访问与后续回报使用不同 Cookie、浏览器后续回报被插件拦截，会造成异常会话行为。不能把“借取 Cookie”简化为复制一个字符串。
- 站内访问检测与 CSR 搜索结果加载前的验证是不同环节；页面顶部 Cloudflare Challenge、登录页 Turnstile、站内 `/antibot`、搜索页 Turnstile 也不能混为一次验证。
- 2024-02 回复提到短时间高频请求、未跟随验证重定向以及延迟/丢包后反复刷新；2024-03 更新表示暂停封禁机制。不能引用早期段落宣称当前仍会按同一机制封禁。
- 这支持优先保持同一原生会话、正常页面脚本/网络、遵从跳转和合理请求节奏。当前没有证据证明本次循环主要由硬件指纹造成。

本分支落实 [#186](https://github.com/Renakoni/hnovel/issues/186)：`concurrentRate` 从来源定义接入 HTTP 和原生导航共用的会话限速器，`1/2000` 表示每两秒一次。准入发生在实际执行窗口内；网页子资源维持 Chromium 行为。完整语义、非法值、取消、缓存和重试边界见 [来源请求节奏](REQUEST_PACING.md)。

## 环境对照与 Chromix 取舍

同一中性页面记录主 frame、iframe、Worker、网络 UA、HttpOnly Cookie、fetch/XHR 是否原生。数据见 [environment-comparison.json](environment-comparison.json)，探针见 [probe.js](probe.js)。不采集真实站凭据或正文，也不将这份探针称为 Cloudflare 风控评分。

| 观察项 | 普通 Chrome | MD3（真实 WebView UA） | 我们原生路径 | 我们旧路径 |
|---|---|---|---|---|
| webdriver | false | false | false | false |
| fetch / XHR | 原生 | 原生 | 原生 | 重写 |
| document 自有 cookie 属性 | 无 | 无 | 无 | 有 |
| SourceBrowser 特权桥 | 无 | 无 | 无 | 有 |
| iframe / Worker | 正常 | 正常 | 正常 | 不可用 |
| 内核/平台 | Chrome 153 / Win32 | WebView 110 / Android 12 | WebView 110 / Android 12 | 同一 MuMu |

MD3 没有显式源 UA 时在该探针中使用 Windows Chrome 128 UA，同时 platform 为 Linux、touch 为 5；hlib 已显式调用 `getWebViewUA()`，所以不能把 MD3 的默认 UA 差异当作当前 hlib 的指纹问题。MuMu 报告 Adreno 640，仅代表模拟器/provider 对网页的报告，不证明物理 GPU 是它。真实桌面 Chrome 的 NVIDIA GPU、20 线程、无触摸，与 Android 差异本身正常。

Chromix `1222eec`（Chromium pin 152.0.7977.82）用于借鉴稳定环境、跨 realm 一致性和后端验证。其桌面 persona、Blink/GPU 补丁不是 Android WebView 配置。扩展采样已覆盖 window、同源/跨源 iframe、dedicated Worker、Canvas 像素和 WebGL 实际绘制；当前 provider 不支持 SharedWorker/WebGPU。4 次前后台 ClientHello 规范化后一致，HTTP/2 SETTINGS 和伪首部顺序一致；这是自有回环端点、调试证书例外下的观测，不覆盖外部线路、QUIC 或会话恢复。完整决策及边界见 [实施计划](IMPLEMENTATION_PLAN.md)。

基线测出我们和参考 MD3 的后台 viewport 都为 0×0，而前台为 1098×546。本分支在导航前按 Android display metrics 对 WebView 实际 measure/layout，前台附着后由 Activity 正常布局，未覆盖 JS getter。两种路径仍存在焦点/窗口状态差异；保留 profile/进程不等于保留页面，也不保证站点延迟上报在 DOM 提取前结束。没有这些因素与 CF 复发的受控因果证据。

## 验证和下一步

- [x] JVM CI 全部任务；原生设备测试：网络/iframe/srcdoc、POST、HTTP 回归、Cookie/账号/缓存隔离、取消、挑战目标恢复。
- [x] Chrome、参考 MD3、原生和旧浏览器中性环境对照。
- [x] 我们 App 实际外层 CF、登录和 `/antibot` → 原搜索页面；Cookie 无跨 App 复制。
- [x] hlib 生产解释器完整搜索、63 章目录、首章多页内容；记录重启后的验证复发并完成同账号验证、重试读取。
- [ ] 验证 UI：前台恢复、后台提示、取消、多个来源排队、来源移除/换版本。
- [x] 来源请求调度：落实 concurrentRate，取消等待不补发；浏览器执行窗口内准入、重试重新计数。设备/实站验收见 [请求节奏](REQUEST_PACING.md)。
- [ ] 新 provider/API 24 与 35 平台验收；真实 Android 设备对照；资源和长期验证频率量化。API 35 CI 已接入原生测试与 provider 探测；本地 MuMu 结果不能冒充这两个 API 的通过报告。

本目录 [hlib-native.json](hlib-native.json) 为显式启用原生路径的候选源，含原来的榜单/文章入口与分类规则。首页/标签 UI 映射不在 #184 中，不能从字段存在推断 UI 已完成。真实源测试必须显式传 `liveHlib=true`；普通 CI 不访问该网站。

```powershell
node source-compatibility/browser/probe-server.cjs
adb -s 127.0.0.1:16416 reverse tcp:18766 tcp:18766
adb -s 127.0.0.1:16416 shell am instrument -w -r -e class indi.dmzz_yyhyy.lightnovelreader.sourceexecution.NativeBrowserInstrumentedTest indi.dmzz_yyhyy.lightnovelreader.debug.test/androidx.test.runner.AndroidJUnitRunner
```

手动实站步骤用同一个测试类 `HlibBrowserLiveInstrumentedTest`，先 `hlibAction=install`，再 `login`，完成页面操作后点“返回阅读器”，最后 `read`。遇到 BrowserRequired 再运行 login 应打开原始待验证目标，不重建账号。

## 资料

- [Cloudflare 支持的浏览器](https://developers.cloudflare.com/cloudflare-challenges/reference/supported-browsers/)、[Turnstile 移动端/WebView 要求](https://developers.cloudflare.com/turnstile/get-started/mobile-implementation/)、[Challenge Passage](https://developers.cloudflare.com/cloudflare-challenges/challenge-types/challenge-pages/challenge-passage/)、[验证循环排查](https://developers.cloudflare.com/cloudflare-challenges/troubleshooting/challenge-solve-issues/)。
- [Android WebView 数据目录](https://developer.android.com/reference/android/webkit/WebView#setDataDirectorySuffix(java.lang.String))、[ProcessGlobalConfig](https://developer.android.com/reference/androidx/webkit/ProcessGlobalConfig)、[ProfileStore](https://developer.android.com/reference/androidx/webkit/ProfileStore)、[CookieManager](https://developer.android.com/reference/android/webkit/CookieManager)。
- [Chromix](https://github.com/xiaozhou26/Chromix)：`FINGERPRINT_STATUS.md`、`docs/fingerprint-acceptance.md`、`tools/fingerprint_transport_audit.py`、0147/0148 原生 GPU 身份补丁。

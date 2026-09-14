# 原生环境与会话回归

对应 #203。复用 `realm-probe.js` 和 `canvas-backend-probe.js`，在生产 AndroidSourceBrowser/SourceBroker 路径运行受控页面。测试不访问 hlib，不复制用户 Cookie，不改变浏览器 getter、像素或 TLS 校验。

## 实验与断言

`NativeBrowserEnvironmentInstrumentedTest` 使用 MockWebServer 的 localhost/127.0.0.1 两个不同 site。前后台各重复两次，每次依次设置 Cookie、访问顶层、访问包含跨站 iframe/fetch/Worker 的页面、访问受限路径，共16次顶层导航。

- 顶层请求包含 Strict/Lax/None/HttpOnly；跨站 iframe 和带 credentials 的 fetch 仅包含 SameSite=None; Secure 的 Cookie。
- HttpOnly 不出现在 document.cookie；SameSite=None 缺少 Secure 被拒绝；Path=/restricted 仅在匹配路径发送。
- 网络 UA 与默认 WebView UA、主页面/跨站 iframe/Worker UA 相符；稳定身份字段跨两种入口及重复请求一致。
- 主页面与 iframe 的 webdriver 为 false，Worker 不暴露该属性。这是不同 IDL 接口的正常差别，不要求所有 realm 的属性完全相同。
- 原生 fetch 和 Cookie 接口没有被宿主替换，无特权页面桥。Canvas 同一次采集的重复读回一致。
- 音频、字体、WebGL、OffscreenCanvas 的真实结果作为证据记录；不把不同 OS/GPU/前后台的完整哈希相等设为门禁。特别是现有 MuMu 前台默认 OffscreenCanvas 蓝通道与后台相差1的现象仍需定位，测试不伪造相等。

结果先记录再断言，包括实际 provider 包名/版本、OS、平台报告的 model/hardware、脚本 SHA-256 与观测。平台报告值不是物理硬件证明。结果写到测试 App 的外部文件目录 `native-environment.json`，CI 将其收集到 API35 的 instrumentation artifact；该文件仅含受控页面数据。

## 会话生命周期

`NativeBrowserInstrumentedTest` 的相关用例覆盖：

| 场景 | 契约 |
|---|---|
| 同账号连续任务 | 内存 session Cookie 可继续用于后续请求 |
| 后台挑战 → 前台验证 → 后台读取 | 验证设置的 HttpOnly session Cookie 回到后台仍有效 |
| 取消未完成请求，再读取同账号 | 停止旧进程；已完成任务保存的持久 Cookie/localStorage 保留，账号代次不改变 |
| 切到其他来源账号，再切回 | 账号之间隔离；原账号持久 Cookie/localStorage 保留 |
| 清除账号 | 新代次看不到旧 Cookie/localStorage，旧执行不向新代次写入 |

取消/切源可能结束 Chromium 进程；这些场景不承诺 session Cookie 永久存活，也不通过添加 Max-Age 来制造该保证。会话目录持久化与 session Cookie 的寿命是两回事。

## 运行与范围

API35 CI 默认执行环境与生命周期用例，API24 保留原有隔离执行测试范围。CI 使用镜像实际安装的 provider，报告版本后才进行新旧对照，不能仅凭 Android API 等级推断 Chromium 版本。

本地先构建、安装 debug APK 与配套 androidTest APK，再运行：

```text
adb -s 127.0.0.1:16416 shell am instrument -w -e class indi.dmzz_yyhyy.lightnovelreader.sourceexecution.NativeBrowserEnvironmentInstrumentedTest,indi.dmzz_yyhyy.lightnovelreader.sourceexecution.NativeBrowserInstrumentedTest indi.dmzz_yyhyy.lightnovelreader.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s 127.0.0.1:16416 pull /sdcard/Android/data/indi.dmzz_yyhyy.lightnovelreader.debug/files/native-environment.json
```

下载两次运行的 JSON 后，可生成对照摘要：

```text
python source-compatibility/browser/compare-environments.py mumu.json api35.json --output comparison.md
```

脚本检查 schema 与四份观测是否完整，展示 provider/OS/探针版本和 Cookie、接口、Canvas 实测差异。脚本不同会明确提示不能直接归因为环境；不同硬件的正常差异不作为失败。它也不以摘要代替完整 JSON 中的音频/字体/能力观测。

测试使用临时来源账号，结束时清理自己的账号。真实账号目录不被删除，但切换到测试账号会结束当前原生浏览器进程；应在用户没有进行登录/阅读操作时运行。不要并行运行 UIAutomator 或另一套 instrumentation。

回环 HTTP 是浏览器认可的可信来源。Secure Cookie 的回环例外不能证明普通公网 HTTP 接受 Secure Cookie；本次不覆盖真实 HTTPS 证书链、跨 scheme、CHIPS/Partitioned、时钟回退、真实 Android 设备或 Cloudflare 频率。第三方 Cookie 开关沿用来源生产配置，未声称覆盖其所有组合。

官方依据：[Android CookieManager](https://developer.android.com/reference/android/webkit/CookieManager)、[W3C NavigatorAutomationInformation](https://w3c.github.io/webdriver/#interface)、[Turnstile 移动集成](https://developers.cloudflare.com/turnstile/get-started/mobile-implementation/)。Chromix 借鉴点是实际后端、跨 realm 与可重复取证；不是桌面 persona 或伪造属性迁移。

## 跨环境复核与本轮收尾判断

2026-09-14：本轮 Cookie/指纹工作以 #203 / #206 的原生语义、账号生命周期和可重复取证为完成边界。完成默认 CI 与报告归档后可以收尾；现有证据没有提出新的宿主修复要求。早期研究文档中的完整指纹矩阵是候选测量方向，不应自动扩大为阅读器必须完成的工程范围。

| 对照 | MuMu 实例 1 | API35 CI |
|---|---|---|
| 系统 / provider | Android 12 / com.android.webview 110.0.5481.154.1 | Android 15 / com.google.android.webview 124.0.6367.219 |
| 平台报告 / WebGL 后端 | SM-S9260、Samsung / Adreno 640 | sdk_gphone64_x86_64、ranchu / Google SwiftShader |
| 探针 | 前后台各两次，共四份观测 | 同一探针、相同四份观测 |

两端探针 SHA-256 都是 `ee1c525b4582bcc4ae4e12c18bf716742eacb5a469c4c64588e7c0067f391820`。对照工具比较 78 项摘要，12 项差异均为三个 realm、两种模式的 UA 和 WebGL 标识；Cookie 发送名单、网络/页面 UA 一致性、原生接口与所测 Canvas 混色结果一致。这是多个环境变量同时改变的对照，不能只归因为 provider 升级，也不能把平台报告当成物理硬件证明。

API35 证据来自 [run 34861025081](https://github.com/Renakoni/hnovel/actions/runs/34861025081) 的 `execution-platform-api-35` artifact：JUnit XML 记录 57 项、0 失败、1 项显式环境采样跳过；新的 `crossSiteCookiesAndRealms` 正常执行并通过（24.557 秒），验证交接、取消、切源和账号隔离用例也通过。完整 JSON 可由其中 `testlog/test-results.log` 的五条 `INSTRUMENTATION_STATUS: nativeEnvironment=` 记录依序恢复：第一条是 metadata，后四条为样本。原始日志 SHA-256 为 `4ffbd0f8d2ed19539e91261d692467233e5ed64a5152082688025fcddc7291f8`。本地原始数据和对照摘要归档在 `ref/hlib-adaptation-20260913/`。

该次工作流整体失败，原因是 Gradle 在测试结束后卸载 App，连同外部文件目录一起清理，随后 `adb pull` 找不到 JSON；不是 Cookie 或环境断言失败。模拟器诊断日志记录了 15:28:46 的卸载，报告读取失败发生在 15:28:49。CI 脚本使用当前 AGP 支持的 `android.injected.androidTest.leaveApksInstalledAfterRun=true` 保留 App，待报告收集后由临时模拟器的销毁完成清理。测试失败与报告缺失仍使检查失败；修复是否交付以 PR 最新检查和实际 JSON artifact 为准。

两个环境都复现前台默认 OffscreenCanvas 蓝通道 101、HTML Canvas 和后台 OffscreenCanvas 为 100；受控页面启用 `willReadFrequently` 后为 100，每种配置的重复读取稳定。这排除了“只在 MuMu 上观察到”的描述，尚未证明具体 Skia/GPU 舍入根因或与 CF 的因果关系。保留原生像素、记录差异，符合 Chromix `docs/canvas-chain.md` 对真实后端和不同上下文的区分；没有依据向用户网页强加读回选项或要求所有表面哈希相等。

后续只有出现明确需求或可复现缺陷时再推进：

| 事项 | 继续工作的触发条件 |
|---|---|
| Cookie / 身份 / 生命周期 | 同账号状态意外丢失、串账号、失效任务回写，或网络/页面/Worker 身份出现宿主造成的不一致；先建立复现，再修复对应职责 |
| HTTPS、跨 scheme、CHIPS、第三方 Cookie 关闭 | 新来源实际依赖这些语义，或相关 provider 更新需要兼容验收；当前未把浏览器 Cookie 展平回交 HTTP |
| Canvas 完整导出、codec、字体、WebRTC、OOPIF、QUIC/TLS 恢复 | 出现具体产品能力要求或后端契约失败；已有稳定的一阶色差本身不证明宿主缺陷 |
| 真机 / 当前新版 WebView | 按实际支持设备做发布兼容验收；这两套模拟环境不替代真机，也不称为最新版验收 |
| CF 频率 | 正常用户读取出现不可恢复或异常高频挑战时，按同账号、网络、provider 记录请求与各类验证次数；目前不宣称频率降低或零挑战 |

页面 readiness/加载性能是另一项产品目标；#173 的 Aitu 实站与严格逐请求权限目标也仍独立存在，不由本次对照关闭。判断依据包括 [原生方案及站方历史说明](README.md)、[稳定性实测](STABILITY.md)、[Chromix 映射与职责](IMPLEMENTATION_PLAN.md)，以及本地统一入口下的深度分析、四 PR 审查和环境实施记录。Cloudflare 官方要求稳定 UA 与正常 Web API，同时对旧版、嵌入式和模拟环境保留兼容限制；这些资料支持保留原生行为，不支持把更多指纹改写作为默认下一步。

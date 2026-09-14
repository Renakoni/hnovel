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

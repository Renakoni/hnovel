# 原生前后台稳定性：2026-09-14 实测

对应 #185 的验证窗口切换验收。原生路径已恢复正常 Chromium 网络、iframe/Worker、Cookie 与真实布局；本轮继续检查从后台读取转到前台验证时，身份和实际后端是否产生异常变化。结果不等于“去自动化全部完成”，也不是 CF 触发频率试验。

## 方法和证据

- [realm-probe.js](realm-probe.js)：实际 Canvas/WebGL 绘制、OfflineAudioContext 输出、字体度量、UA/locale、Worker/frame 与媒体能力。每个可用 realm 内重复三次音频和字体读取。
- [run-stability.cjs](run-stability.cjs)：MuMu 实例 1，前台和后台各三次独立 instrumentation 启动；每次用新中性 profile，运行完只清理该 profile。保留失败日志，不接触 hlib 账号、页面或验证码。
- [stability-results.json](stability-results.json)：六个样本、APK/probe SHA256、provider、时间以及检查结果。为避免重复，完整后台首样本为 baseline，其余样本保存相对它的 JSON Patch；原始采集文件 SHA256 一并记录。
- 参考 MD3 `fb01a76` 在同设备执行同一个中性页面，完成环境采样后禁用临时来源。其书源调试器随后尝试目录解析得到 TocEmptyException；这不影响已返回的中性环境样本，也不计为书籍全流程通过。

设备为 Android 12，`com.android.webview 110.0.5481.154.1`。测试使用 Debug APK；没有桌面 Chrome 调试连接、UA 覆盖、Canvas 噪声、代理切换或媒体授权。首次编排混用了 #188 的测试 APK 与 #192 的运行 APK，因 configureSource 签名差异在探针前失败；安装同一次 #192 构建的配套 APK 后六次均完成。失败未被计为环境不稳定或通过。

## 观察与解释

| 项目 | 本次结果 | 能说明什么 |
|---|---|---|
| 主页面 UA/platform/语言/时区/原生 fetch/webdriver | 六次相同，且与参考 MD3 相同 | 未发现本次前后台切换导致这些身份字段漂移；webdriver=false 不是不可检测证明 |
| 音频 | 44,100 Hz、4,096 样本，三次输出哈希均为 `ecd44425`；复制读取一致，样本有限且非静音 | 主页面和同源/跨源 frame 的 OfflineAudio 后端稳定；Worker 无此 API，未伪造支持 |
| 字体 | 五种 CSS family 请求的宽度与边界重复稳定，与 MD3 一致 | 证明这些文本度量稳定；指定 family 可能回退，不能据此证明字体文件已安装 |
| Canvas 主页面/frame | 六次均为 `a0b1f035`，透明边界正确，重复读取相同 | 主页面及 frame 的固定几何输出稳定 |
| Canvas dedicated Worker | 后台三次 `a0b1f035`，前台三次 `fcd58335` | 稳定复现前台 Worker 混色蓝通道 101、主页面 100 的差异；仍未定位 GPU/Skia 的具体舍入路径，不强行改成同一哈希 |
| WebGL | 六次及 MD3 均可绘制，readPixels `[64,127,191,255]`，error 0 | 后端可执行且本次结果稳定；Adreno 字符串不证明模拟器拥有物理 GPU |
| 媒体能力 | canPlayType 和 API 可用性六次稳定，与 MD3 一致 | 只完成能力探测，未完成实际 codec 解码、WebRTC/STUN/TURN 或设备媒体测试 |
| 页面几何 | 后台 1098×618，前台 1098×546；同模式各三次稳定 | 真实工具栏和状态栏导致正常高度差；不覆盖 viewport getter |

本轮没有发现需要新增身份伪造的证据。Cookie 的账号归属、持久 Cookie 与 localStorage 跨账号进程切换，以及 session Cookie 同进程延续，继续由原生账号设备测试约束；上述六次新 profile 采样不代替 Cookie 持久性测试。

## Chromix 与官方资料如何影响决策

Chromix `1222eec` 的 `docs/fingerprint-acceptance.md` 将身份、真实绘制、运行时和传输分开验收，明确 stock Chrome 对照不等于匹配构建验收，缺少物理硬件和外部线路时 `full_acceptance=false`。本项目借鉴这一证据分层和跨 realm／重启比较方法。

它的 `tools/tests/test_fingerprint_media_audio.py` 还约束旧的危险 renderer 覆盖已退役，要求 AudioBuffer 读取/复制、静音和非有限值等保持后端契约。因此不能把“采用 Chromix 思想”解释成照搬桌面随机设备模板或给 Android getter 填假值。

- [Cloudflare 支持的浏览器](https://developers.cloudflare.com/cloudflare-challenges/reference/supported-browsers/)将长期未更新、嵌入式浏览器列为有限支持，并说明自动化框架不受生产挑战支持。WebView 110 是当前必须单列的环境变量；伪装新版 UA 无法升级实际内核。
- [Turnstile 移动端说明](https://developers.cloudflare.com/turnstile/get-started/mobile-implementation/)要求正常 JavaScript、所需网络、Cookie 与 localStorage。当前原生方案符合该方向；此说明不保证所有站点的 Challenge Page 都支持当前 provider。
- [Android WebSettings](https://developer.android.com/reference/android/webkit/WebSettings)与 [CookieManager](https://developer.android.com/reference/android/webkit/CookieManager)提供真实设置和存储语义，不提供任意 Blink/GPU/TLS 身份后端。调试协议仅用于中性诊断，Release 不主动开启 WebView 调试。

## 下一批可验证工作

1. 在独立测试设备上使用较新 provider，再用真机重复同一探针和账号切换测试；记录实际包版本，不能在用户正在使用的 MuMu 中直接替换 provider 并丢失对照条件。
2. 补充 Worker Canvas 绘制/导出/像素路径对照，确认差异是否来自软件与硬件后端；保持真实像素契约。
3. 扩展实际 codec、字体文件/回退、WebRTC/STUN/TURN、OOPIF 和外部 QUIC/TLS 恢复验收。当前回环 TLS/H2 结果仅证明所测完整握手。
4. 记录同网络、同账号、同 provider、同请求节奏的正常阅读会话：请求数、CF 次数、站内验证次数、登录次数、恢复成功数和等待时间分开计数。至少覆盖跨日与冷启动；出现挑战由用户完成，不反复清 Cookie 人为触发。未获得这些数据前，不发布“CF 频率已下降”的结论。

复现时先安装同一次构建的 App 和 androidTest APK，启动 `probe-server.cjs 18767` 并映射设备 18767/18768 回环端口，再运行 `node run-stability.cjs ADB 127.0.0.1:16416 APP_APK OUTPUT_JSON`。记录参考 App 样本使用 `reference-consistency.cjs`，需要其本地调试服务 18122/18123。不得将这些中性探针直接注入用户网站。

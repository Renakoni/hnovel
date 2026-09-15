# 登录事实与网站验证（#217）

设置页显示已保存的事实和当前可执行的动作，不探测站点、不根据 Cookie 存在或任意 HTTP 200 宣称登录有效。

## 状态和动作

| 依据 | 显示 | 操作 |
| --- | --- | --- |
| `login()` 脚本正常结束 | 登录已提交，可选账户名 | 退出登录、次要的重新登录 |
| 网页流程或已有网站验证完成 | 会话已保存 | 退出登录；有登录声明时可重新登录 |
| 显式登录请求收到 401 | 需要重新登录 | 重新登录 |
| 尚无完成记录 | 未登录 | 有登录声明时提供登录 |
| 已存状态读取失败 | 无法读取账户状态 | 重试 |
| 当前账号存在 `Login` 验证票据 | 需要登录 | 继续登录 |
| 当前账号存在 `Cloudflare` / `SiteVerification` 票据 | 需要网站验证 | 打开验证 |

验证提示优先显示，已保存的账户名和会话仍在。完成、失败或取消验证后重新读取当前存储。没有登录声明也可使用已存在的网站验证票据；完成后保留“会话已保存”和退出入口，不构造空登录表单。

界面只保留短状态、可选账户名和动作。具体失败按发生原因提示：域名解析、连接、站点授权、地址限制和路线不可用均不等于登录失效。无可恢复票据的浏览器失败提示“网站验证未完成，请从书源重试”，不会构造新的验证地址。

## 责任边界

- `SourceLoginService.savedStatus` 将历史 `authenticated` 值映射到 `LoginSubmitted`。持久化格式不变，旧版本保存的数据仍可读；内部命名不再暗示已经获得通用的服务端认证结论。
- `RuleSource` 仍拥有登录规则、原请求和存储写入。网站验证成功只写 `session`；连接失败、非成功 HTTP 响应、取消和已撤销的执行身份不能写入这个结果。
- `SourceVerificationCoordinator` 继续串行执行原有验证票据，并按来源、修订、账号代际检查所有者。设置页只观察票据元数据，通过 id 调用已有恢复动作，不调用 `login.begin()`、不轮换账号、不另建恢复状态机。
- `SourcesViewModel` 对照当前注册信息过滤票据。前台请求继续自动验证并最多重试一次；后台请求只留下提示，用户打开验证后需重新发起原后台任务。
- `WebRequestErrorKind.VerificationRequired` 追加到既有枚举，书籍请求与后台任务不再把普通网站验证归为 `AuthenticationRequired`。下载项保存错误分类，失败原因分别为 `verification_required` 和 `authentication_required`。
- `SourceVerificationHost` 按票据种类选择继续登录或打开验证；具体连接/权限错误复用设置动作的错误映射。

原生浏览器现有挑战检测器和 `loginCheckJs` 保持原有边界，本项没有新增页面猜测、定时登录检查、Cookie 导入或通用身份探测。网络路线和原生网页数据保留分别由 #219、#218 处理。

## 参考与验证

对照本地 `legado-with-MD3` 的 `SourceLoginViewModel.confirm/saveCookie`：参考项目也以脚本完成和保存 Cookie 结束界面流程，没有跨站通用的认证有效性协议。本项目复用其登录规则兼容语义，使用自身已有的账号撤销和验证票据边界。

回归覆盖脚本提交的短状态、网页会话、无登录声明的验证与退出入口、后台分类、连接失败、取消，以及过期来源/修订/账号拒绝。受控 fixture 使用合成账号与页面，不依赖真实网站或用户账号。

2026-09-15 验证：`:app:testDebugUnitTest` 485 项、`:source-content:test` 120 项全部通过，0 失败、错误、跳过；debug 与 androidTest APK 构建通过。API 35 / WebView 124 的 `SourceVerificationInstrumentedTest` 通过，覆盖 Activity 重建、真实验证窗口和原搜索的一次恢复。设备测试没有使用真实账号，也不属于 VPN 物理出口验证。

相关入口：[基础设置](SOURCE_BASIC_SETTINGS.md)、[原生浏览器边界](browser/README.md)、[Issue #217](https://github.com/Renakoni/hnovel/issues/217)。

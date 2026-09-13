# 复杂番茄样本的执行边界与依赖诊断

对应 #134，复核时间为 2026-09-13 UTC。原文件 SHA-256 为
`14dce10e4d799afdd910ee67ea14cb90491660d45be7e8c1e136877540c980f6`，319,879 bytes，
共享库 218,968 字符。本报告只公布结构、固定依赖名及聚合结果；原文件、认证值、私有接口、脚本和小说内容均未入库。

**当前结果：原文件可以导入，但尚不能完成搜索、分类和登录。** 本次补齐只读来源前导脚本以及具体依赖诊断，未将原文件宣称为可用来源。

## 固定依据

实际获取并核对 [Luoyacheng/legado-E@8b87c5a](https://github.com/Luoyacheng/legado-E/tree/8b87c5aba4df91c39a3a0939a68a1180b9f2ee1c) 的文件：

- `BaseSource.kt` / `NativeBaseSource.kt`：来源变量、登录信息与属性读取。
- `SharedJsScope.kt` / Rhino bindings：共享库初始化时没有本次调用的宿主对象。
- `JsExtensions.kt` / `JsEncodeUtils.kt`：脚本工具、请求及 Android 能力。
- `CookieStore.kt` / `CacheManager.kt`：域名 Cookie 与通用对象缓存。
- `BaseBook.kt` / `BookChapter.kt`：书籍自定义变量与章节图片记录写入。

文件存有本地 SHA-256 对照。固定 fork 的实现是兼容性依据，不表示本应用应复制它的 Android 对象、全局数据库和任意 Java 互操作。

## 实际分阶段结果

| 输入与执行层 | 登录表单 | 分类目录 | 搜索 |
| --- | --- | --- | --- |
| 原样本，修改前生产 JVM 流水线 | InvalidRule / ScriptRuntime | 同左 | 同左 |
| 原样本，修改后生产 JVM 流水线 | UnsupportedDependency / jsLib / JavaImporter | 同左 | 同左 |
| 原样本，Android API 35 隔离进程及 Binder | UnsupportedDependency / jsLib / JavaImporter | 同左 | 同左 |

原样本三入口都在第一次宿主桥接调用之前失败，不能把未触达的网络、目录或正文阶段判为成功。Android 验证从设备上的临时私有文件读取原始 bytes 并核对完整哈希；没有用裁剪样本替代原文件。另验证依赖失败后下一次正常调用可以恢复。

为了定位初始化之后的差异，另做了明确标记的本地控制实验：

1. 仅删除已定位的 Java 导入/工具初始化块，并将设备字段置为空研究值。库初始化可以完成；新账号首先在 `source.putLoginInfo(object)` 失败。固定 fork 的公开签名为 `putLoginInfo(String)`；宿主没有应当接受任意对象的依据。
2. 临时账号预置空 JSON 对象后，三个入口继续至裸域名形式的 `cookie.getKey`，因当前 Cookie 接口要求完整 HTTP(S) URL 而失败。fork 的 CookieStore 使用域名归一化和共享数据库，不能直接等同于当前来源/账号隔离的 Cookie 模型。
3. 仅在研究副本中显式 JSON 序列化登录信息、把裸域名 Cookie 参数改为 HTTPS URL。登录脚本计算完成，但表单校验在 `loginUi[16].default` 拒绝；分类和搜索仍在后续脚本/桥接参数阶段失败。该实验禁止非存储桥接请求，实际未触发网络；未伪造请求、签名或正文返回值。

上述控制实验用于排查后续阻碍，**不是兼容规则的交付或原样本验收**。它们没有修改用户原文件，研究副本不随应用发布。详情、目录、正文、图片及账号登录成功仍未验收。

## 能力映射与剩余工作

| 样本依赖 | 当前判断与下一步 |
| --- | --- |
| `source.loginUrl` / `getLoginUrl()` | 本次补齐。作为只读的本次调用数据传入隔离脚本；来源可显式 `eval(String(source.loginUrl))`，读取属性本身不会启动登录。 |
| JavaImporter / Packages / Android Build | 不开放任意互操作。来源作者应把纯工具改为纯 JS/已有受限工具，并明确设备字段的协议要求；不能用虚构设备参数冒充真实注册。 |
| Hutool MD5、Base64、反转 | 已有受限 MD5/Base64 工具及 JS 字符串运算可作为改写基础，需逐项核对 bytes/编码语义。没有自动重写原库或伪造同名 Java 类。 |
| GZIP、解压及二进制 POST | 原库将压缩字节用于请求；当前 broker 的文本请求体契约不能直接代替。需要单独提供有界二进制工具/请求设计、实际协议和成功对照。 |
| OkHttpClient / Request.Builder | 应改写为来源绑定的请求接口，保留域名授权、Cookie、响应大小、超时及取消；不得通过 Java 网络对象绕过 broker。 |
| 来源配置、登录和发现动作 | 配置、有限发现动作及表单协议已有实现。原样本仍有对象/文本、Cookie 参数和具体表单字段差异，须提供与固定 fork 一致的最小样本逐项处理。 |
| `book.putCustomVariable` / `chapter.putImgUrl` | 前者已有快照数据语义；后者在 fork 中直接写章节数据库，不能当作一个无副作用字符串工具补入。需明确宿主章节身份及图片写入生命周期。 |
| 签名辅助服务、注册、账号、配额 | 当前只验证 DNS 与根路径连通性；没有成功登录/签名业务响应，不能承诺受保护请求或正文可用。需要来源作者给出无密钥协议说明与测试账号/成功对照。 |

建议先由来源作者给出移除 Java 互操作后的最小初始化/登录/请求样本，再按照登录表单 → 分类/搜索 → 详情/目录/正文 → 图片逐阶段推进。公开 issue 只放脱敏结构与阶段结果。#134 保持开放，避免“修复第一个报错”等同于整个复杂来源可用。

## 最新外部连通性

对原样本提取到的 15 个域名进行了 Google / Cloudflare 两次独立公共 DNS 查询，均返回成功状态和公网 A 地址。另核对编码字符串，没有发现额外的编码域名。**早期审查的一次 NXDOMAIN 不是本次结果，不能继续作为固定失效结论。**

使用本次公共 DNS 地址，保持原 HTTPS 主机名/SNI 与证书验证，直接发起无凭据的根路径 HEAD 请求，不跟随重定向、不提交设备注册或账号信息：

| 结果 | 域名数 |
| --- | ---: |
| HTTP 200 | 6 |
| HTTP 404 | 6 |
| HTTP 400 | 1 |
| 连接/握手阶段超时（curl 28） | 2 |

签名辅助服务属于返回根路径 404 的一项。HTTP 响应只证明当时此路由可以到达服务器，404 既不能证明业务接口失效，也不能证明签名业务可用。直连公共 DNS 对照不代表用户的 VPN/代理或 Android 默认 DNS 结果；应用网络策略未更改。

## 诊断设计及验证

引擎仅将未捕获的、由 Rhino 产生的固定 Java 绑定缺失 `ReferenceError` 分类为 `UnsupportedDependency`。枚举限于 JavaImporter、Packages、importClass、importPackage、JavaAdapter；不输出原异常、未知变量名或脚本。`typeof` 探测、被来源捕获的 fallback、普通 JS 错误继续保留原语义。

依赖名从 Rhino → worker 结果 → wire/Binder → 内容错误 → 来源诊断报告传递，库错误归属 `jsLib`。诊断页展示 profile、字段、能力和改写方向；来源动作显示专用提示。预览仍然只检查定义结构，不执行第三方脚本。新增结果字段可缺省，旧定义、身份和存储格式无需迁移。

验证覆盖纯脚本、真实 JVM 子进程、内容请求、宿主诊断导出和 Android Binder；合成测试不包含原样本内容。源模块完整测试 232 项通过，应用完整 JVM 测试 395 项通过；后续新增诊断报告回归后，诊断测试 2 项通过。Android API 35 的 2 项测试通过，含原始私有文件三入口检查；Debug 与 instrumentation APK 构建通过。

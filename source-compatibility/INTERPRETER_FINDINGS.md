# Legado 小说解释器对照记录

本轮使用原合集定义，对照 MuMu 中的阅读 3.26.15，以及本地
`legado-with-MD3` 和 `legado-hectorqin` 源码。范围是导入的小说书源；内置
Wenku8 继续使用 `Wenku8Api` / `Wenku8Discovery`，不改成 Legado 规则执行。

## 已移植与重新实现的边界

| 层 | 当前实现 | 兼容性含义 |
| --- | --- | --- |
| Jsoup / JSONPath / XPath 选择器 | 来自固定版 Legado，见 `source-rules/THIRD_PARTY.md` | 已有移植基础，不需要逐站重写选择算法 |
| 规则切分、组合、模板、替换、返回值转换 | `RuleParser` / `RuleEvaluator` 重新实现 | 必须比较完整规则的执行顺序，不能只检查单个选择器 |
| JavaScript | Rhino 引擎，`ScriptRuleHelpers` 等适配接口 | 引擎相同不代表 `java.*`、对象返回值、状态生命周期相同 |
| 请求规则 | `ScriptRequestTemplates` / `RequestCompiler` | URL、编码、请求选项、动态请求头必须共同保留 |
| 目录与正文 | `RuleSource` / `ContentMarkup` | 翻页调度、去重和正文后处理也会改变规则的最终结果 |
| 内置 Wenku8 | 现有原生插件 | 独立保留，不属于上述解释器替换范围 |

`source-compatibility/reference/` 固定了五个底层解析文件，没有包含完整
`AnalyzeRule`。`ReferenceRunner` 的脚本案例使用合成宿主接口。因此，现有选择器
测试和产品契约测试通过，不能当作完整解释器与阅读等价的证据。

## 本轮定位的通用缺陷

| 触发条件 | 阅读的行为 | 我们此前的行为 |
| --- | --- | --- |
| `</js>` 后有换行，或脚本前/之间只有空白 | 忽略空白规则片段 | 空选择器清空脚本结果 |
| 首次 `nextTocUrl` 返回完整页列表，包含当前页 | 去掉当前页，列表只展开一次 | 重复访问当前页，或在后续页递归展开同一列表 |
| 章节标题含空格，被模板拼进查询参数 | 保留逻辑 URL，到请求阶段编码 | `URI.resolve` 提前拒绝整个目录 |
| 纯文本正文含换行，同时要归一化图片 URL | 原正文保持分段 | 中间 Jsoup prettyPrint 把换行折成空格 |
| HTML 选择后再执行 JS | JS 观察选择器的原始输出，最终标量再解实体 | 在 JS 前额外解实体，最后一个 JS 的输出反而没有解实体 |
| 最后一个 JS 返回带换行的字符串，调用方要字符串列表 | 按换行变成多个条目 | 把整串当一个条目，URL 列表也受影响 |

对合集 972 条小说定义的 18,272 个非空规则字段扫描，六条定义的十个字段包含
`</js>` 后的尾随空白。这只是该语法形态的数量，不表示这六条以外没有兼容问题，
也不表示这些来源的网络和所有章节已经验证成功。

宜搜原规则依次暴露前三项：此前目录只有一章；修复脚本空白后，完整目录列表
开始执行；接着第 161 章标题中的空格暴露 URL 问题。修复目标是这些通用语义，
没有修改宜搜原定义，也没有加入站点特判。

## 完整解释器的实际对照方法

阅读的本地 Web 服务只用于调用参考应用并取回结果；本 App 的正常阅读不依赖它。
本轮通过本地合成 HTTP 页面和原应用的 `java.getString` / `java.getStringList`，
执行固定输入规则，再把同一输入规则交给产品 worker。以下是稳定的参考输出：

| 输入与规则 | 参考输出 | 产品修复前 |
| --- | --- | --- |
| `@js:'&amp;'`，取标量 | `&` | `&amp;` |
| `<h1>&amp;amp;</h1>`，`h1@text` 后的 JS 检查是否为 `&amp;` | 是 | 已提前变成 `&` |
| `@js:'one\ntwo'`，取列表 | 两个条目 | 一个含换行的条目 |
| `<js>'chapter'</js>` 后接 CRLF | `chapter` | 修复空白后已一致 |
| 脚本前空白，读取输入长度 | 原输入长度 | 修复空白后已一致 |

还观察到一个不适合作为固定期望的结果：参考应用把 JS 数组作为标量返回时，
出现 `org.mozilla.javascript.NativeArray@...` 对象标识字符串；产品会连接数组内容。
该输出包含运行时对象标识，不能把一次观测硬编码为兼容规则。需要实际依赖此
行为的书源证据后，再确定有意义的返回值约定。

新增回归覆盖上述稳定的执行语义，以及目录完整页列表、空尾页、带空格的章节
URL、浏览器请求选项和正文段落。后续继续扩大固定输入的完整规则对照，优先检查
模板作用域、嵌套 `java.getString` / `java.setContent`、请求编码、变量写回、空结果
和异常分支。网络超时、域名拒绝与规则输出差异分别记录，不合并成“规则不兼容”。

## 修复后的验证

七个 source 模块的 381 项仓库测试通过，另运行一项本地原应用对照探针；Wenku8
发现和内置正文渲染的 11 项回归通过。Debug APK 与测试 APK 构建成功。

MuMu 使用未经修改的合集定义，经导入器、产品规则执行器、网络 broker 和 Android
隔离 worker 跑完整流程，得到：

| 来源 | 搜索条目 | 目录章数 | 首章文本段数 | 首章字符数 |
| --- | ---: | ---: | ---: | ---: |
| QQ 浏览器 | 20 | 660 | 90 | 2,984 |
| 神魔 / 宜搜 | 20 | 431 | 98 | 3,128 |

宜搜目录章数与参考阅读一致；QQ 正文已在实际阅读器看到逐段显示。这里的字符数
不含图片，不代表逐字与原页面一致；每个来源只验了首章，不能推出全部来源、全部
章节可读。五个稳定的合成解释器案例修复后与原应用输出一致。

本轮还补齐浏览器 UA 和默认等待：来源 UA 同时用于页面与重写的 fetch；页面完成
后先等待 1 秒再取正文，与参考默认行为相同。新增两项设备回归通过。此前完整
浏览器测试为 9 项中 8 项通过，图片验证码测试因 UiAutomation 定位输入框超时失败，
尚未证明这个失败由本轮修改引起或此前就存在。未重复触发前台验证码测试，也不把
这组测试报告成全部通过。

## 浏览器研究暂存

同一 MuMu、相同 WebView UA、同一个本地页面：普通字符串 fetch 和 HttpOnly Cookie
在两条路径均工作；当前受限浏览器的 `fetch(new Request(...))` 被包装层拒绝，iframe
被 CSP 阻止；原生 WebView 的 POST 与 iframe 均成功。这证明存在真实兼容差异，
但该本地页面没有 CF，不能作为 CF 验证成功的证据。

Chromix 的指纹设置主要依赖桌面 Chromium 内核补丁，不能直接变成 Android WebView
启动参数。它也没有实现可配置的 TLS/HTTP persona。可借鉴的是浏览器环境一致性，
不是复制几个 JS getter。恢复原生浏览器还需要处理来源会话和网络边界，本轮优先
完成解释器修复，不用整体切换浏览器掩盖解析错误。

CF 应参考官方的 [挑战响应识别](https://developers.cloudflare.com/cloudflare-challenges/challenge-types/challenge-pages/detect-response/)、
[浏览器支持](https://developers.cloudflare.com/cloudflare-challenges/reference/supported-browsers/)
和 [clearance](https://developers.cloudflare.com/cloudflare-challenges/concepts/clearance/)：
`cf-mitigated: challenge` 是明确标识，单独出现 403 或验证码不能证明是 CF。
`cf_clearance` 与访问者和设备相关，Turnstile 一次性 token 也不等于 clearance Cookie。
跨设备、跨浏览器或切换出口后不能保证复用验证。此前反复出现的“图片验证码”来自
本地 MockWebServer 测试，和这些网站验证不是同一件事。

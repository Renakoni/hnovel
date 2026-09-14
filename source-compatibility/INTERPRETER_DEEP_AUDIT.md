# 解释器源码深审与固定页面双向验证（2026-09-14）

本轮从参考源码确定执行语义，再构造相同书源和本地页面，分别运行产品与真实阅读 App。
基线 20 个定向场景中，18 个观察到差异、2 个对照一致。**这些场景专门针对已发现的差异构造，
不是随机抽样，更不是六份书源有 90% 失败。** 部分差异只是参考分支之间的不同语义。

本轮已修复其中 8 个场景。用修改后的正式产品链路重放原输入，对照固定的参考 App 结果，
现在 10/20 一致（8 个修复加 2 个原有对照）。剩余 9 项兼容差异与 1 项参考版本分歧
均在下表列明；没有把“解释执行成功但输出错误”计为一致。

## 基线和复验方法

| 对象 | 固定版本 |
| --- | --- |
| 产品基线 | `a42688e397135fe604faea2d1498fe0aef683894` |
| hectorqin/legado | `da17bb2bed44f30b12a524c2457e32a20b16fa41` |
| Legado with MD3 | `fb01a76ebbbca41423e2c4c00080cc0861239fbd` |
| 实际参考 App | 阅读 3.26.15，Android 12 |

随后同步 main `3478a49d`（#187/#188/#190），合并提交为 `c44d3c6e`。
再次同步 #192 后为 `4ea4fc50`；#193 已 squash 合入 main `b13e113f`，两者文件树一致。
本轮修复从 `b13e113f` 新建分支提交，最终 JVM 检查及 APK 构建已包含 #192。
下面的差异表保留 a42688e3 的实测基线；已由最新 main 补齐的网络能力在后文单独更正，
不重复算作本轮新增实现。

产品路径为正式导入器 → `RuleSource` → `WorkerRuntime` → `SourceBroker`，页面由
`MockWebServer` 提供。参考 App 经其 Web 服务导入同一组临时定义、启动书源调试；
最后一个替换表达式仅观察处理后的正文与章节名，并原样返回。此 Web 服务是调试和管理入口，
规则实际仍由 App 的解释器执行，并非另外一个云端解析服务。

URL 比较只把本地服务器的主机和端口统一为 `ROOT`；正文按非空段落比较，保留文本内部空白，
章节标题单独比较。该表示法不验证阅读器显示的缩进和空段行距。抓取列表也记录下来，
用于识别重复翻页。临时书源在 `finally` 中恢复或删除；验证仅请求本地自有页面。

`deep-audit/` 保存共同输入、基线双方结果和版本信息。完整运行日志、参考调试消息和
六份文件的逐定义统计在本地 `E:/H-novel/ref/interpreter-deep-audit-20260914/`。
本地 classpath 指向可重新构建的工作区，不能把它当作不可变的基线二进制；基线结果单独保留。

首次探针修正过两个测试自身的问题：缓存表达式误用了 `try/finally` 的完成值；
参考 APK 的 `source.getHeaderMap()` 不支持省略 Kotlin 默认参数。最终缓存探针显式返回变量，
header 探针使用可调用的 `source.getHeader()`。初次探针异常未计入最终差异。

## 逐项结论

参考路径以下均相对 `app/src/main/java/io/legado/app/`；产品路径相对仓库根目录。

| 固定场景 | 基线产品与参考的差异 | 源码依据与处理 |
| --- | --- | --- |
| `replace_lines` | 产品保留 `DROP advertisement`；参考删除该段 | `model/webBook/BookContent.kt` 先逐页 `formatKeepImg`、实体解码，再合并、逐行 trim、全文替换。产品 `RuleSource.content` 原来把 HTML 直接传入 replaceRegex。本轮对齐顺序，复用已移植的 HtmlFormatter |
| `content_page_list` | 参考返回三页正文；产品因重复入队报 `RepeatedPage` | `BookContent` 首次返回多个 URL 时展开一次，后续页不再解释 nextContentUrl。本轮移植这一分支，并保持输入顺序 |
| `content_self_link` | 参考结束读取；产品因末页指向自身报错 | `BookContent` 单链接循环遇到已经访问的 URL 时结束。本轮对齐，仍保留页数、字数、时间及重定向异常限制 |
| `title_failure` | 参考保留原章节名并读正文；产品中断 | `BookContent` 将 title 视为可选字段。本轮只容忍普通规则错误；撤销、超时、依赖缺失、登录及授权错误继续上报 |
| `format_failure` | 参考保留章节标题；产品目录失败 | `BookChapterList` 每个标题的 formatJs 有错误回退。本轮补齐普通规则错误回退；失败脚本的部分写入仍见 `optional_state` |
| `boolean_flags` | `null`、` false `、`NO` 在产品都成为真，章节被当作卷；参考均为假 | `utils/StringExtensions.isTrue` 特判空白和精确的 `null`，对 trim 后的 false/no/not/0 忽略大小写。本轮对齐 |
| `chapter_tag` | 参考 `tag=chapter-token`；产品 `tag=null` | `BookChapterList` 将 toc.updateTime 写入 `BookChapter.tag`。产品只写了 updateTime，本轮同时补齐 tag |
| `redirect_base` | 参考脚本 baseUrl 是请求章节地址；产品是重定向地址 | `AnalyzeRule` 分开保存 baseUrl 与 redirectUrl；产品 `RuleSource.fetch` 覆盖了前者。需要分别传递脚本基址和相对链接解析基址，不能直接改一个变量而破坏跳转后链接 |
| `preupdate_refresh` | 参考首次普通目录读取成功；产品提前执行 preUpdateJs 并因缺少 refreshTocUrl 失败 | `WebBook.getChapterListAwait(runPerJs=false)` 默认不触发；`AnalyzeRule.refreshTocUrl/reGetBook` 只能在专用上下文执行真实重查。产品的触发时机与宿主动作都未闭环 |
| `cache_object` | 参考保存并读取 `[object Object]`；产品报 PermissionDenied | 参考 `CacheManager.put(Any)` 对普通对象调用 toString；产品 broker 要求 JsonPrimitive。普通缓存 TTL/持久化已修复，但类型转换未全覆盖；参数错误误映射为权限错误也需要修正 |
| `header_api` | 参考 getHeader 返回原 header；产品方法不存在 | `data/entities/BaseSource` 的实体 getter 未完整暴露到 `ScriptRealm` 来源门面 |
| `source_property` | 参考返回来源名和地址；产品成功返回 `undefined\|undefined` | `ScriptRealm` 仅传递部分来源字段，缺少 bookSourceName/bookSourceUrl 等。成功状态不足以判断兼容 |
| `chapter_url` | 参考 chapter.url 保留原始相对地址、baseUrl 为目录地址；产品 url 变绝对地址、baseUrl 为空 | 本轮在目录字段求值前提供 baseUrl/bookUrl，保留原始 url，按参考顺序设置 tag 后读取卷/VIP 标志。宿主 id 仍用于存储和请求，后续正文不再覆盖脚本 url |
| `title_after_content` | 阅读/MD3 得到正文脚本写出的新标题；产品与 hectorqin 保持旧标题 | MD3 在正文后读 title，hectorqin 在正文前读。这是版本差异，本轮保留固定 hectorqin 语义，不标作所有 Legado 版本都要求的修复 |
| `list_api` | 参考 `java.getStringList(...).size()/get(0)` 可用；产品抛错 | 参考返回 Java List；产品 `ScriptRuleHelpers` 转成 JS Array，仅补了 toArray。应为返回的列表补齐真实接口，不修改所有普通 JS 数组 |
| `native_object` | 同一对象既有 `x.y` 直接键又有嵌套 x.y，参考返回 Literal，产品返回 Nested | `AnalyzeRule.getString/getStringList` 对 Rhino NativeObject 有直接键分支。产品先转 JSON，丢失原生对象与 JSON 文本的区别 |
| `optional_state` | 可选字段写变量后抛错，参考后续仍读到 saved；产品写入丢失 | 参考持有活对象；产品 `RuleEvaluation.value` 只接收成功调用的状态。需区分容忍的规则异常、取消和强制超时，不能一律提交失败调用 |
| `regex_mode` | 参考冒号正则列表后，常量名称规则可用；产品搜索 0 项 | `AnalyzeRule.splitSourceRule` 保留 isRegex 模式，影响后续行字段。产品只有捕获组替换，没有模式延续。探针后续 single() 的异常只是零结果的表现 |

`control` 普通 HTML 阅读、`block_paragraphs` 的 p/hr/dl/dt/dd 段落样例，两边均一致。
不能仅根据标签集合不同就宣布对应段落功能缺失；Jsoup 的中间序列化也会影响换行。

## 六份书源中哪些值得优先处理

统计对象是此前 static-final 已接受的 **4,557 个不同定义**，共 6,298 原始行。
下面是字段存在或可执行字段中的词法提及数，不是失败数；脚本注释、死分支和别名没有语义分析。
同一定义可命中多项，六份文件中也存在重复行，不能相加当作受影响书源总数。

| 字段/接口 | 不同定义 | 原始行 | 含义 |
| --- | ---: | ---: | --- |
| content.replaceRegex | 979 | 1,372 | 按行清理与全文替换属于高优先级 |
| nextContentUrl | 1,126 | 1,562 | 分页遍历的通用修复优先于逐站补丁 |
| content.title | 16 | 20 | 可选标题不能阻止正常正文 |
| toc.formatJs | 0 | 0 | 固定探针证实差异，本批无字段使用 |
| preUpdateJs | 11 | 15 | 看书神、晋江、一个阅读等有使用 |
| refreshTocUrl/reGetBook | 9 | 12 | 需要真实宿主动作 |
| concurrentRate 非零 | 12 | 15 | 包含 1000/2000/3000 毫秒等配置 |
| 未补齐的数值/文件/内存缓存 API | 7 | 9 | 酷我相关版本等 |
| source 来源属性 | 50 | 70 | 番茄2、疯情书库、奇热、读文学等 |
| source header/getTag getters | 0 | 0 | 不能由探针推导本批必受影响 |
| chapter.url/baseUrl getter/属性 | 6 | 8 | 一个阅读、西瓜小说等 |
| toc.updateTime | 383 | 514 | 应正确映射 tag，但不代表 383 个都因此失败 |
| chapter.tag/getTag 直接提及 | 0 | 0 | 本批没有直接提及 |
| 冒号正则列表 | 29 | 36 | 后续行规则也必须继承正则模式 |
| MD3 fromBookInfo/subContent | 0 | 0 | 有参考扩展，目前优先级较低 |

## 同步 main 后的网络结论与架构差异

- 基线的 `concurrentRate` 缺失已由 main 的 #190 补齐。已检查
  `RuleSourceDefinition` → `configureSource` → `SourceRequestPacer` 的实际接线：HTTP 与
  原生顶层导航共享来源准入节奏，支持单次间隔和 N/毫秒固定窗口。子资源不重复计数，
  已有总超时仍包含限速等待。不是滑动窗口，也不能由限速通过推断 CF 不再出现。
  细节见 [REQUEST_PACING.md](browser/REQUEST_PACING.md)，本轮冲突解决保留其全部测试。
- 默认 UA 尚未对齐：参考 `BaseSource.getHeaderMap` 注入 AppConfig.userAgent；产品的正常
  来源绑定只传入已保存授权头和规则头，没有自动注入浏览器 UA。没有显式 UA 的直连请求使用
  OkHttp 默认值；显式规则 UA 已支持。浏览器的 navigator UA 与普通 HTTP 的默认 UA
  不是同一条路径。这是挑战验证的可能因素，不是已经证实的 CF 触发原因。
- 参考的文件/字节缓存、数值读取、保留对象身份的内存缓存，不能用现有字符串缓存冒充。
  产品按来源/档案隔离缓存是有意设计；参考全局共享不能原样照搬。
- 64 页、5,000 章和操作时间/输出预算可能截断有效大书。它们是明确限制，后续应按数据大小
  调整或分批处理，不能为兼容直接删除撤销、隔离与输出限制。
- `UrlOption.origin` 只是声明，参考对应路径也未发现使用，不能由字段存在直接判定产品缺失。
  jsLib 初始化时不提供本次调用变量与参考 SharedJsScope 一致；java.ajax 的数组首参数重载
  也已处理，均不列为本轮缺陷。
- main 的 #187/#188 已提供原生联网、iframe、来源账号 profile，以及前台验证完成后一次
  重试。`browserRead:true` 的 Document 请求可选择该路径；普通 java.ajax/connect 的 API
  请求仍走 HTTP，除非显式指定 webView。两条路径的 Cookie 没有通用双向同步，因此
  “浏览器验证过后所有 API 都自动通过”仍不成立。见 [浏览器说明](browser/README.md)。
  这与本轮本地页面揭示的解释器差异分开；不能继续把产品描述为只支持禁网重写 WebView。

参考直接持有书籍/章节/数据库对象，调用和状态传递简单；产品把规则、HTML 和脚本放在 worker，
主进程持有账号、网络授权和持久化，因此能撤销单次执行并限制资源。后者不天然更兼容，
代价是必须显式移植类型、上下文、错误后的状态和宿主 API。可移植的纯解释算法应按参考实现；
依赖 Android 活对象的接口应提供对应行为，不能填空函数或返回 undefined 后就宣称支持。

后续顺序明确：继续来源元数据、重定向上下文和正则模式延续，然后 Java List/NativeObject
返回契约与可选错误的部分状态，补齐刷新动作和缓存接口。每项都以同输入双方
结果验证；参考版本自身缺陷或版本分歧单独记录。原生 Wenku8 不经过本轮修改的 RuleSource。

## 验证与 CI

本轮新增规则回归用例先在产品基线运行，6 项失败均复现对应问题，原有段落对照通过。
随后增加章节元数据、隐藏元素不变成正文、连续变化 URL 仍受 64 页上限约束的回归。
隐藏 script/style/noscript 在格式化前移除，保留产品此前不显示隐藏节点内容的行为；
不为了复制参考的纯格式化器而把脚本源码展示为正文。

另外用参考 App 单独验证 `literal_entities`：输入 `&lt;b&gt;literal&lt;/b&gt; &amp;amp;`，
参考结果为文字 `<b>literal</b> &amp;`。参考 `TextChapterLayout` 只识别图片标记，
不会把已解码正文再次作为完整 HTML 解析。本轮为可信 worker 转换增加已格式化文本路径，
避免丢失字面标签或多解码一次实体；原 HTML 转换入口保留，用例覆盖 wire 输出限制。
这一追加检查单独保存，不修改原 20 个探针的基线。

PR #193 在 a42688e3 的 [CI run 34839438641](https://github.com/Renakoni/hnovel/actions/runs/34839438641)
首次只有 API 35 的 `rendersThroughBrokerAndKeepsSameDomainAccountsAndBrowserStorageSeparate`
失败，JVM 与 API 24 已成功。logcat 显示前两个浏览器进程完成退出后，下一次连接没有完成，
15 秒后返回 `Queue/Timeout`；整个测试约 19.84 秒，并未耗尽请求的 60 秒正文预算。
浏览器分支未更新 broker 的 stage，因此 Queue 字样不能证明卡在 HTTP 并发队列。

本地同一测试通过，原提交仅重跑失败作业后，API 35 也通过，整轮 CI 全绿。
证据支持偶发服务重绑定时序问题，尚不足以确定 Android 生命周期中的唯一竞态；
没有以延长超时、取消断言或关闭测试来掩盖失败。
合入 main 的唯一文本冲突是 RuleSourceTest 两边各新增测试，已同时保留，四个相关模块回归通过，
冲突修复单独推送为 c44d3c6e。新代码的远端检查应以新提交为准。

最终 JVM 检查 889 项通过，无失败或跳过：app 425、rules 25、network 51、import 27、
execution 78、compatibility 44、rhino 136、content 103。debug 与 AndroidTest APK 构建成功。
API 35 在 `c44d3c6e` 加本轮修复上完成 55 项设备检查：54 通过、1 项环境采样因未配置而跳过，
无失败。同步 #192/#193 并重新构建后，补测新增的公开登录链接识别、真实 Binder 导入阅读链路、
两项正文转换，共 4 项全部通过；上述 5 个本轮生产文件在两次设备验证间没有变化。
原 20 个固定页面也已在同步后的构建上重放，仍为 10/20 一致。
本轮没有重新对六份原始书源做全量联网验证，词法覆盖和定向探针不能替代该通过率。
共同输入、基线与修复后的结果见 `deep-audit/baseline-comparisons.json` 和
`deep-audit/after-comparisons.json`；版本文件还记录了复验所用生产文件的 Git blob，
避免把之后的工作区重新构建误认为本轮同一版本。

PR #198 的首轮 [CI run 34844650692](https://github.com/Renakoni/hnovel/actions/runs/34844650692)
在 `e39e4b8e` 上 API 24、API 35 均通过，JVM 的统计测试报 `UncaughtExceptionsBeforeTest`。
下载 JUnit XML 后确认实际异常来自前一个 `SourceIdentityRoomTest`：设置状态的
`AbstractSettingState.asState` 订阅仍在执行 `UserDataDao.getFlow` 查询，测试已关闭 SQLite
连接池。`ViewModelStore.clear()` 只发出取消，不保证 IO 子任务已经退出；晚到的未处理异常
被下一个 `runTest` 收集，因而不能从报红测试名推断是统计逻辑失败。

后续修复只调整测试资源清理：提前保存 ViewModel 的 Job，在 clear 后有界等待它完成，
再允许数据库关闭。同类 `BookshelfLayoutPreferenceTest` 同时等待新旧两个 ViewModel，
并推进其测试 Main 调度器后才关闭数据库、重置 Main。保留所有业务断言和未处理异常检测，
不以重试、忽略异常或扩大请求超时处理本次故障。三组定向回归共 18 项通过。
随后对八个 JVM 模块的 test 任务逐一使用 `--rerun` 强制重跑，889 项全部通过、无跳过；
检查完整 JUnit XML，未再出现连接池已关闭或 `UncaughtExceptionsBeforeTest` 异常。

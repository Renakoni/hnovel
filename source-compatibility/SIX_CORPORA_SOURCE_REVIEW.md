# 六份书源的解释器源码对照

本轮从合并 #183 的 `2c93eed` 开始。输入共 6,636 条定义，按完整 JSON 去重后为
4,845 条、3,323 个来源 URL。相同 URL 的不同规则版本分别保留。Wenku8 保留原生实现。

本文记录本轮确认的修复及剩余差异，不是“全部兼容”的验收结论。每项先确定参考源码
的执行语义，再核对产品调用链，最后用相同输入验证。联网结果单独记录，避免把
域名失效、请求被拦截、登录页面或环境缺少 WebView 误算成解释器缺陷。

## 参考版本与证据

- 固定基线：`hectorqin/legado@da17bb2bed44f30b12a524c2457e32a20b16fa41`。
- 交叉审查：`legado-with-MD3@fb01a76ebbbca41423e2c4c00080cc0861239fbd`。
- 实际应用：阅读 3.26.15，MuMu Android 12。APK 暴露的方法不一定与源码完全相同。
- 产品已有五个选择器的移植及原版参考副本，见 `reference/provenance.json`；完整
  `AnalyzeRule`、`AnalyzeUrl` 和宿主对象仍需要跨模块适配，不能用底层选择器测试
  替代完整解释器的验证。
- 本地逐条证据：`E:/H-novel/ref/six-corpora-20260914/`。包含文件哈希、定义哈希、
  原索引、生产导入结果、固定基线运行结果、失败规则及输入、参考 App 输出。
  原书源和响应正文不提交到仓库。

## 执行顺序与实现映射

下列参考路径相对 `app/src/main/java/io/legado/app/`。方法名用于定位源码，
而非依据社区教程推断语法。

| 层/规则 | 参考源码规定 | 产品对应位置 | 本轮审查结论 |
| --- | --- | --- | --- |
| 规则字段导入 | 可选规则对象缺省；实测空数组导出被读作 `null` | `DefinitionParser` → `RuleSourceDefinition.rules` | 四个小说定义受影响；导入和执行两层已接受空数组，非空数组继续拒绝 |
| 设置解析内容 | `AnalyzeRule.setContent` 设置内容和 JSON 模式，并清除选择器缓存；`baseUrl` 与 `redirectUrl` 分开 | `RuleContext`、`ScriptRuleHelpers` | 已有状态和基址实现；不能把临时输入当作 `setContent` |
| 切分脚本 | `AnalyzeRule.splitSourceRule` 将 `@js:`、`<js>…</js>` 与普通规则按顺序执行 | `RuleParser.parse`、`RuleEvaluator.run` | 已移植执行链；产品使用有界扫描，避免参考正则切分误伤字符串 |
| 模式选择 | `SourceRule.init`：显式 CSS/`@@`/XPath/JSON 优先；然后依据内容 JSON 状态、`$.`/`$[`、`/` 判断 | `RuleEvaluator.selectors` | 需区分原始内容模式与中间结果模式；不能把每个 `||` 分支都自动当成独立方言 |
| `@put` | `splitPutRule`、`putRule`：先取解释器内容执行保存规则，再解析字段 | `RuleEvaluator.select`、`RuleContext`、执行状态写回 | 已有对应路径；保留章节、书籍、来源的状态作用域 |
| `@get` / 模板 | `SourceRule.makeUpRule`：模板内 `@`、`$.`、`$[`、`//` 是选择规则，其余为 JS；选择模板读当前内容 | `RuleEvaluator.interpolate`、`ScriptRuleHelpers` | JS 模板中的正则被普通引号扫描误判；已定位并补修复 |
| 模板求值顺序 | `makeUpRule` 从后向前计算并插入参数 | `RuleEvaluator.interpolate` | 已改为先定位、后从右向左求值；参考 App 同输入返回 `right\|right`，产品回归一致 |
| 模板外文字 | 模板参数以外保留为字面量，可以生成下一次使用的请求规则 | `RuleParser`、`RuleEvaluator.interpolate` | 修复 Lofter 简介生成未来请求 JSON 时被引号/括号检查误拒绝；保留生成的下一页模板 |
| 规则脚本与原生回调 | `AnalyzeRule.SourceRule.makeUpRule` 在编译规则脚本前展开 JS/选择模板；`AnalyzeUrl.evalJS`、登录检查等直接执行 JS | `RuleEvaluation` → `ExecutionTask.Rule.scriptTemplates` → `WorkerRuleEvaluator` | 已区分两条路径：正文内嵌 `{{java.get("id")}}` 正常展开，原生回调中的请求模板留给请求编译器；避免预先插入关键词改变 JS 字符串 |
| `&&` / `||` / `%%` | 各 `AnalyzeBy*` 用 `RuleAnalyzer` 分组；拼接、首个非空分支、交织 | `RuleEvaluator.selectors` + 移植选择器 | 需按输出类型定义“空”；不能把类型不符的列表分支当致命错误 |
| JSON 取列表 | `AnalyzeByJSonPath.getList`：只有列表作为结果；非列表、缺失路径返回空，`||` 继续 | `selector/AnalyzeByJSonPath.getList` | 已修复类型回退，并增加固定参考副本与产品直接对照；同一 JSON 返回 20 项 |
| JSON 取对象 | `getObject` 直接 `ctx.read`，对象是合法结果 | `OutputKind.Element` | 与取列表分开；修复列表不能让取对象也变为空 |
| JSON 输入错误 | 构造 `AnalyzeByJSonPath` 时解析整份输入，空文本/坏 JSON 不属于缺失路径 | `AnalyzeByJSonPath.parse` | 保留错误；两个原始空响应在参考端同样失败 |
| 正则列表 `:` | `splitSourceRule(allInOne=true)` 选择 Regex；`AnalyzeByRegex` 返回完整匹配及捕获组 | `RegexRules.extract`、`RuleParser` | 确认引号/字符类词法缺陷；按 Java Pattern 语法修复，无站点分支 |
| 正则串联 `&&` | 前级所有完整匹配连接为下一层输入 | `RegexRules.extract` | 已有对应语义；参考简单拆字符串会破坏 `[a-z&&[^aeiou]]`，产品不复制该错误 |
| `##` 替换 | `makeUpRule` 最后拆替换字段；多于三段触发首段替换；`replaceRegex` 处理匹配失败 | `RuleEvaluator.select`、`RegexRules.replace` | 修复普通规则及选择模板中的替换正则被当成选择器括号扫描；晋江使用的 `showMore[(]…[)]` 同输入对照通过 |
| 输出转换 | `getString` 最终解 HTML 实体；`getStringList` 将最终字符串按换行拆分；元素保持结构 | `RuleEvaluator.run`、`ScriptRuleHelpers` | #183 之前已补齐主要语义；对象原生标识字符串不作为正确兼容目标 |
| URL 结果 | `getString(isUrl)` 空结果回 `baseUrl`，其他相对 `redirectUrl`；列表去重 | `RuleEvaluator.evaluate`、`sourceLink` | 已有独立基址；请求选项和中文/空格到请求阶段编码 |
| URL 规则顺序 | `AnalyzeUrl.initUrl`：脚本 → `{{}}` → `<第一页,后续页>` → 解析选项 → 选项 JS → 编码 | `ScriptRequestTemplates` → `RequestCompiler` | 已补未加引号的 key/page/baseUrl 参数，嵌套 body/header 同样处理；生产链验证 POST、请求体及特殊字符关键词 |
| 请求选项 | `AnalyzeUrl.analyzeUrl` / `UrlOption` 使用 Gson 宽松 JSON | `RequestOptionsJson`、`RequestCompiler` | 改用有大小/深度上限的 Gson 数据读取；支持裸键、单引号、分号、字符串换行、WebView 真值。函数表达式不执行；不复制参考对坏 JSON 静默丢弃选项并改发 GET 的行为 |
| 表单编码 | `AnalyzeUrl.appendEncoded`、`NetworkUtils.encodedForm`：未指定字符集且整个值已经编码时保留；显式字符集则编码 | `RequestCompiler` | 修复已有 `%XX` 被重复编码，避免改变签名使用的表单字节；默认/显式 UTF-8 与参考实际请求体一致，动态关键词仍作为数据编码 |
| 网络 API | `AnalyzeRule.ajax` 复用 `AnalyzeUrl`；参考某些失败返回异常文本 | `ScriptRequestTemplates`、`SourceExecutionBroker`、`SourceSession` | 保留受控请求和明确错误；不把异常文本当成功正文 |
| HTTP 错误响应 | `WebBook.getContentAwait` 将响应 body 交给 `BookContent.analyzeContent`，即使后续脚本完全不使用该响应 | `RuleSource.fetch` / `checkStatus` | 已允许以 JS 开始的正文规则处理 HTTP 错误响应，使占位章节地址不再阻止脚本请求真正正文；普通选择规则保留 HTTP 错误，传输错误和权限拒绝仍传播 |
| JS 绑定 | `AnalyzeRule.evalJS` 绑定 java/cookie/cache/source/book/chapter/result/baseUrl/title/src/nextChapterUrl；MD3 另有 fromBookInfo | `RhinoScriptEngine`、`ScriptFrame` | 已补 title/nextChapterUrl 的序列化、两条 worker 路径和正文调度；续页保持下一章，末章为空。Android 隔离进程测试通过 |
| 状态 API | `AnalyzeRule.put/get`：章节→书籍→来源；bookName/title 是特殊读取键 | `RuleContext`、`ScriptMetadata`、执行状态 | 特殊键和普通变量优先级仍有差异，需补对应状态语义 |
| 提示 API | `JsExtensions.toast/longToast` 对参数原生 `toString` 后显示短/长提示，返回 void | Rhino bridge → 执行 broker → Android Toast | 已补真实提示；不会因循环对象 JSON 化或缺少函数中断验证码交接 |
| 文件/内存缓存 | `CacheManager`：普通缓存存数据库，默认/显式 0 秒不过期；文件缓存走 ACache，内存缓存保留对象；delete 清理三类 | `cache` facade、`SourceBroker.ValueCache` | 尚有真实差异：产品普通缓存为会话内存且默认 60 秒，0 秒被拒绝；文件/内存 API 缺失。不能用统一字符串别名代替三类存储 |
| 目录倒序 | `Book.setReverseToc` 写 `readConfig`；`BookChapterList` 在反向去重后依据配置决定最终顺序 | `ScriptMetadata`、`RuleSource.directory` | 已接通读写配置及目录排序，保留其他 readConfig 字段；覆盖正序、负号倒序、去重、最终索引和持久化 |
| 可选书籍字段 | `BookList.getSearchItem`、`BookInfo` 单独捕获分类、字数、最新章、简介、封面规则失败 | `RuleSource.bookFields` | 已容忍这些字段的 InvalidRule，保留跟踪记录和已有值；分类按字符串列表用逗号合并。必需字段及权限/登录/限额错误继续上报 |
| preUpdateJs | `WebBook` / `AnalyzeRule` 限定刷新目录/书籍 API 的调用时机 | `RuleSource.directory`、宿主动作 | 当前能运行脚本，不等于 refreshTocUrl 等动作已实现；十个定义涉及 |
| 目录/正文翻页 | `BookChapterList`、`BookContent` 区分单页链和整页列表，再合并清理 | `RuleSource`、`ContentMarkup` | 已有调度；本轮补齐正文下一章 URL 上下文并验证续页行为 |
| WebView/Cookie | 参考走原生 WebView 网络和 Cookie；浏览器完成后可重新请求 | `AndroidSourceBrowser`、网络 broker | 已知网络/iframe/会话差异；本轮 JVM 验证不提供浏览器，不能据此判书源失效 |

## 同输入对照已经排除的误判

`snapshots-reference.json` 保存真实阅读对同一份失败响应的结果：

| 规则/输入 | 参考结果 | 归因 |
| --- | --- | --- |
| 阅友 `data||data.list`，同一完整 JSON | 20 个对象 | 产品取列表的类型回退缺陷 |
| 花生 `$.data[*]`，空响应 | JSON 输入异常 | 上游未提供 JSON；不能靠返回空列表掩盖 |
| 番茄 `$..book_data[*]||$..book_info[*]`，空响应 | JSON 输入异常 | 同上 |
| 豆瓣 `class.story-item@a||$.list[*]`，当前 HTML | 选择器异常 | 原规则混合模式/页面结构不符；不是网站不可达 |
| 黑岩 `href` 后取末尾数字，同一 XPath 节点 | `match(...)[0]` 空值异常 | 原规则对节点类型的提取不匹配，参考也失败 |
| 阅友 `tag` 后正则 `.join`，tag 为空 | 单字段 `null.join` 异常，完整搜索继续 | 原脚本未处理空标签；产品还缺少参考的可选字段失败边界，现已修复 |

其他同输入证据：`regex-reference.json`（引号、字符类、模板正则），
`reference-invalid-imports.json`（四份空数组规则导入）、`literal-reference.json`（原始 Lofter
模板及右到左求值）、`request-options-reference.json`（七种请求语法的真实 method/header/body）。这些是已确认范围，不外推
为全部原始定义或全部章节通过。

`selector-reference.json` 又验证了两点：晋江的选择模板替换在参考端正确返回简介；
笔趣阁封面的缺引号脚本、起点元数据的混合引号 XPath，在有效的相同 HTML 输入下参考也报错。
另一个番茄定义把 `##` 替换内容包在 `<js>` 中，参考返回未经处理的原文，产品明确报规则错误；
不将参考的静默无操作当成正确解释目标。静态扫描还会碰到漫画 `payAction` 等原生脚本字段，
这类记录需要按字段语义和小说导入范围判读，不能直接等同于小说规则缺陷。

修复后的固定输入重放经生产 `ExecutionWire` / `WorkerRuntime` 执行，拒绝联网；
Lofter 生成的完整请求文本及有副作用模板与参考逐字一致，JSON 回退两边均为 20 项。
其中字符类交集串联保留有意差异：参考的简单拆分会破坏 Java 正则，产品返回合法匹配。

## 本轮闭环顺序

1. 完成源码映射，区分已有移植、真实缺口和有意差异。
2. 先修复已确认的规则语义：空数组、正则/模板词法、JSON 列表回退、提示 API。
3. 补上下文和宿主状态接口时，沿生产链验证返回值、持久化、调用时机及目录/正文
   结果；不提供“调用不报错但无效果”的假实现。
4. 保留主线固定版本的全量基线；修复后另存重测结果，逐行关联六份文件的原索引。
   检查正文样本，排除升级提示、版权到期和登录错误页。

`💯阅友` 的完整闭环包含可选字段错误、表单签名、HTTP 占位响应和脚本模板四层差异。
最终取得 20 个搜索结果、341 章目录和 2,436 字正文（149 段）；正文样本去除空白后与参考一致。
另一个阅友版本两边都取得 `SignatureDoesNotMatch` 错误页，单独记录，不能算正文成功。
这两项测试显式授权了书源实际返回的阿里云正文域名，产品的网络授权策略仍保留。

## 验证规模与剩余范围

| 原始文件 | 条目数 | 修复后可导入条目 |
| --- | ---: | ---: |
| 71e56d4f.json | 22 | 22 |
| b778fe6b.json | 3,907 | 3,714 |
| 2a1f129b.json | 1,554 | 1,519 |
| e29e19ee.json | 939 | 844 |
| e3e5d620.json | 86 | 78 |
| 4dc410d1.json | 128 | 121 |

- 4,845 份唯一完整定义全部完成导入和静态扫描。唯一小说定义可导入数从 4,553 增至 4,557；
  剩余一份小说定义缺少身份信息，其他未导入类型不属于小说导入器范围。
- 合并 #183 的固定基线完成全部 4,553 个已接收定义的入口验证：搜索、首本详情、目录、
  首个非卷章节，在哪一步停止就记录哪一步；并非每个书源的所有书籍/章节均可用。
- 647 个定义无搜索/发现入口；49 个有发现但无搜索，其中 1 个禁用了发现。
  其余 48 个已另跑发现入口。不能把这些都统计成解释器缺少能力。
- 修复版完成受影响定义的两轮 151/155 项回归，并对后续表单、HTTP 和模板修复另存定向结果。
  静态通用扫描的 RuleParse 可疑项从 52 降至 4；该计数包含非小说及需按字段语法判读的项目。
- 参考 App 联网对照为选定的 17 个原始定义；其中 10 个取得真实正文且保存的样本忽略空白后相同。
  另有固定响应/规则对照及生产 worker 重放。这不是参考 App 的 4,553 项全量联网扫描。
- 相关 JVM 测试 426 项通过，Android APK 构建通过；设备验证覆盖隔离进程、提示/章节上下文、
  规则模板与原生脚本的传递、WebView UA 和动态正文。Wenku8 原生实现未改动。
- 尚未闭环的通用差异主要是特殊变量读取、缓存的持久化/有效期与文件/内存 API、
  目录刷新回调，以及原生 WebView 网络、iframe、Cookie/验证交接。没有用空函数掩盖这些缺口。

完整逐行映射见本地证据目录的 `all-original-rows.jsonl`，汇总见 `REPORT.md`。
`api-inventory-gaps.json` 记录词法 API 提及；其中包含注释、局部同名变量等，不能直接作为必需 API 清单。

火绒本轮日志确认拦截 `www.aastory.space` 和 `www.39yd.com` 的可疑 HTML/JS 跳转。
四个定义记录为 SecurityBlocked，同域另外三个版本为 SecurityExcluded；七个定义均排除后续联网重测。
证据为只读复制后筛选出的本轮 Java 请求日志，不据此判断 Java 程序感染。网络授权拒绝、平台 API
缺失及无搜索能力另列，均不能称作“解释器不支持”或“书源已失效”。

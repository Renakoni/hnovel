# R5 内容链的后续问题记录

范围为 `85f04d7a` 上已有的内容注册、解码、文本处理、绘制和分页行为。R5 只拆分职责，下列问题未在本轮修复。结构说明及验证边界见 [R5 设计记录](r5-content-boundaries.md)；其他阶段问题见[已有记录](refactoring-follow-ups.md)。

“测试确认”仅指所列输入和断言；“源码风险”不代表已经复现了用户界面故障。特征测试锁定本轮前后的一致性，不要求未来永久保留错误行为。

## CONTENT-001：短组件 ID 在不同入口的含义不一致

- 状态：**测试确认**。
- 证据：[ContentJsonDecoder][decoder] 渲染路径给无冒号的 ID 补 `lightnovelreader:`；数据导出路径不补。[ComponentProcessor][processor] 同样按原始 ID 查询数据类型。[解码测试][decode-test] `shortIdsExpandOnlyInTheRenderingEntryPoint` 和[处理测试][processing-test]验证了这一区别。
- 影响：同一份包含 `simple_text` 的 JSON 可以在阅读器显示，却被导出跳过、无法应用文本变换。
- 后续：统一讨论 ID 规范化应发生在输入、存储还是各消费者边界，同时确定未知命名空间处理；不要只改其中一个入口。

## CONTENT-002：错误组件并不覆盖所有解析和构造失败，创建仍有初始化前提

- 状态：**修复已提交 PR #53；逐组件容错和取消传播测试通过**。
- 证据：[解码测试][decode-test] 现在验证错误 JSON 类型、非对象数组项、序列化器异常、反射构造异常和未初始化注入器都只影响当前渲染组件；后续合法组件仍按原顺序创建。取消异常专门断言继续向上传播，数据导出入口仍保持严格异常。
- 修复：渲染解码器在组件边界捕获非取消 `Exception` 并生成错误组件；根数组和数组项形状错误也转成可见错误项。工厂在注入器缺失时返回初始化失败，不再解引用 `!!`。没有捕获 `Error` 或取消异常，也没有改变导出解码策略。
- 影响与限制：损坏组件不再终止整章渲染，错误信息可显示给读者；插件严重错误、取消和导出错误仍可被调用方观察。JVM fixture 不覆盖外部插件 DEX、真实 Compose 进程初始化、网络或设备进程终止。
- 后续：合并 PR #53 后在真实插件加载、阅读入口和导出入口验证错误可见性；如果需要更细的错误分类，应另建协议设计 Issue，不在本次继续扩大 catch 范围。

## CONTENT-003：注册信息没有验证类型关联，也没有并发一致性边界

- 状态：**源码确认，错误插件/并发交错的用户影响待验证**。
- 证据：[注册表][registry] 的 builder 仅检查三个参数非空，独立接受组件类、数据类和序列化器，随后顺序写三张可变 Map。读取快照也分别进行。[PluginInjector][injector] 根据 Class 精确匹配构造参数；注册成功不代表数据类型能够用于组件构造。
- 影响：错误组合可能到反射时才失败；若注册和读取跨线程交错，还可能观察到不同代的类/序列化器。现有重复覆盖测试只覆盖串行情况，没有声称复现了竞争。
- 后续：先定义注册完成时机和可变性，再考虑类型关联校验、单个注册条目或发布完整快照；这些会改变失败时机/并发语义，单独评审。

## CONTENT-004：文本变换会丢弃 JSON 扩展字段和不完整条目

- 状态：**测试确认**。
- 证据：[ComponentProcessor.process][processor] 重建根对象与组件对象，仅写 `components`、`id`、`data`；缺 ID/data 的条目跳过。[处理测试][processing-test] 使用根 `metadata`、组件 `extra`、短 ID 和未知类型，断言完整输出。
- 影响：即使某个组件未匹配当前变换类型，其外层扩展字段也不会原样保留。这与“不匹配类型的组件将原样保留”的 API 注释存在差异，未来扩展可能因此丢数据。
- 后续：明确哪些字段属于可扩展协议，再决定保留策略并同时更新 API 文档与测试。

## PAGE-001：可用高度不足一行时，切片算法会递减到负行号

- 状态：**已修复，固定行度量回归测试通过**。
- 基线证据：[TextPagination.pageText][pagination] 原先在 `getLineBottom(checkLine)` 溢出时不断执行 `checkLine--`，没有下界；基线测试在单行 10px、可用高度 5px 时确认了 `getLineBottom(-1)`。同一契约在零视口输入下也会失败。
- 修复语义：分页尺寸在测量和切片入口统一归一化到至少 1；候选行限制在当前页起始行到最后一行之间，只在候选行仍晚于起始行时回退。若首行本身放不下，则首行单独成页，从而保证每轮至少消费一行且不访问负行号。
- 验证：[分页测试][text-page-test] 覆盖短于一行、零尺寸、负尺寸和正常多页文本；完整 `:app:testDebugUnitTest` 共 105 项通过，`:app:assembleDebug` 成功。
- 限制：测试使用固定行度量，未替代真实字体引擎、设备 Insets 或 Compose 测量；真实设备仍需验证大字号、极小窗口和动态边距下的视觉分页结果。

## PAGE-002：分页测量与实时绘制没有共享完整的输入和失效条件

- 状态：**源码风险，交互场景待复现**。
- 证据：[宿主绘制][renderers] 读取实时 `LocalReaderStyle` 和字体 Flow；[TextPagination][pagination] 在 split 时读取设置，用组件创建时的 TextMeasurer 配置。[翻页宿主][flip] 的 effect key 只有 `chapterContent.content / resources / density`，没有字体设置、字体 URI、边距或布局方向；尺寸取 `resources.displayMetrics`，并非实际内容容器约束。绘制还使用 `LocalTextLocaleList`，测量没有显式使用相同 locale。
- 影响：某些设置或容器变化可能只更新绘制，分页结果仍来自旧输入；多窗口等场景的测量区域也可能不同于绘制区域。此项不等同于已确认的真机闪烁原因。
- 后续：在宿主侧定义一次分页所需的完整参数和失效规则，用受控设置/容器变更测试验证；与 R6 的协程所有权一起讨论，暂不修改 effect key 或调度。

## PAGE-003：分页去重用的 contentKey 从未记录计算结果

- 状态：**源码确认**。
- 证据：[翻页宿主][flip] 初始化 `contentKey = 0`，计算 `chapterContent.hashCode() + width + height` 后只比较，未给 `contentKey` 赋值。
- 影响：它没有实现预期的“上一批分页输入”去重；计算结果恰为 0 时反而会跳过。实际重复分页频率尚未测量。
- 后续：结合 PAGE-002 决定是否需要额外缓存键及其组成，避免只补一次赋值，却把当前未纳入键的样式变化继续忽略。

## CONTENT-005：图片组件没有携带自己的书源请求头归属

- 状态：**源码风险，多书源切换待复现**。
- 证据：[宿主图片绘制][renderers] 通过 `remember(webBookDataSourceManagerApi.getWebDataSource())` 从当前书源取 `imageHeader`；`ImageComponentData` 只携带 URI，没有章节/书源身份。
- 影响：旧组件在切换书源后继续显示或重新组合时，可能取到另一个书源的 Header。当前未验证导航和书源切换是否允许到达这一情况。
- 后续：先复现旧章节与新书源共存时的请求参数，再决定由内容会话还是图片加载边界持有书源上下文；本轮保留原 Header 获取与导航语义。

[decoder]: ../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/content/ContentJsonDecoder.kt
[factory]: ../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/content/ContentComponentFactory.kt
[registry]: ../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/content/ContentComponentRegistry.kt
[injector]: ../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/plugin/injector/PluginInjector.kt
[processor]: ../api/src/main/kotlin/io/nightfish/lightnovelreader/api/text/ComponentProcessor.kt
[pagination]: ../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/content/componet/TextPagination.kt
[renderers]: ../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/content/componet/BuiltInContentRenderers.kt
[flip]: ../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/content/flip/FlipPageContentComponent.kt
[decode-test]: ../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/content/ContentDecodingContractTest.kt
[processing-test]: ../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/content/TextProcessingContentContractTest.kt
[text-page-test]: ../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/content/TextPaginationContractTest.kt

# 独立行距与段距（#66）

本次主目标是 [#66](https://github.com/Renakoni/hnovel/issues/66)：行距作用于段内基线，段距作用于段落边界，滚动与翻页消费相同的测量输入。布局预览页和离线字体属于独立的 [#60](https://github.com/Renakoni/hnovel/issues/60)，在本变更之上完成。

## 配置及责任边界

- 保留 `reader.fontLineHeight` 的附加行高语义：`lineHeight = fontSize + lineSpacing`，默认 7 sp，范围 0–32 sp。新增 `reader.paragraphSpacing`，默认 0 sp，范围 0–64 sp；缺少新键的已有用户不额外增加段距。两项独立保存和恢复，主题页在原行距控件旁提供段距入口。
- `ReaderLayoutValues` 集中定义正文已有数值和新增段距的合法范围。有限越界值夹紧，NaN/Infinity 回落默认值；读取不重写历史存储。字号 8–64 sp、字重 100–900、手动边距 0–128 dp 的范围与已有控件一致。
- `ReaderLayoutSettings` 是不拥有存储、协程的不可变排版快照；`rememberReaderTextLayout` 捕获已解析字体、locale、TextStyle、TextMeasurer 和段距像素值。正文与一次分页请求使用同一快照，颜色留到绘制时解析。
- `layoutReaderText` 只负责逻辑段落、行度量、段落间距和页面装填；`ReaderTextFragments` 负责可选择文本的 Compose 绘制。连续内置文本组件能够共享页面，避免短段落各占一页。
- `paginateReaderComponents` 仅接管宿主内置 `SimpleTextComponent`。图片、错误和插件组件保留顺序及独立页面，继续调用原 `split` / `Content` 协议。没有修改插件构造器、序列化正文或数据库 schema。

## 段落与特殊块语义

旧 `simple_text` 只有字符串，无法还原早期解析已经丢失的 HTML `p` / `br` 差异。本次使用明确的兼容规则，不根据标点、缩进或行长猜测段落：

1. LF / CRLF 结束一个段落；自动折行和已有 U+2028 是段内换行。
2. 连续空行保留为显式空段落，各占一个文本行盒；末尾分隔符终止当前段落，不追加虚构空段落。原始字符范围包括分隔符，可逐项重建原文。
3. 段距仅计入同页相邻段落之间，包括相邻内置文本组件。空字符串组件跳过。新页顶部没有段距，长段落跨页续排不重复增加段距。
4. 图片或插件块的两侧不插入正文段距。内置文本形式的标题按普通文本规则排版；滚动模式的章节导航标题属于界面装饰，沿用其原布局。
5. 视口小于一行时仍消耗一整行，保证分页前进。文本使用 `LineBreak.Simple`、关闭额外字体 padding，并保留行盒首尾高度，确保页面切片的测量和绘制一致。

## 重排与阅读位置

`ReaderPage` 保存宿主运行时的组件索引和 UTF-16 字符范围。间距、字号、字体、边距或窗口变化前，翻页控件捕获可见页首字符；新分页定位包含该字符的页面，避免用新页数的百分比猜测原位置。

连续重排保留待恢复锚点，`FlipPaginationCoordinator` 继续负责取消与最新结果发布，`FlipReadingProgress` 继续负责首次章节恢复和进度记录。尚未完成的首次恢复优先；正常锚点重排不会被旧页数百分比覆盖。锚点绑定当前章节内容列表，内容版本变化后不复用。

应用重启仍使用原持久进度协议；不透明插件切片按组件及片段序号近似定位。本变更不引入跨内容版本、跨模式的持久字符定位协议。

## 验证入口

- `ReaderTextLayoutTest`：短段落合页、段距边界、长段续页、CRLF/空行/末尾分隔符、完整字符范围、极小视口和非法数值。
- `ReaderSettingsBoundaryTest`：两个存储键独立写入、多个设置适配器恢复和非法历史值保护。
- `FlipModeContractTest` / `FlipPaginationCoordinatorTest`：锚点恢复不被百分比替换、首次恢复优先、旧请求失效。
- `ReaderSpacingInstrumentedTest`：真实 Android 字体测量与 Compose 绘制一致；图片独立分页、相邻文本合页；实际滚动组件中两项间距互不替代；实际 Pager 连续重排后仍显示原字符。

CI 在 API 24 和 API 35 上运行设备测试；完整应用 JVM 回归继续由原工作流执行。各次执行结果记录在对应 PR 中。

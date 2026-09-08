# 重构期间的后续问题记录

本文件记录结构拆分时发现的行为差异、设计风险和功能疑点。初始来源为 R3 的书籍访问消费链检查，基线为 R4 合并后的 `323ca480`（PR #6）；随后在 R3 合并后的 `85f04d7a`（PR #7）回查 R1、R2、R4。所列问题供后续独立评审，复查仅补充文档和测试，没有修改生产逻辑。

“已证实”只覆盖所列测试/源码证据；“待复现”不等于已确认用户可见故障。特征测试用于证明本轮重构保持行为，并不意味着这些行为必须永久保留。后续修改规则时，应连同相应断言一起更新。

## R1 / R2 / R4 复查结论与优先级

| 边界 | 主要发现 | 归因与后续优先级 |
| --- | --- | --- |
| R1 窗口副作用 | 退出恢复会覆盖其他入口更新的系统栏样式，常亮标志的归属也未保存 | 旧逻辑保留；优先明确窗口状态所有权，见 WIN-001 |
| R2 设置 | `safeAsState` 不处理解析异常；主题状态、字体清理仍未完全脱离阅读 UI | 旧实现/未完成拆分，见 SET-001、SET-002 |
| R2 测试 | 普通 Map 被多个 IO 协程访问，默认值和实例身份断言无法证明编辑传播 | R2 新增测试的潜在缺陷，见 TEST-001 |
| R4 记录/计时 | 缓冲清空丢秒数、分段取整丢分钟；另有重复结算、进度滞后、会话归属与任务寿命问题 | 优先处理统计一致性；主要为旧逻辑保留，见 STATS-001/002、READ-002～006、BOOK-002 |

历史核对：`StatsRepository`、`AbstractSettingState` 和 `FloatUserData` 从拆分前 `a9425c05` 到此次基线没有改动；窗口旧快照恢复原先就在 `ReaderScreen.Content` 中；进度计算和计时循环也在 R1/R4 中按原行为保留。本次没有证实这些拆分引入了新的生产行为回归，但不等于对所有导航、主题和设备组合做了完整验证。

此次新增 6 项验证：真实 `StatsRepository` 配合可记录的 DAO 替身（3 项）、真实窗口效果的 SDK 27 Compose/Robolectric 测试（2 项）、真实设置观察/解析链配合非法值输入（1 项）。旧的 R1 枚举测试不覆盖窗口恢复，旧的 R4 协调测试不进入统计仓库缓冲；这些覆盖范围的差别不能用“已有测试通过”代替。

## BOOK-001：缓存刷新失败覆盖已成功读取的内容（P1，已修复）

- 跟踪：[issue #11](https://github.com/Renakoni/hnovel/issues/11)。修复基线为当前 main `1dd4604f`（R6 合并后），历史拆分只保留行为，本次独立修正语义。
- 原因与复现：正文、目录及书籍详情均先发射本地 `Ok`，再无条件发射远端 `Err`。在未修改的基线上，25 项相关测试有 8 项失败；真实 ChapterRepository → ReaderChapterLoader → 两种阅读控制器的受控集成测试确认，延迟到达的错误会撤销已经显示的缓存内容。这是可用内容丢失，超出注释偏差；测试没有模拟网络或设备绘制。
- 修复规则：遵循 [BookRepositoryApi](../api/src/main/kotlin/io/nightfish/lightnovelreader/api/book/BookRepositoryApi.kt) 原有契约。每次收集先判断该请求的本地值；有本地值且远端返回 `Err` 时只保留第一次本地成功发射，继续记录错误日志；无本地值时仍发射 `Err`；成功刷新仍先保存原始数据，再发射经文本处理的远端结果。书籍详情的书架更新时间/更新标记仍在远端成功时按原顺序执行。
- 责任边界：规则位于仓库，阅读模式继续消费章节结果。缓存命中不跨请求、章节、书籍或收集保存；切换到未缓存章节失败仍显示新章节错误。没有增加错误状态 API、重试策略或重复本地发射，避免重复渲染、记录和预加载。`BENCHMARK` 命中本地即返回的路径保持原样。
- 回归证据：[章节及兼容门面契约测试](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/book/ChapterSourceContractTest.kt)、[详情流测试](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/book/BookInformationFlowTest.kt) 覆盖成功/失败与有/无缓存、冷流、重新收集、当前书源、优先级、存储/处理顺序；[缓存阅读契约测试](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/mode/CachedChapterReaderContractTest.kt) 由翻页和滚动各自实例化，覆盖延迟错误后保留显示对象、不重复渲染/写入及未缓存章节错误。
- 范围限制：这里的“失败”是书源返回的 `Err<WebRequestError>`；存储、文本处理或其他直接抛出的异常和协程取消继续传播。独立模式特征测试仍可注入 `Ok → Err` 验证消费者的替换行为，但生产仓库在本地命中后的远端请求错误不再产生这个序列。损坏的缓存内容、全新章节错误、跨章节旧任务回写（READ-001）仍有各自的问题边界。

## BOOK-002：阅读数据的读改写没有覆盖整个操作的原子边界

- 状态：**源码确认存在可交错步骤；丢更新待复现**。
- 证据：[LocalBookDataSource.updateUserReadingData](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/local/LocalBookDataSource.kt) 先调用 DAO 读记录，再执行变换，最后调用 [UserReadingDataDao.insert](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/local/room/dao/UserReadingDataDao.kt) 覆盖整行。当前方法没有让完整的读改写成为一个事务或受同一个锁保护；进度与总时长使用独立协程调用它。
- 影响：如果进度任务和时间任务读到同一旧值，后一次整行写入可能覆盖前一次更新。单条 `replace into` 的原子性不足以保证整个读改写操作的原子性。
- 后续：用可控挂起点复现两个调用同时读旧值的情况，并验证最终进度与累计时长。再评估事务、按字段更新或按书籍串行化的成本；这会改变并发语义，应独立处理。

## BOOK-003：KEEP 策略下返回的新请求 ID 可能不是正在运行的任务

- 状态：**机制风险，待 WorkManager 集成测试验证**。
- 证据：[BookRepository.cacheBook](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/book/BookRepository.kt) 每次构造新请求，以 `cache:<bookId>` 和 `KEEP` 入队，然后返回新请求。[DetailViewModel.cacheBook](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/detail/DetailViewModel.kt) 用返回请求的 ID 观察任务完成。若同名未完成任务已存在，KEEP 会保留原任务。
- 影响：被忽略的新请求 ID 可能没有对应的运行记录，详情页对这个 ID 的观察可能收不到原任务完成结果。当前 R3 测试验证参数保持，没有启动实际 worker，尚未验证重复请求场景。
- 后续：在受控 WorkManager 环境连续提交同一书籍，核对实际工作记录、返回 ID 和完成观察；再决定按唯一任务名观察还是显式返回已存在的任务身份。

## BOOK-004：非空卷列表中的所有卷都没有章节时，缓存状态为 true

- 状态：**已证实的边界行为，产品语义待确认**。
- 证据：[BookRepositoryOperationsTest](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/book/BookRepositoryOperationsTest.kt) 的 `cacheStatusKeepsMissingEmptyAndPartiallyCachedVolumeSemantics` 验证：无目录和空卷列表返回 false；存在一个没有章节的卷时返回 true。
- 影响：目录不完整的书籍可能显示为已缓存；也可能是对空卷的合理处理，目前缺少明确规则。
- 后续：定义“已缓存”是否要求至少存在一个可阅读章节，再决定是否调整判断。不要仅为了统一空集合处理而修改行为。

## READ-001：快速切换章节时，旧翻页任务可能回写新界面（P1，修复已提交）

- 状态：**已由翻页模式受控测试确认，修复已提交到独立 PR**。R6 只明确了模式任务所有权，没有改变替换行为；本项是其后的行为修复。
- 证据：原有 [FlipModeContractTest](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/content/flip/FlipModeContractTest.kt) 先启动 A 再启动 B，让 B 先成功、A 后成功，最终章节 ID 回到 A；A/B 两个订阅直到 reader scope 退出仍活动。`FlipReaderController.changeChapter` 也没有保存/取消上一章的收集和恢复任务，异步写入期间继续从 `uiState` 读取书籍 ID。
- 修复语义：翻页控制器为每次章节请求保存 `Job`、请求代次和请求开始时的 book ID。切章或换书取消旧章节收集及旧进度恢复；每个结果在更新 UI、完成阅读记录后续操作前检查当前代次，不能依赖书源 Flow 一定及时响应取消。恢复进度在 `FlipReadingProgress` 内同样按代次校验，旧读取完成不能污染新章节的待恢复页。
- 滚动模式保持自己的 `ScrollChapterWindow` 订阅所有权，本 PR 不把连续滚动的三槽替换算法改成翻页模式的单任务算法。
- 后续限制：测试使用可控 Flow、挂起的阅读记录和协程调度器，覆盖仓库/加载器以外的章节请求交错；未宣称覆盖每种真实网络、导航动画或设备生命周期组合。若发现滚动窗口在关闭连续模式后继续回写，继续由 SCROLL-001 单独处理。

## READ-002：直接移除仍处于 RESUMED 的阅读器会重复提交剩余时长

- 状态：**已修复，受控 Compose/Lifecycle 回归测试通过**。
- 基线证据：[ReaderReadingTimeEffectsTest](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/ReaderReadingTimeEffectsTest.kt) 在 `main` 上确认直接移除产生“统计 -1 → 总时长 n → 统计 -1 → 总时长 n”，暂停后移除还会额外写入 0。
- 修复语义：由 `LifecycleResumeEffect` 独占总时长结算；移除重复的 `DisposableEffect` 结算入口。暂停和组合销毁都通过同一个 `onPauseOrDispose` 路径完成一次结算，统计缓冲 flush 保持独立入口。
- 验证：覆盖直接移除、暂停后移除、恢复新计时段、当前书籍切换和空书籍抑制；完整 `:app:testDebugUnitTest` 共 103 项通过，`:app:assembleDebug` 成功。
- 限制：测试验证 Compose 生命周期回调语义，不覆盖所有真实导航栈/设备后台路径；这些路径仍需真机验证，重点检查退出时持久化是否完成。

## READ-003：进度事件的标题与书籍 ID 在不同时间读取

- 状态：**R4 的受控调度测试已证实输入读取时机**。
- 证据：[ReaderReadingRecordsTest](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/ReaderReadingRecordsTest.kt) 的 `queuedProgressCapturesTheTitleButReadsTheBookCountAndTimeWhenWriting` 和 `completionCheckWaitsForPersistenceAndReadsTheLiveBookAfterSuspension`。标题在接收事件时捕获，书籍 ID 在异步写入及挂起恢复之后读取。
- 影响：若书籍在排队或写入期间改变，旧章节事件可能使用新书 ID，写入目标与完成检查目标也可能不同。实际导航是否允许触发该交错仍需确认。
- 后续：用跨书籍切换事件验证会话归属，决定是否在事件入口捕获完整身份或采用会话标识。该修正会改变当前写入目标，应该独立于接口拆分。

## WIN-001：窗口恢复的责任范围超过阅读器实际拥有的状态

- 状态：**SDK 27 的受控窗口测试已证实；拆分前已有**。
- 证据：[ReaderWindowOwnershipTest](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/ReaderWindowOwnershipTest.kt) 验证：进入后其他入口将 `isAppearanceLightStatusBars` / `isAppearanceLightNavigationBars` 设为 true（浅色背景对应的深色图标）时，移除阅读效果会恢复旧标志；进入前已存在的 `FLAG_KEEP_SCREEN_ON` 也会被退出清理清除。[ReaderWindowEffects](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/ReaderEffects.kt) 保存整份 `systemUiVisibility`，却无条件清除常亮，并有两处系统栏显示清理。
- 影响：[LightNovelReaderTheme](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/theme/LightNovelReaderTheme.kt) 也设置系统栏图标样式；阅读期间若主题改变，退出恢复旧快照可能覆盖新主题样式。常亮测试说明潜在所有权冲突，当前源码未发现阅读器以外的常亮设置入口，不能据此声称已有其他页面常亮故障。
- 限制：测试覆盖旧版 `systemUiVisibility` 路径，不包含 Android 11+ 的实际窗口帧或完整主题导航流程。
- 后续：明确主题拥有图标样式、阅读器拥有临时显隐等责任；为各项状态定义恢复规则并验证清理顺序。不要直接恢复整份旧 flags 或仅按重复代码数量删除清理。

## SET-001：safeAsState 名称没有对应的解析失败保护

- 状态：**受控设置链测试已证实；R2 之前已有**。
- 证据：[SettingObservationFailureTest](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/SettingObservationFailureTest.kt) 使用真实 [AbstractSettingState](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/setting/AbstractSettingState.kt) 和 [FloatUserData](../api/src/main/kotlin/io/nightfish/lightnovelreader/api/userdata/FloatUserData.kt)：输入 `malformed` 产生 `NumberFormatException`，订阅结束；测试捕获异常后改成 `22.0`，该实例仍停在初始 `15f`。`safeAsState` 与 `asState` 当前实现相同；颜色解析也使用会抛异常的转换。
- 影响：非法存储值或上游 Flow 异常可以终止观察。生产没有在该观察链捕获异常；[MainActivity](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/MainActivity.kt) 安装的 [LogUtils](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/utils/LogUtils.kt) 会对未捕获异常记录日志并退出进程，因此不能把实际后果仅描述为“设置不刷新”。测试自行捕获异常，没有执行退出进程。
- 限制：未发现普通字体大小滑块会生成该非法字符串；触发条件是数据无效或观察失败，并非所有正常设置操作都会出错。
- 后续：明确解析失败、存储失败各自的回退与恢复策略，校正 `safe` 的语义；用“错误输入 → 后续有效输入”验证恢复。这个问题与保留旧版本兼容性无关。

## SET-002：R2 已收窄读取接口，但状态与副作用归属尚未完整拆开

- 状态：**设计欠账，未确认新增功能故障**。
- 证据：[ReaderSettings](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/ReaderSettings.kt) 中定义了全局 `ThemeSettings`；[ThemeViewModel](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/home/settings/theme/ThemeViewModel.kt) 仍实例化阅读器的 [SettingState](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/SettingState.kt)，每个实例创建 31 个阅读属性与 5 个主题属性的观察任务。只读值和编辑接口目前仍由同一实现提供，这是 R2 的阶段性边界。
- 另一处边界：[rememberReaderFontFamily](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/utils/ReaderCustomUtils.kt) 仍在渲染期间加载字体，并在失败时调用清理/持久化。R2 将依赖收窄到 `getFlow/clear`，但没有把字体失败处理策略移出渲染。旧 `UriUserData` 重载仍有主题预览和文本组件调用者，不是无用的历史兼容入口。
- 影响：未来修改全局主题、字体失败策略时，仍需理解阅读状态或 UI 生命周期；不能把“渲染接收只读设置”理解为整个渲染链完全没有写入副作用。这里尚无订阅数量导致卡顿的性能证据。
- 后续：按真实消费者逐步归位主题状态和字体加载/清理责任；编辑控件保留必要的写能力。无需为每个属性建立接口，也无需引入旧版本迁移框架。

## TEST-001：R2 设置测试的替身与断言不足以覆盖真实编辑传播

- 状态：**R2 新增测试的静态并发风险；本次未复现偶发失败**。
- 证据：[ReaderSettingsBoundaryTest](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/ReaderSettingsBoundaryTest.kt) 的内存 DAO 使用普通 `mutableMapOf` 和 `getOrPut`；测试 scope 虽采用 `Dispatchers.Unconfined`，但生产 `safeAsState` 明确在 `Dispatchers.IO` 启动多个观察任务，因此这些 Map 仍会被并发访问。
- 影响：替身存在非线程安全访问；两个现有测试仅覆盖代表性默认值/路径和实例身份，不能证明保存后刷新、两个状态实例同步或字体清理不会覆盖更新的值。全部测试通过也无法消除这些覆盖缺口。
- 后续：改用线程安全替身或受控存储，再测试实际编辑 → 持久化 → 多个观察者更新的链路。修复测试可独立进行，无需改变生产调度以迁就测试。

## STATS-001：入书统计和单本书结算会清除不属于该次写入的缓冲

- 状态：**真实统计仓库的受控 DAO 测试已证实；R4 之前已有**。
- 证据：[StatsRepositoryCharacterizationTest](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/statistics/StatsRepositoryCharacterizationTest.kt) 验证两个序列：缓存某书 10 秒 → 写入该书入书次数 → flush，最后仅有 1 次阅读、0 秒；缓存 A 的 10 秒和 B 的 20 秒 → flush B → flush A，最后仅有 B 的 20 秒。[StatsRepository](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/statistics/StatsRepository.kt) 在 `updateReadingStatistics` 和缓冲结算中对整个 Map 调用 `clear()`；负数命令遍历所有 key，却始终处理传入的同一个 bookId。
- 影响：未结算时间可能丢失，即使没有同时操作同一行数据库。入书记录与每秒累积来自不同协程，因此应同时审查事件顺序和缓冲的归属；其设备触发频率尚未测量。
- 后续：区分入书事件与时间结算的缓冲责任，明确单书 flush 和全部 flush；仅移除已成功结算的缓冲，并用原有两个序列验证秒数完整保留。

## STATS-002：每次结算独立取整，导致短阅读时间永远不进入总览分钟数

- 状态：**真实统计仓库的受控 DAO 测试已证实；R4 之前已有**。
- 证据：[StatsRepositoryCharacterizationTest](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/statistics/StatsRepositoryCharacterizationTest.kt) 的 `twoThirtySecondSettlementsProduceSixtyBookSecondsButZeroSummaryMinutes`：两次各 30 秒并分别 flush，书籍记录累计 60 秒，总览仍为 0 分钟。`updateCount` 对每次 `secondDelta / 60` 取整，没有保存余数；`getTotalReadingSummary` 使用这个按分钟累计的统计。
- 影响：经常暂停/退出形成的短会话会在总览中少计，累计足够一分钟也不会补回，书籍秒数与总览分钟数产生分歧。
- 后续：定义唯一的统计时间单位和聚合边界，保留余数或从累计秒数派生分钟；覆盖分段结算、跨小时/日期和重复 flush，避免同时改变展示规则而无法定位差异。

## READ-004：总体进度和读完标记滞后一次章节进度写入

- 状态：**R4 既有测试已证实；提取前公式相同**。
- 证据：[ReaderReadingRecords](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/ReaderReadingRecords.kt) 先用旧最大进度 Map 计算总体进度，再更新当前章节。[ReaderReadingRecordsTest](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/ReaderReadingRecordsTest.kt) 的 `finishingUsesTheReadAfterWriteResultAndStillCallsTheRepositoryForRepeatedEvents` 验证：两章原最大进度 0.5 和 1，当前章写到 1 后总体仍是 0.75；下一次再写才成为 1 并调用读完标记。
- 影响：最终章节进度已完成却未必立即表现为整本读完。如果没有下一次进度事件，该状态可能一直滞后。
- 后续：明确总体进度应基于本次更新后的数据计算，并结合结束阅读时最后一次进度事件验证读完标记；与并发写入问题 BOOK-002 一起评估。

## READ-005：计时累计的是循环次数，恢复时立即计入一秒

- 状态：**R4 既有虚拟时间测试已证实计数规则；暂停频繁时的实际偏差未测量**。
- 证据：[ReaderReadingTimeEffectsTest](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/ReaderReadingTimeEffectsTest.kt) 验证恢复后立即产生 1 秒回调，第 61 次计数提交 61 秒；循环在计数后 `delay(1.seconds)`。两条循环不是由同一份实际经过时间派生。
- 影响：短暂恢复后立即暂停也可能记入 1 秒；主线程调度延迟又可能造成少计。R4 注入的 `LocalDateTime` 只控制记录时间戳，不控制这些循环，也不控制统计仓库的 `LocalTime`/日期。
- 后续：先明确产品是否需要实际可见阅读时长，再考虑单调时间源和统一的时间区间结算；保留暂停/退出重复结算的专门用例，避免仅修正阈值而遗漏 READ-002。

## READ-006：目录收集与阅读模式缺少明确的替换/销毁边界

- 状态：**R6 测试已确认模式替换后任务继续活动、宿主清理时取消；目录与旧模式的实际干扰仍待验证**。
- R6 补充：已明确模式选择由 ReaderModeHost 管理、任务寿命由 reader scope 提供。实际 ReaderViewModel 与模式替身测试确认切换不取消旧任务、清理 ViewModel 会取消全部模式任务，详见 [R6 证据记录](r6-reader-mode-follow-ups.md)。目录和独立统计 scope 的规则仍待后续讨论。
- 证据：[ReaderViewModel](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/ReaderViewModel.kt) 的 bookId setter 每次启动目录收集而未保存/取消前一任务；模式切换创建新控制器时，旧控制器仍使用同一个 `viewModelScope`。[ReaderModeController](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/content/ReaderModeController.kt) 没有释放契约。独立统计 scope 也没有随 ViewModel 关闭；R4 为保持行为未改变它。
- 影响：旧目录请求可能较晚回写；旧模式的设置/滚动观察可能持续到宿主 scope 结束。接口变窄并不会自动建立任务所有权或取消策略。
- 后续：在已确认的模式任务寿命基础上，继续控制真实旧/新模式与目录请求的完成顺序，确认替换后的写入；分别定义模式任务、目录任务与需要完成退出结算的统计任务寿命，不宜全部套用同一种 cancel 策略。

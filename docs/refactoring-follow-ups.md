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

- 状态：**修复已提交 PR #50；受控唯一工作观察测试通过**。
- 证据：基线的 [BookRepository.cacheBook](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/book/BookRepository.kt) 每次构造新请求，以 `cache:<bookId>` 和 `KEEP` 入队，然后返回新请求；[DetailViewModel.cacheBook](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/detail/DetailViewModel.kt) 用这个新 UUID 观察。若同名未完成任务已存在，KEEP 保留原任务，UUID 与观察对象脱离。EPUB 导出有相同的入队/按新 UUID 观察模式。
- 修复：缓存和 EPUB 导出都先等待 `enqueueUniqueWork` 的 Operation 完成，再按唯一工作名查询 `getWorkInfosForUniqueWorkFlow`，避免既观察被 KEEP 忽略的新 UUID，也避免入队完成前读到上一次的终态。
- Review 证据：项目使用的 WorkManager 2.11.2 在单请求、无依赖的 KEEP 入队事务中保留现有活动记录，或删除旧终态记录再插入新请求（`EnqueueRunnable.enqueueWorkWithPrerequisites`）。这些名称没有 APPEND 链，因此不需要对历史终态排序；已移除本 PR 先前加入的时钟标签及排序器，不引入持久序列或迁移。
- 验证：受控 Flow/future 测试覆盖两个入口的入队等待；真实 WorkManager 入队算法与内存 WorkDatabase 测试确认活动请求保留、后退时钟下旧终态删除及新请求身份。没有启动网络 worker，也未模拟真实进程重启或设备后台限制。
- 后续：合并 PR #50 后在真实缓存和 EPUB 导出路径验证重复点击、后台恢复及进程重启。其他使用 KEEP 的导出/书架入口若增加状态提示，应复用唯一工作名观察规则。

## BOOK-004：非空卷列表中的所有卷都没有章节时，缓存状态为 true

- 状态：**已证实的边界行为，产品语义待确认**。
- 证据：[BookRepositoryOperationsTest](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/book/BookRepositoryOperationsTest.kt) 的 `cacheStatusKeepsMissingEmptyAndPartiallyCachedVolumeSemantics` 验证：无目录和空卷列表返回 false；存在一个没有章节的卷时返回 true。
- 影响：目录不完整的书籍可能显示为已缓存；也可能是对空卷的合理处理，目前缺少明确规则。
- 后续：定义“已缓存”是否要求至少存在一个可阅读章节，再决定是否调整判断。不要仅为了统一空集合处理而修改行为。

## CACHE-001：内存缓存代理的读取键与写入键不一致

- 状态：**已提交修复 PR #55**。
- 证据：[ProxyCachedWebBookDataSource](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/web/proxy/ProxyCachedWebBookDataSource.kt) 基线按请求 key 的 `hashCode()` 查询，却按 `origin.id.hashCode()` 写入。使用真实 [Cache](../api/src/main/kotlin/io/nightfish/lightnovelreader/api/util/Cache.kt) 的回归测试确认同一卷目录请求连续两次都会调用底层。
- 修复：读写使用相同的完整请求键（方法、书籍 ID、章节 ID）。Cache 按完整 key 的 equality 判定命中，整数 hash 碰撞和字符串拼接歧义不会再返回别的请求的数据；类型分组、过期和容量策略保持。
- 回归覆盖：`Aa`/`BB` 同 hash 书籍、`ab+c`/`a+bc` 章节组合、重复命中和响应类型隔离。网络传输和设备进程行为不在 JVM 测试覆盖范围内。

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

- 状态：**修复已提交 PR #49；受控调度回归测试通过**。
- 证据：[ReaderReadingRecordsTest](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/ReaderReadingRecordsTest.kt) 覆盖事件排队及存储挂起后切书；写入与完成检查固定使用事件入口捕获的书籍 ID 和标题。章节总数按捕获的书籍 ID 查询已加载目录计数，避免读取另一书的 UI 分母。
- 目录计数由 ViewModel 的现有目录订阅持续更新，记录层不另开冷 Flow、不永久缓存首次计数、不等待网络。目录未加载或为空时仍保存章节进度，保留已存整体值；后续有效发射自动更新计数。[ReaderDirectoryProgressTest](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/ReaderDirectoryProgressTest.kt) 用真实 ViewModel 验证目录挂起时可写进度、空目录和多次刷新均使用同一个订阅。
- 影响：旧实现可能把旧章节写入新书，或把完成检查发给新书。修复限制了异步任务的身份漂移；真实导航是否产生同一交错仍需设备验证。
- 后续：合并 PR #49 后验证真实导航、后台切换和进程终止路径。READ-004 继续单独处理总体进度公式的滞后问题，BOOK-002 继续负责仓库读改写的原子边界。

## WIN-001：窗口恢复的责任范围超过阅读器实际拥有的状态

- 状态：**SDK 27 的受控窗口测试已证实；拆分前已有**。
- 证据：[ReaderWindowOwnershipTest](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/ReaderWindowOwnershipTest.kt) 验证：进入后其他入口将 `isAppearanceLightStatusBars` / `isAppearanceLightNavigationBars` 设为 true（浅色背景对应的深色图标）时，移除阅读效果会恢复旧标志；进入前已存在的 `FLAG_KEEP_SCREEN_ON` 也会被退出清理清除。[ReaderWindowEffects](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/ReaderEffects.kt) 保存整份 `systemUiVisibility`，却无条件清除常亮，并有两处系统栏显示清理。
- 影响：[LightNovelReaderTheme](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/theme/LightNovelReaderTheme.kt) 也设置系统栏图标样式；阅读期间若主题改变，退出恢复旧快照可能覆盖新主题样式。常亮测试说明潜在所有权冲突，当前源码未发现阅读器以外的常亮设置入口，不能据此声称已有其他页面常亮故障。
- 限制：测试覆盖旧版 `systemUiVisibility` 路径，不包含 Android 11+ 的实际窗口帧或完整主题导航流程。
- 后续：明确主题拥有图标样式、阅读器拥有临时显隐等责任；为各项状态定义恢复规则并验证清理顺序。不要直接恢复整份旧 flags 或仅按重复代码数量删除清理。

## SET-001：safeAsState 名称没有对应的解析失败保护

- 状态：**修复已提交 PR #51；受控设置链回归测试通过**。
- 证据：[SettingObservationFailureTest](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/SettingObservationFailureTest.kt) 在旧 main 上确认 Float 的 `malformed` 值抛出 `NumberFormatException` 并终止订阅；修复后覆盖非法 Float/Color 值回退默认、后续合法值恢复，以及底层观察异常不逃逸。`safeAsState` 现在与 `asState` 具有明确不同的异常语义。
- 修复：`FloatUserData` 和 `ColorUserData` 使用可空解析，让逐值格式错误转换为 null 并由默认值处理；`safeAsState` 捕获非取消的底层 Flow 异常、记录日志并发射一次默认值，取消异常继续传播。
- 影响与限制：设置页面不会因损坏的 Float/Color 存储值退出或停止后续合法值观察。底层 DAO 失败会保留默认值并结束该订阅，不能凭 JVM 测试保证存储层之后自动重连；正常滑块写入路径和真机进程行为仍需设备验证。
- 后续：合并 PR #51 后验证真实损坏设置值、主题加载和底层存储失败路径；其他未使用 `safeAsState` 的状态仍保留其原有异常策略。

## SET-002：R2 已收窄读取接口，但状态与副作用归属尚未完整拆开

- 状态：**设计欠账，未确认新增功能故障**。
- 证据：[ReaderSettings](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/ReaderSettings.kt) 中定义了全局 `ThemeSettings`；[ThemeViewModel](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/home/settings/theme/ThemeViewModel.kt) 仍实例化阅读器的 [SettingState](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/SettingState.kt)，每个实例创建 31 个阅读属性与 5 个主题属性的观察任务。只读值和编辑接口目前仍由同一实现提供，这是 R2 的阶段性边界。
- 另一处边界：[rememberReaderFontFamily](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/utils/ReaderCustomUtils.kt) 仍在渲染期间加载字体，并在失败时调用清理/持久化。R2 将依赖收窄到 `getFlow/clear`，但没有把字体失败处理策略移出渲染。旧 `UriUserData` 重载仍有主题预览和文本组件调用者，不是无用的历史兼容入口。
- 影响：未来修改全局主题、字体失败策略时，仍需理解阅读状态或 UI 生命周期；不能把“渲染接收只读设置”理解为整个渲染链完全没有写入副作用。这里尚无订阅数量导致卡顿的性能证据。
- 后续：按真实消费者逐步归位主题状态和字体加载/清理责任；编辑控件保留必要的写能力。无需为每个属性建立接口，也无需引入旧版本迁移框架。

## TEST-001：R2 设置测试的替身与断言不足以覆盖真实编辑传播

- 状态：**测试改进已提交 PR #52；生产实现未发现新增故障**。
- 证据：[ReaderSettingsBoundaryTest](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/ReaderSettingsBoundaryTest.kt) 现在使用并发安全、共享 Flow 的内存 DAO，并验证一个 `FloatUserData` 写入后两个独立 `SettingState` 观察者都收到持久化值。旧实现的普通 `mutableMapOf`/`getOrPut` 只构成测试替身风险，没有复现生产行为失败。
- 影响：CI 对“编辑 → 持久化 → 多观察者传播”的覆盖更接近真实设置链，替身不再因生产 `Dispatchers.IO` 观察任务而产生数据竞争。该 PR 只修改测试，不改变生产调度。
- 限制与后续：测试仍不是 Room 真机并发测试，也不覆盖进程终止或设备存储故障；合并 PR #52 后保留为测试边界记录，若未来发现真实 DAO 并发问题再独立建生产 Issue。

## STATS-001：入书统计和单本书结算会清除不属于该次写入的缓冲

- 状态：**真实统计仓库的受控 DAO 测试已证实；R4 之前已有**。
- 证据：[StatsRepositoryCharacterizationTest](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/statistics/StatsRepositoryCharacterizationTest.kt) 验证两个序列：缓存某书 10 秒 → 写入该书入书次数 → flush，最后仅有 1 次阅读、0 秒；缓存 A 的 10 秒和 B 的 20 秒 → flush B → flush A，最后仅有 B 的 20 秒。[StatsRepository](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/statistics/StatsRepository.kt) 在 `updateReadingStatistics` 和缓冲结算中对整个 Map 调用 `clear()`；负数命令遍历所有 key，却始终处理传入的同一个 bookId。
- 影响：未结算时间可能丢失，即使没有同时操作同一行数据库。入书记录与每秒累积来自不同协程，因此应同时审查事件顺序和缓冲的归属；其设备触发频率尚未测量。
- 后续：区分入书事件与时间结算的缓冲责任，明确单书 flush 和全部 flush；仅移除已成功结算的缓冲，并用原有两个序列验证秒数完整保留。

## STATS-002：每次结算独立取整，导致短阅读时间永远不进入总览分钟数

- 状态：**已修复，真实统计仓库的受控 DAO 回归测试通过**。
- 基线证据：[StatsRepositoryCharacterizationTest](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/statistics/StatsRepositoryCharacterizationTest.kt) 的分段结算契约在 `main` 上失败：两次各 30 秒后，书籍记录累计 60 秒，但 `getTotalReadingSummary` 仍返回 0 分钟。原因是 `updateCount` 对每次 `secondDelta / 60` 取整且不保留余数。
- 修复语义：总览分钟数从持久化的 `BookRecordEntity.seconds` 汇总后统一除以 60，并以 Long 计算后安全转换为 Int；秒数成为总览的唯一精度来源，不再依赖每次结算时已经取整的 `Count`。
- 验证：覆盖两次 30 秒分段结算；完整 `:app:testDebugUnitTest` 共 103 项通过，`:app:assembleDebug` 成功。
- 限制：按小时 `Count` 和热力图仍是分钟粒度，无法从现有记录恢复每小时的秒余数；本修复只校正总览汇总，不改变热力图的既有展示语义。若产品需要按小时精确累计，应另立数据模型/迁移 Issue。

## READ-004：总体进度和读完标记滞后一次章节进度写入

- 状态：**本分支已修复计算顺序；基线问题由受控仓库测试复现**，对应 [Issue #24](https://github.com/Renakoni/hnovel/issues/24)。
- 基线证据：在 `main@1dd4604f` 生产代码上运行调整后的 [ReaderReadingRecordsTest](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/ReaderReadingRecordsTest.kt)，14 项中 6 项因旧 Map 计算失败。两章最大进度为 0.5 和 1 时，将前者写为 1，整体仍为 0.75；首次上报四章之一的 0.5 进度时，整体仍为 0。
- 修复语义：[ReaderReadingRecords](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/ReaderReadingRecords.kt) 在同一个 `updateUserReadingData` 回调内先更新当前/历史最大章节 Map，再用更新后的历史最大值求和、除以章节数并限制在 0～1。两例现在分别为 1 和 0.125；写入完成后回读即可调用读完标记，无需第二次进度事件。
- 回归契约：覆盖首次上报、同章更新、回读不降低历史最大值、目录未就绪时保留原总进度、总进度上限、末章完成及等待写入结束后才标记读完。重复完成事件仍交给现有仓库处理。
- 验证结果：14 项记录测试全部通过；完整 `:app:testDebugUnitTest` 为 106 项，失败/错误/跳过均为 0；`:app:assembleDebug` 和 `git diff --check` 通过。
- Review 与基线同步：保留 main 已合并的 PR #49 事件身份边界：入口捕获书籍 ID 和标题，写入时按该书籍读取已观察到的目录计数，写入、回读与读完检查始终使用同一书籍。PR #47 在此基础上用本次更新后的章节最大进度聚合整体值；不重新引入 UI 当前书籍计数，也不新增目录请求。
- 限制：受控 `ReaderRecordStore` 验证计算与调用顺序，不证明真实导航退出时异步任务必然完成。本次保持章节等权计算规则，目录未就绪时保留已存整体值。

## READ-005：计时累计的是循环次数，恢复时立即计入一秒

- 状态：**已修复，受控 Compose/Lifecycle 回归测试通过**。
- 基线证据：`main@1dd4604f` 的 [ReaderReadingTimeEffectsTest](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/ReaderReadingTimeEffectsTest.kt) 原有契约确认恢复后立即产生 1 秒回调，第 61 次循环提交 61 秒；两条循环按回调次数累积，并非按实际经过时间累积。
- 修复语义：计时开始和每次调度都读取单调 `SystemClock.elapsedRealtime`（测试注入可控时钟），累计从上次测量点到当前的完整秒数；暂停/销毁前再补一次测量。小于一秒的恢复/暂停不产生秒数，调度延迟会在下一次测量补齐；总时长每 60 秒结算，剩余秒数在退出时结算。
- 验证：覆盖立即恢复/暂停、调度延迟、暂停后恢复、直接移除和书籍 ID 变化；计时测试 5 项通过，完整 `:app:testDebugUnitTest` 共 103 项通过，`:app:assembleDebug` 成功。
- 限制：本 PR 不处理 READ-002 的重复生命周期结算入口；退出路径仍需真机验证异步持久化是否完成。`LocalDateTime` 记录时间戳和统计仓库的书籍归属仍由其他 Issue 负责。

## READ-006：目录收集与阅读模式缺少明确的替换/销毁边界

- 状态：**R6 测试已确认模式替换后任务继续活动、宿主清理时取消；目录与旧模式的实际干扰仍待验证**。
- R6 补充：已明确模式选择由 ReaderModeHost 管理、任务寿命由 reader scope 提供。实际 ReaderViewModel 与模式替身测试确认切换不取消旧任务、清理 ViewModel 会取消全部模式任务，详见 [R6 证据记录](r6-reader-mode-follow-ups.md)。目录和独立统计 scope 的规则仍待后续讨论。
- 证据：[ReaderViewModel](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/ReaderViewModel.kt) 的 bookId setter 每次启动目录收集而未保存/取消前一任务；模式切换创建新控制器时，旧控制器仍使用同一个 `viewModelScope`。[ReaderModeController](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/content/ReaderModeController.kt) 没有释放契约。独立统计 scope 也没有随 ViewModel 关闭；R4 为保持行为未改变它。
- 影响：旧目录请求可能较晚回写；旧模式的设置/滚动观察可能持续到宿主 scope 结束。接口变窄并不会自动建立任务所有权或取消策略。
- 后续：在已确认的模式任务寿命基础上，继续控制真实旧/新模式与目录请求的完成顺序，确认替换后的写入；分别定义模式任务、目录任务与需要完成退出结算的统计任务寿命，不宜全部套用同一种 cancel 策略。

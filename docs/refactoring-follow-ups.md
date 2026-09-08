# 重构期间的后续问题记录

本文件记录结构拆分时发现的行为差异、设计风险和功能疑点。初始来源为 R3 的书籍访问消费链检查，基线为 R4 合并后的 `323ca480`（PR #6）。这些条目未在 R3 中修复，供后续独立评审。

“已证实”只覆盖所列测试/源码证据；“待复现”不等于已确认用户可见故障。特征测试用于证明本轮重构保持行为，并不意味着这些行为必须永久保留。后续修改规则时，应连同相应断言一起更新。

## BOOK-001：本地缓存之后的远端错误与 API 注释不一致

- 状态：**已证实的契约差异**。
- 证据：[BookRepositoryApi](../api/src/main/kotlin/io/nightfish/lightnovelreader/api/book/BookRepositoryApi.kt) 的目录/章节 Flow 注释说，远端失败而本地存在时只发射本地值。实际 [ChapterRepository](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/book/ChapterRepository.kt) 仍发射远端 `Err`；提取前后的 `remoteFailureStillFollowsCachedSuccessForBothFlows` [契约测试](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/data/book/ChapterSourceContractTest.kt) 均证明这一点。
- 影响：按文档编写的消费者可能错误地假设缓存成功后不会再收到错误。阅读模式目前逐次替换章节结果；缓存成功之后的错误有覆盖显示状态的风险，用户界面结果尚未单独复现。
- 后续：先决定刷新失败是否应该覆盖缓存内容，再同步实现、API 文档和消费者测试；如仅修正文档，也应明确正常构建与 benchmark 分支的差异。书籍详情 Flow 的同类注释也需要一起核对。

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

## READ-001：快速切换章节时，旧翻页任务可能回写新界面

- 状态：**设计风险，待模式级测试复现**。
- 证据：[FlipPageContentViewModel.changeChapter](../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/content/flip/FlipPageContentViewModel.kt) 每次启动章节收集和进度恢复协程，未记录/取消上一章的这两个任务，也没有在写状态前检查事件所属章节。章节 Flow 可能先发本地、再较晚发远端。
- 影响：先请求 A 再切换 B 时，A 的较晚结果可能覆盖 B 的显示状态，或影响进度恢复。滚动模式有不同的 Job 管理，不能直接假定两者应套用同一算法。
- 后续：R6 中用独立 `ChapterSource` fake 控制 A/B 的发射和完成顺序，记录 UI、进度、预加载和持久化；确定任务所有权后再修改取消或结果归属规则。

## READ-002：直接移除仍处于 RESUMED 的阅读器会重复提交剩余时长

- 状态：**R4 的受控 Compose/Lifecycle 测试已证实**。
- 证据：[ReaderReadingTimeEffectsTest](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/ReaderReadingTimeEffectsTest.kt) 的 `leavingWhileResumedWritesTheSameRemainderOnBothSidesOfFlush`：总时长 n → 统计 -1 → 总时长 n。先暂停再移除则为统计 -1 → 总时长 n → 总时长 0。
- 影响：直接移除路径可能重复累计剩余时长。该测试没有测量设备上每种导航是否经过此路径。
- 后续：明确总时长结算的唯一所有者及生命周期入口，再用现有暂停/恢复/移除测试验证新规则；不要在本轮通过删除一个 effect 改变行为。

## READ-003：进度事件的标题与书籍 ID 在不同时间读取

- 状态：**R4 的受控调度测试已证实输入读取时机**。
- 证据：[ReaderReadingRecordsTest](../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/ReaderReadingRecordsTest.kt) 的 `queuedProgressCapturesTheTitleButReadsTheBookCountAndTimeWhenWriting` 和 `completionCheckWaitsForPersistenceAndReadsTheLiveBookAfterSuspension`。标题在接收事件时捕获，书籍 ID 在异步写入及挂起恢复之后读取。
- 影响：若书籍在排队或写入期间改变，旧章节事件可能使用新书 ID，写入目标与完成检查目标也可能不同。实际导航是否允许触发该交错仍需确认。
- 后续：用跨书籍切换事件验证会话归属，决定是否在事件入口捕获完整身份或采用会话标识。该修正会改变当前写入目标，应该独立于接口拆分。

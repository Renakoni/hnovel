# R6 阅读模式的后续问题记录

以下均为原有行为，本轮只拆分与验证，不修复。基线为 R5 合并提交 `31ecc5b9`。职责、任务所有权和测试边界见 [R6 设计说明](r6-reader-mode-boundaries.md)。

## 已有 READ-001 / READ-006 的补充证据

- [翻页测试][flip-test] `olderChapterSubscriptionsRemainActiveAndCanOverwriteTheNewChapterUntilReaderExit` 先请求 A 再请求 B，让 B 先成功、A 后成功，最终章节 ID 回到 A；两个源订阅都持续到 reader scope 取消。R6 前后的同一用例均通过。
- [所有权测试][ownership-test] 使用实际 ReaderViewModel 和模式替身，确认切换模式时传递同一个 scope；旧模式任务仍活动，ViewModelStore.clear 后它们全部被取消。R6 明确了这一生命周期归属，但没有增加停用旧模式的行为。
- 这两项证据不等同于完整真实导航/网络环境中的复现；目录任务、独立统计 scope 的问题继续留在[原记录](refactoring-follow-ups.md)。后续需要分别决定会话切换、模式停用、旧请求结果归属与退出结算规则。

## SCROLL-001：关闭连续滚动后，旧相邻订阅仍可回写三槽

- 状态：**原实现与拆分后测试确认**。
- 证据：[ScrollModeContractTest][scroll-test] 的 `turningOffContinuousScrollingLeavesOldAdjacentSubscriptionsAliveUntilReaderExit`：连续模式订阅当前/前/后章，切换为非连续后槽位被清空，但旧前后章订阅仍在；让旧后章继续发射，槽 2 再次出现数据。[ScrollChapterWindow][window] 直接跳章只取消 current Job；停止连续观察也不等同于取消两个相邻 Job。
- 影响：非连续界面可能再次收到旧相邻内容；跨章节重新加载也存在类似交错路径，需要进一步覆盖具体布局影响。
- 后续：定义三槽订阅的会话身份与关闭顺序，再决定取消点或结果归属校验。保留在首次当前章节成功后才创建相邻订阅的因果顺序。

## FLIP-001：进度读取晚于 Pager 创建时，不会立即应用恢复

- 状态：**原实现与拆分后测试确认**。
- 证据：[FlipModeContractTest][flip-test] 的 `lateStoredProgressWaitsForAnotherPagerUpdate` 控制阅读记录读取的挂起点，让非空 Pager 先到达。读取恢复后只设置待恢复值；直到再次调用 `updatePagerState` 才收到目标页请求。
- 影响：分页结果快于记录读取时，首次页面可能显示初始位置，保存的进度处于等待状态。具体真实布局是否恰好再次建立 Pager 决定可见结果。
- 后续：明确章节、分页结果与恢复进度三个条件的协调时机，避免简单在每次变化时滚动而重复恢复或覆盖用户操作。

## FLIP-002：排队恢复使用旧 Pager 的页数，却滚动当前 Pager

- 状态：**受控 Pager 替身测试确认调用参数与目标对象**。
- 证据：[FlipModeContractTest][flip-test] 的 `queuedRestorationUsesTheOldPageCountButScrollsTheCurrentPager`：先传入 5 页 Pager，再在任务执行前替换为 2 页 Pager，最终对新 Pager 调用 `scrollToPage(2)`。[FlipReadingProgress][flip-progress] 捕获参数 Pager 计算目标，再通过 `uiState.pagerState` 调用滚动。
- 影响：布局快速重新分页时，目标页和对象可能不属于同一次分页；真实 Pager 还可能对索引作进一步限制，测试没有声称测量了最终屏幕位置。
- 后续：把恢复输入与 Pager/章节身份一起定义，确定过期恢复是取消、忽略还是重算；本轮保留任务排队与对象读取方式。

## SCROLL-002：throttleLatest 不会在窗口结束时自动补发暂存值

- 状态：**可控时钟与协程调度测试确认**。
- 证据：[ScrollProgressTimingTest][timing-test] 的 `pendingThrottledOffsetsDoNotEmitOnTimeAloneButStoppingRecalculatesProgress` 在首次偏移后 50ms 发出新偏移，再推进时间与调度器，进度仍停在首值。现有 [throttleLatest][throttle] 只在下一次输入或上游完成时检查/发射暂存值，没有定时器。
- 影响：名称容易让调用者误以为“最多每 120ms 输出最新值”。滚动模式还有独立停止观察会重新计算并写入，因此不能据此直接认定持久化一定丢进度。
- 后续：先决定所需的是节流、采样还是带尾发射的节流，再同时审查 2500ms 写入门槛、停止与完成进度例外。可控时钟只用于证明现有行为，默认时间来源仍不变。

## MODE-001：切换模式使用最后的显式跳章 ID，而非当前显示章节

- 状态：**实际 ReaderViewModel 与可移动章节的模式替身测试确认**。
- 证据：[ReaderModeOwnershipTest][ownership-test] 先显式请求 initial，通过模式的 next 命令把显示 ID 改为 initial-next，再切换模式，新模式仍绑定 initial。[ReaderViewModel][reader] 的私有 chapterId 只在 `changeChapter` 更新，上一章/下一章和模式内连续跨章不回写该字段。
- 影响：模式内翻到另一章节后切换阅读模式，可能重新打开旧的显式请求章节。替身测试证明了输入归属差异，未模拟真实列表/Pager 的所有交互。
- 后续：明确“用户请求章节”和“当前阅读章节”的关系，再决定切换时读取哪一个；不要只改一处跳章回调造成两个身份不完整同步。

## SCROLL-003：相邻订阅的 ID 检查不约束预加载

- 状态：**原实现与拆分后测试确认，产品规则待决定**。
- 证据：[ScrollModeContractTest][scroll-test] 的 `selfLinksAndDuplicateAdjacentIdsDoNotCreateExtraSubscriptions`：自引用前/后链接或两个相同邻章不会创建邻章订阅，但 current success 仍对 nextChapter 发起预加载；两个不同角色恰好指向同一邻章时，当前相邻检查还会把两侧都跳过。
- 影响：异常目录可能重复预加载当前章，或不显示可用的邻章。是否应容忍这类数据，需要与书源协议一起决定。
- 后续：定义当前章/前章/后章的合法身份关系后，分别评估预加载和显示窗口规则；本轮不把两种操作合并为同一去重策略。

[flip-test]: ../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/content/flip/FlipModeContractTest.kt
[scroll-test]: ../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/content/scroll/ScrollModeContractTest.kt
[timing-test]: ../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/content/scroll/ScrollProgressTimingTest.kt
[ownership-test]: ../app/src/test/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/mode/ReaderModeOwnershipTest.kt
[window]: ../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/content/scroll/ScrollChapterWindow.kt
[flip-progress]: ../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/content/flip/FlipReadingProgress.kt
[throttle]: ../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/utils/Extentions.kt
[reader]: ../app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/book/reader/ReaderViewModel.kt

# R6 阅读模式的后续问题记录

以下均为原有行为，本轮只拆分与验证，不修复。基线为 R5 合并提交 `31ecc5b9`。职责、任务所有权和测试边界见 [R6 设计说明](r6-reader-mode-boundaries.md)。

## 已有 READ-001 / READ-006 的补充证据

- [翻页测试][flip-test] `olderChapterSubscriptionsRemainActiveAndCanOverwriteTheNewChapterUntilReaderExit` 先请求 A 再请求 B，让 B 先成功、A 后成功，最终章节 ID 回到 A；两个源订阅都持续到 reader scope 取消。R6 前后的同一用例均通过。
- [所有权测试][ownership-test] 使用实际 ReaderViewModel 和模式替身，确认切换模式时传递同一个 scope；旧模式任务仍活动，ViewModelStore.clear 后它们全部被取消。R6 明确了这一生命周期归属，但没有增加停用旧模式的行为。
- 这两项证据不等同于完整真实导航/网络环境中的复现；目录任务、独立统计 scope 的问题继续留在[原记录](refactoring-follow-ups.md)。后续需要分别决定会话切换、模式停用、旧请求结果归属与退出结算规则。

## SCROLL-001：关闭连续滚动后，旧相邻订阅仍可回写三槽（P1，修复已提交）

- 状态：**原实现与拆分后测试确认，修复已提交到独立 PR**。
- 对照证据：在保留新增断言、强制重新编译并恢复 main 生产代码时，[ScrollModeContractTest][scroll-test] 10 项中 1 项失败。连续模式先订阅当前/前/后章，切换为非连续后，`ScrollChapterWindow.stopContinuousObservation()` 只取消布局观察 Job，前/后章 Job 仍活动；旧后章晚到发射会重新填充槽 2。
- 修复语义：停止连续观察时同时取消并清空 `collectPrevChapterJob` 与 `collectNextChapterJob`。当前章由既有 `changeChapter` 重载流程负责，前后章不会继续以邻章身份回写；重新打开连续模式仍按当前章成功后的顺序创建新的相邻订阅。
- 影响边界：取消使用 Kotlin 结构化 Job 生命周期，未增加全局过滤或吞掉章节结果。合法连续滚动的三槽顺序、预加载和跨章替换保持原实现；非合作的外部 Flow/真实网络生命周期仍需真机和集成环境观察。
- 后续：跨章节窗口切换、模式切换身份和分页问题继续由 MODE-001、READ-001、PAGE-* 独立跟踪。

## FLIP-001：进度读取晚于 Pager 创建时，不会立即应用恢复

- 状态：**原实现与拆分后测试确认**。
- 证据：[FlipModeContractTest][flip-test] 的 `lateStoredProgressWaitsForAnotherPagerUpdate` 控制阅读记录读取的挂起点，让非空 Pager 先到达。读取恢复后只设置待恢复值；直到再次调用 `updatePagerState` 才收到目标页请求。
- 影响：分页结果快于记录读取时，首次页面可能显示初始位置，保存的进度处于等待状态。具体真实布局是否恰好再次建立 Pager 决定可见结果。
- 后续：明确章节、分页结果与恢复进度三个条件的协调时机，避免简单在每次变化时滚动而重复恢复或覆盖用户操作。

## FLIP-002：排队恢复使用旧 Pager 的页数，却滚动当前 Pager

- 状态：**基线测试确认，修复已提交到独立 PR**。
- 基线证据：[FlipModeContractTest][flip-test] 的 `queuedRestorationUsesTheCurrentPagerPageCountAndTarget` 先传入 5 页 Pager，再在任务执行前替换为 2 页 Pager；在原始 `main@1dd4604f` 上，验收期望新 Pager 根据自身页数得到目标 `0`，实际收到旧页数计算出的目标 `2`，测试失败。原实现捕获旧 `pagerState.pageCount`，却通过 `uiState.pagerState` 滚动。
- 修复语义：每次 Pager 替换都会取消尚未执行的恢复任务；任务执行时重新读取被捕获 Pager 的当前页数，并确认它仍是 `uiState.pagerState`，随后在同一个 Pager 上计算和调用 `scrollToPage`。页数变为零或任务已过期时不滚动；切章会取消遗留恢复。
- 回归覆盖：新 Pager 页数重算、空 Pager 替换、原有进度取整与 Pager 观察测试均通过。测试只证明受控 Pager/协程时序和目标调用，不覆盖真实分页测量、设备导航或最终视觉位置。
- 后续：真实设备上仍需观察分页重建与 Compose Pager 的实际时序；FLIP-001 的晚到记录协调和其他分页语义保持独立。

## SCROLL-002：throttleLatest 不会在窗口结束时自动补发暂存值

- 状态：**可控时钟与协程调度测试确认**。
- 证据：[ScrollProgressTimingTest][timing-test] 的 `pendingThrottledOffsetsDoNotEmitOnTimeAloneButStoppingRecalculatesProgress` 在首次偏移后 50ms 发出新偏移，再推进时间与调度器，进度仍停在首值。现有 [throttleLatest][throttle] 只在下一次输入或上游完成时检查/发射暂存值，没有定时器。
- 影响：名称容易让调用者误以为“最多每 120ms 输出最新值”。滚动模式还有独立停止观察会重新计算并写入，因此不能据此直接认定持久化一定丢进度。
- 后续：先决定所需的是节流、采样还是带尾发射的节流，再同时审查 2500ms 写入门槛、停止与完成进度例外。可控时钟只用于证明现有行为，默认时间来源仍不变。

## MODE-001：切换模式使用最后的显式跳章 ID，而非当前显示章节（P1，修复已提交）

- 状态：**实际 ReaderViewModel 与可移动章节的模式替身测试确认，修复已提交到独立 PR**。
- 证据：[ReaderModeOwnershipTest][ownership-test] 先显式请求 initial，通过模式的 next 命令把显示 ID 改为 initial-next，再切换模式，原实现仍绑定 initial。[ReaderViewModel][reader] 的私有 `chapterId` 只在 `changeChapter` 更新，上一章/下一章和模式内连续跨章不回写该字段。
- 修复语义：模式切换时由宿主优先读取当前旧模式 `ContentUiState.readingChapterId`；旧模式尚未建立有效章节时才回退到最后显式请求目标。两种模式不互相调用，模式内部的章节导航仍归各自控制器所有。
- 影响边界：模式切换继续复用同一个 reader scope，旧模式任务生命周期、记录和进度写入策略不在本项改变；真实分页/网络完成时序仍需设备与集成验证。

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

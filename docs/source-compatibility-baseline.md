# VNR-10 书源兼容基线与验证边界

对应 [#82](https://github.com/Renakoni/hnovel/issues/82)。核对宿主 main：`ad62f4c0199f663475973885ed26511848990476`。本 PR 交付测试框架和可核验契约，不交付 App 内规则执行或导入功能。

> 本文保留 VNR-10 建立时的研究边界。后续生产功能、扩展源码依据、分类能力、账号语义及最终测试归属以 [VNR-23 验收报告](source-compatibility-acceptance.md) 和当前矩阵为准；文中的「待实现/待补证」是当时状态。

## 兼容目标

| Profile | 定义 | 当前证据 |
| --- | --- | --- |
| `legado-text-da17bb2` | `hectorqin/legado@da17bb2bed44f30b12a524c2457e32a20b16fa41` 的文本来源语义 | 固定源码、依赖和部分直接参考执行 |
| `advanced-sample-20260908` | 私有复杂样本暴露的目标扩展能力 | 字段形态、成员名清单与合成交互契约；扩展宿主出处/部分语义尚待补证 |
| `unknown` | 无法识别的格式/分支/API | 明确诊断，不能静默忽略后标为支持 |

完整小说兼容包括选择器、组合/替换、变量、JavaScript、请求、登录、浏览器、发现、目录/正文分页、图片与生命周期。音频/漫画/文件来源类型需能识别，但相应播放器、TTS、漫画 JS 直接执行和旧 `.lnrp` 二进制兼容不属于此小说目标。没有旧用户不意味着可以省略外部来源格式兼容。

固定版本用于取得可重复的依据。官方仓库在本次检查时只含移除源码的公告；保留分支不称为官方 latest。Yuedu 是来源分发库，社区教程是说明文档，二者均不能代替实际执行规范。

## 测试结构与依赖

现有项目使用 Hilt、JUnit4、MockK、Robolectric 和 Compose 宿主测试，CI 运行 `:app:testDebugUnitTest`。本 PR 增加独立 `:source-compatibility:test`，沿用同一个 CI job 和报告上传，不引入新的必需检查名称。

新模块无生产源码、Android/插件依赖或真实网络请求。保留五个未改动的参考解析器，使用 SHA-256 校验，并明确附带 GPL 许可。宿主不依赖该模块。参考依赖跟随实际构建：Rhino **1.8.1**，不是已被注释的旧 1.7.14 JAR；Jsoup **1.16.2**，不跟随宿主升级到较新版本。

当前可执行样本覆盖 HTML/CSS/默认规则、JSONPath、XPath、正则、组合与嵌套、共享脚本状态、混合提取、登录动作、动态发现与正文多页契约。期望值从小样本手工推导，再与实际参考选择器或 Rhino 执行交叉核对。不存在自动把当前输出保存成正确答案的更新命令。

其中登录、发现、分页和共享脚本使用合成宿主服务，**不是**完整上游 `JsExtensions`、`AnalyzeRule`、`WebBook`、CookieStore 或 WebView 的执行证明。完整桥接和端到端参考对比由各实现 Issue 补齐。这种证据差别写入每个样本，不能用测试总数掩盖。

本文建立时产品状态为 `planned`；后续 PR 已逐项更新矩阵，当前状态以矩阵为准。生产连接与仍未完成的端到端部分见 [Rhino 合并后 checkpoint](vnr-checkpoint-after-rhino.md)。完整功能映射、未来测试 ID 和负责人见 [coverage.json](../source-compatibility/src/test/resources/coverage.json)。已经运行的案例见 [cases.json](../source-compatibility/src/test/resources/cases.json)。最终 #95 的兼容性验收不能因为这些参考测试通过而提前关闭。

## 高级样本的真实范围

静态清单只记录 52 个成员/字段名，不复制原 JSON、脚本、账号、私有接口或认证值。成员出现不等于实际可达、可执行或线上可用。详见 [advanced-inventory.json](../source-compatibility/src/test/resources/advanced-inventory.json)。

- `customButton`、`eventListener` 在样本中是布尔标记，不能把它们当作 JS 文本直接执行。
- `loginUi` 在样本中为 `@js:` 脚本。固定参考 `BaseSource.loginUi()` 则直接把字符串解析为表单数组；这是需要识别的扩展差异，归 #88，不能静默视为标准表单。
- `infoMap.set/save`、部分 `java.upConfig/upLoginData/reLoginView/refreshExplore` 等动作不由当前已核验的标准桥接声明证明，归 #91。具体对象构造、事件时机和持久化效果仍需扩展宿主证据。计划是实现明确的受控等价协议，不能仅凭方法名猜测或永久 stub。
- 网络、编码、来源变量、Cookie/cache 等可映射到标准能力族，但重载、返回类型、错误和生命周期仍要逐项验证。
- `bookSourceUrl` 可能包含身份片段，来源身份不等于请求根地址、显示名、规则版本或账号。导入与修订服务负责保留其语义。

扩展能力已分派到实现 Issue，未决语义仍是可追踪的完成条件。本 PR 不以 CSS 子集替代整个目标。

## 参考特例和后续判断

| 记录 | 直接证据 | 影响与处理 |
| --- | --- | --- |
| QREF-001 | `JSON-INTERLEAVE-TAIL`：`$.short%%$.long` 以第一个结果的长度迭代，较长后续结果的尾部被丢弃 | 参考兼容性差异，可能丢条目；#84 必须明确遵循或有意修正，不能因觉得“合并应该更完整”而无声改变 |
| QREF-002 | `REGEX-OPTIONAL-FIRST`：单条正则遇到未匹配可选组抛 NPE；批量路径返回空字符串 | 参考实现的正确性问题，可能中断单条解析；#84 应明确安全处理和兼容诊断，不要求产品复刻崩溃 |
| DIALECT-001 | 样本脚本 loginUi/布尔事件标记与固定标准字段不同 | 如果误判为标准，将无法登录或缺失动作；#88/#91 必须补方言证据和测试 |

这些不是当前 HNovel 已发生的 P0/P1 事故。QREF-001/002 属于 P2 级参考正确性风险；若未来未经隔离直接向宿主传播异常，才可能形成具体运行阻碍。没有执行依据的扩展行为不作严重度断言。

## 后续验证要求

纯规则/状态/请求映射优先 JVM 和离线响应；真实 Room/Worker 使用必要宿主测试。浏览器 Cookie/localStorage/Service Worker 与可终止进程边界必须使用 Android 仿真器 CI，Robolectric 或合成 JS host 不足以证明。

后续实现把真实产品适配器接入同一批独立期望值，增加真实链路测试，再更新对应状态和覆盖报告。未完成能力、未知 API、外部站点失败和账号权限不足应明确区分。参考错误和有意偏差须记录，不能靠降级目标来获得“全兼容”。

运行方法和更新规范见 [模块说明](../source-compatibility/README.md)。

# 发现页实机排查与 tab 规划（2026-09-14）

基线为 main `ad97a20d`（PR #198）。使用六份原始 JSON，先对照参考源码，再通过 MuMu 中安装的 debug APK 测试。修复后的实测结果见本文末尾；静态数量不代表联网通过率。

## 已定位并修复的差异

| 触发条件 | 修复前 | 本轮修复 |
| --- | --- | --- |
| 豆瓣的 `exploreUrl` 使用单引号、裸键名 | 严格 JSON 解析拒绝整个目录，搜索仍成功 | 对齐参考的 Gson 宽松数据语法，不执行 JSON 中的代码；目录采用独立的 512,000 字符上限，保留 1,024 行限制 |
| 阅友的 JS 目录生成 `title="", url=null, style={}` | `exploreUrl[9].title` 报错 | 接受并跳过无动作的布局占位；空标题但有网址的入口保留，UI 显示“未命名入口” |
| 刺猬猫 www 的回退分支 `tag.tr!0`、晋江的 `#diss@tr!0@td` | APK 抛 `ValidationException`，独立模块的测试通过 | 使用保留元素组成新列表，避免把 Jsoup Elements 成员设为 null，也不修改原 DOM |
| 番茄发现返回约 1.3 MB 的 API 数据 | `ruleExplore.bookList` 在执行预算检查时失败 | 搜索/发现书单在逐本提取字段前，使用与章节列表相同的 2 MiB 输出上限；单字段限制与 1,000 本书单限制保持独立 |
| 发现首个入口失败、后续入口可用 | `feed()` 返回整体错误，已解析目录也不可见 | 预览错误附在对应 section，保留入口和其他预览；“重试”打开该入口的结果页，沿用权限、验证和重试流程 |

Jsoup 是本次“源码已迁移但 APK 仍不兼容”的明确实例：`source-rules` 的依赖是 **1.16.2**，应用的最终依赖是 **1.22.2**。参考项目在版本表中明确提醒新版本存在破坏性变化。旧迁移代码用 `elements[index] = null` 再删除 null；新版禁止该写入。单测必须覆盖应用最终依赖和实际 isolated worker，不能只测试独立解释器模块。

本轮新增主应用 JVM 回归和 `IsolatedExecutionInstrumentedTest.exclusionSelectorsRunAgainstThePackagedJsoupWithoutMutatingTheDocument`；这条设备测试随 API 24 / 35 的现有 CI 类列表执行。新增的联网诊断类只接受显式启用，不加入默认 CI 联网测试。

## 参考实现与产品模型

对照固定版本 [hectorqin/legado da17bb2](https://github.com/hectorqin/legado/tree/da17bb2bed44f30b12a524c2457e32a20b16fa41)：

- `help/source/BookSourceExtensions.kt` 的 `exploreKinds()`：执行 JS 后转为字符串；数组交给 Gson，其余按 `&&` / 换行和 `::` 解析。
- `ui/main/explore/ExploreAdapter.kt`：展开书源只显示入口目录；没有网址的行是标题，点击有效入口后才请求书单。它没有要求每个书源提供首页预览。
- `data/entities/rule/ExploreKind.kt`：传统入口主要只有 `title/url/style`。MD3 扩展了输入、按钮等交互，不能把这些控件当作题材类别。
- `model/analyzeRule/AnalyzeByJSoup.kt` 的排除索引代码，以及 `gradle/libs.versions.toml` 对 Jsoup 1.16.2 的版本说明。
- `model/webBook/BookList.kt`：发现规则缺少有效 `bookList` 时回退搜索规则。产品已有这个回退，但搜索成功仍不能保证发现 URL 返回相同结构。

产品的 `RuleDiscoveryProvider` 还负责生成首页预览。这一额外行为让一个失效入口曾经拖垮整个页面，不能归因于“阅读也没有发现功能”。当前仍按原顺序展示传统入口，默认只自动加载一个预览，避免 QQ 等数百入口的目录触发数百次联网。语义分流是下一阶段的工作。

## 六份 JSON 的字段统计

运行 `python source-compatibility/tools/discovery_inventory.py <六个文件路径> --output <输出文件>` 可复验。聚合结果与输入 SHA-256 在 [discovery-inventory.json](discovery-inventory.json)。脚本只读取文件，不联网，不执行 JS，不输出书源网址、书名或正文。

| 口径 | 数量 |
| --- | ---: |
| 原始定义行 | 6,636 |
| 按完整 JSON 去重 | 4,845 |
| 其中小说定义 `bookSourceType=0` | 4,558 |
| 提供搜索 URL | 3,861 |
| 提供发现入口 | 2,590 |
| 没有发现入口 | 1,968 |
| 严格 JSON 数组 / 宽松数组 / 旧文本 / JS 目录 | 1,280 / 57 / 1,133 / 120 |
| 非空 `homepageModules` / `exploreScreen` | 0 / 0 |

这里的 4,558 是原始小说定义数，不能与此前 importer 接受的 4,557 个定义混为一谈。`enabledExplore=true` 有 4,396 个，它是显示开关，不代表存在发现规则。

可静态读取的 81,281 行只有 `title/url/style`。题材候选 9,608 行，发现候选 7,316 行，组标题 2,837 行，占位 887 行，尚不能可靠归类 60,633 行。该候选统计只用于规划；关键词不足以支撑把全部入口自动重新归类，且不包含 57 个宽松数组和 120 个 JS 目录的运行结果。

## 下一阶段：发现与分类

| 书源字段或入口 | 产品去向 | 处理要求 |
| --- | --- | --- |
| 明确 `homepageModules` | 发现 | 优先采用作者声明；预览分开加载与报错 |
| 最新、更新、新书、热榜、排行、推荐、畅销 | 发现候选 | 保留原 URL、分页和筛选参数；没有请求不能伪造书籍卡片 |
| 玄幻、都市、科幻、同人、题材、标签、类别 | 分类 | 分组目录；用户点击后加载书单 |
| 非空标题、空 URL | 组标题 | 保留分组上下文，不能自动请求 |
| `exploreScreen` 的 text/select/toggle/button | 对应页面或结果页控件 | 保留 `infoMap` 键和作用范围，不作为类别导航 |
| `ruleExplore.kind` / `ruleSearch.kind` | 单本书的标签 | 不据此创建无 URL 的可点击分类 |
| `bookSourceGroup` | 书源管理 | 这是书源分组，不是小说分类 |
| 没有 `exploreUrl` | 仅展示已有能力 | 可搜索源在搜索中可用；不报“发现规则损坏”，不凭空生成首页 |

建议分两步实施：

1. 先给解析后的入口建立“发现 / 分类 / 待归类”用途，优先级为用户设置、作者明确声明、结合组标题的确定规则、待归类。把“入口用途设置”放在书源设置中；保存书源身份与稳定入口 ID，更新书源后仍需校验目标是否存在。相同名称的两个入口不能被合并。
2. 发现默认加载少量用户选定或明确识别的最新/榜单入口，按可见性加载后续预览；分类用紧凑分组展示题材和标签。待归类入口保留在“全部入口”，用户始终可达。只有类别而没有推荐入口的源应提供分类/搜索跳转，无需为它编造首页。

验收使用 QQ 的 353 行、番茄的 366 行、黑岩的 65 行，以及动态目录、占位、无名入口、只有搜索的源。检查两个 tab 不丢目标、不跨书源、保留筛选与分页；一个榜单失败不影响其他入口；原生 Wenku8 单独回归。本轮不重写原始 JSON、不按含糊关键词自动隐藏类别。

## MuMu 诊断方法

`DiscoveryLiveInstrumentedTest` 接受本地 `files/discovery-audit-input.json`，其中每项包含完整 `source`、显式 `origins` 和可选 `keyword`。仅在 `-e liveDiscovery true` 时运行。经真实 importer、安装源注册、Android isolated worker 和发现 adapter，依次记录 search、catalog、feed 和前两个有效入口。输出为 app 私有目录下的 `discovery-audit-results.json`。`-e discoveryAction install` 仅导入，方便继续 UI 操作。

输入和 grants 仅在本地提供，不随仓库提交。已安装源的权限只合并输入指定的目标；不能自动授权错误返回的未知地址。报告只包含阶段、数量、耗时和结构化错误；debug `RuleSourceTrace` 记录脱敏执行元数据，不包含响应正文或 Cookie。runner 的 `OK (1 test)` 只说明诊断执行完，不等于被测源全部通过。

参考阅读 App 使用原始定义和同一入口执行 `bookSourceDebug`；发现 key 使用 `audit::<原入口>`，检测到书单完成即停止，避免把章节故障混进发现结论。临时写入的参考定义在测试后恢复。设备与参考 App 的 Cookie、UA、IP 环境不能视为完全相同，因此先用固定页面回归定位解释器差异，再用真实站点验证结果。

## #198 CI

run `34846409502` 原 API 24 job 在安装阶段发生 `ShellCommandUnresponsiveException` / `Failed to install-write all apks`，执行 **0 个测试**；JVM 与 API 35 成功。相同提交的失败 job 重跑成功，整组转绿。没有证据把该次错误归因于规则解释器。

本轮在模拟器启动前构建两个 APK，并停止编译 daemon；连接测试仍执行原有测试集合。新增退出时采集 logcat、包管理和磁盘信息，保留失败退出码，以便下一次安装失败有原始证据。该调整减少同时构建与运行模拟器的资源争用，不能宣称彻底消除基础设施抖动。

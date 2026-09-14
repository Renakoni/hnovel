# hlib 首页、分类与账号判断

本轮修正对应 #191，依赖原生浏览器 #187、前台自动恢复 #188 和请求节奏 #190。

## 已定位的问题

旧原生源的搜索声明 webView:true，exploreUrl 的标签请求却是普通 java.connect；后者属于 API/HTTP，不共享 Chromium 账号状态。旧 hlibVerifiedResponse 以导航栏 /login 链接且没有 /logout 链接判断登录失效，即使公共页已成功返回也会尝试打开登录窗口。脚本交互被拒绝后，发现页统一显示需要登录。

首页与分类都先完整执行 exploreUrl，因此标签失败也挡住榜单和文章。旧隔离分支的 homepageModules 映射未进入当前生产构建，且即使只映射输出，仍无法消除这项请求依赖。

## 当前契约

- 原生 hlib 源删除重复的 loginCheckJs/jsLib 登录判定。宿主原生浏览器识别 CF、/antibot 与 /login 的密码表单；导航栏登录链接不会阻止读取。网站的实际访问政策仍由网站决定。
- 标签 java.connect 显式声明 webView:true。搜索、分类、榜单、文章、详情与阅读沿用该来源账号的 Chromium profile；不复制桌面或参考 App Cookie。
- homepageModules 的直接 url 可独立生成首页，exploreScreen 提供排序控件，exploreUrl 专门列出远程标签与翻页按钮。kindTitle 旧形式仍需要从 exploreUrl 解析，普通来源没有该扩展时行为不变。
- DiscoveryProvider.homepageCatalog 默认调用 catalog；规则适配器提供独立首页求值。首页模块直接携带目标且不冒充分类 ID，结果页以原目标和独立首页控件执行；实际分类仍按分类 ID 查找与绑定。
- 榜单周期、文章排序留在目标表达式中，改变筛选后重新求值；不能提前将默认选项固定进链接。
- 发现与搜索的验证失败使用 VerificationRequired，真正账号登录才使用 AuthenticationRequired；失败定位仍保留规则字段。

## 验收

中性 fixture 直接加载发布的 hlib-native.json，验证公共页登录链接、所有读取使用浏览器、标签分页、排序表达式与标签失败后首页可用。适配器测试覆盖首页/分类隔离、kindTitle 兼容、分类错误与登录错误区别。

设备测试使用原生 WebView 分别访问公共页、密码登录页、站内验证页；前台恢复使用自有站点，只在真实验证窗口可见后放行，并验证自动返回原搜索。实站结果与当前 APK 记录在统一入口，未完成的项目不以中性测试代替。

源更新通过生产 importer 与 SourceRevisionUpdates，保留来源身份和账号代次。liveHlib 的 update 动作必须带 hlibExpectedDigest，与当前定义不一致即拒绝覆盖。

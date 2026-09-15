# 退出登录与网页数据保留

本项对应 [#218](https://github.com/Renakoni/hnovel/issues/218)，延续[账号与本地内容语义](../docs/account-switch-semantics.md)。2026-09-15 确认的边界：只保留书源明确声明的通用网页数据，退出时清理未知网页数据，不新增用户设置。重新登录先重置旧账号环境，再打开登录页；继续已有网站验证保留其所属会话。

## 数据边界

| 数据 | 退出／重新登录时的处理 |
| --- | --- |
| 书架、目录、正文、图片、阅读记录与进度 | 保留，继续使用既有来源／书籍归属 |
| `source.put/get`、来源变量、规则缓存 | 保留，不按键名猜测其用途 |
| LoginInfo、LoginHeader、HTTP Cookie、登录事实 | 撤销旧账号并清理 |
| 原生 Cookie、未声明的 localStorage、IndexedDB、CacheStorage、Service Worker 等 | 随旧网页账号环境清理 |
| 明确声明的通用 localStorage 键 | 在同一来源、App profile 和 origin 内保留 |

普通浏览、关闭网页、继续验证和网络模式切换不会触发账号清理。网站自己的“退出”按钮由网站实现，不等于宿主的“退出登录”。

账号环境重置不改变既有 WebView UA、原生 API、frame/Worker 环境和真实布局，也不新增自动化标记或网页特权桥。来源安装标识仍使用既有通用存储。网站自己保存在 Cookie／未知网页存储中的标识会随旧会话清理；这与保持浏览器环境一致是不同的数据层次。

## 最小书源声明

`preserveLocalStorage` 是 HNovel 扩展，不是 Legado 的标准字段；不改变 `browserRead` 或 URL `webView` 的选择规则。

```json
{
  "bookSourceUrl": "https://example.org",
  "browserRead": true,
  "preserveLocalStorage": {
    "https://example.org": ["appearance", "fontSize"]
  }
}
```

省略、`null` 或空对象表示不跨账号保留网页数据。声明只接受精确 HTTP(S) origin 和字符串键，不执行脚本，不支持域名通配符、路径、查询参数或凭据。origin 规范化后去重，默认端口与显式端口视为同一 origin。声明本身不授予权限，只处理当前书源已获授权的 origin；来源修订改变声明或授权后，恢复时重新取交集。

限制为每个来源最多 8 个 origin、总计 64 个键；键非空、最多 128 个 UTF-16 单元且无控制字符。每个值最多 16 Ki UTF-16 单元，origin、键和值总计最多 64 Ki UTF-16 单元。值原样保留，不做业务类型推断。超出预算会报告存储失败，不能通过整体复制 profile 回退。

书源作者负责只声明通用偏好。Android 无法证明任意字符串一定不是令牌：如果作者把认证数据声明为通用数据，该值也会被保留。这是显式协议的责任边界；宿主负责执行来源、origin、键、大小和账号代次约束。

## 原生生命周期

1. 先持久化新账号代次、撤销旧执行权限并停止旧网页进程。迟到结果不能提交旧 Cookie 或登录状态。
2. 通过专用进程的受控离线页面读取声明键。禁用网络、文件、content 和 Service Worker 网络访问，不打开真实网站，不直接操作 Chromium 数据库。
3. 将有界快照原子保存为同一来源下一账号的待恢复数据，结束读取进程，再删除旧账号 profile。快照失败仍执行旧账号清理并报告失败；专用账户文件清理失败也继续尝试 Cookie 和网页清理。
4. 新账号首次原生导航前，仅在受控页面内恢复当前声明和授权仍允许的键，然后打开网站。新账号不会导入旧 Cookie 或其他网页数据库。
5. 待恢复记录包含账号代次。正常文档完成后移除快照内容，保留代次标记；重复旧账号清理不能覆盖新账号数据。尚未完成首次导航时取消，保留原快照供重试；直接再次退出也可把这份基线交给下一账号。

如果在快照持久化后、旧 profile 清理前进程结束，下次恢复会先重试旧 profile 清理。没有成功保存快照的中断不能承诺保留偏好，但新账号仍使用独立的干净 profile。文件切换或进程停止失败会报告错误，不操作仍在使用的目录。

WebView 没有通用 localStorage 落盘确认接口。已完成网页任务建立的数据是保留基线；刚写入即取消或杀进程的网页修改仍可能丢失，不以固定等待时间宣称其持久性。待恢复文件与 WebView profile 位于应用私有、排除备份的目录；不把它们宣称为经过账号存储的 Keystore/AES-GCM 加密。

## 参考项目实际行为

核对本地 `E:\H-novel\ref\legado-with-MD3`，提交 `fb01a76ebbbca41423e2c4c00080cc0861239fbd`：

| 操作 | 参考项目代码与行为 |
| --- | --- |
| 登录确认／离开登录页 | `ui/login/SourceLoginViewModel.kt` 的 `confirm`、`finish`、`saveCookie` 保存登录表单或 Cookie，不删除网页 profile |
| 删除登录头 | `data/entities/BaseSource.kt:158` 删除 LoginHeader，并调用 `CookieStore.removeCookie(getKey())`；不清 localStorage |
| 删除表单登录信息 | `BaseSource.kt:232` 只删除 `userInfo_...`，与 LoginHeader、通用来源变量分别处理 |
| 清 Cookie | `help/http/CookieStore.kt:79` 删除对应域的 Cookie 缓存／数据库记录，并调用 WebView Cookie 删除辅助方法 |
| 清除 WebView 数据 | 设置中的独立动作，`help/webView/WebViewDataCleaner.kt` 清所有 Cookie、WebStorage、表单／HTTP 认证及 WebView 目录 |

因此参考项目平常保留网页数据，但不能概括为任何操作都保留所有数据。它也没有统一的“清 Cookie 后所有认证状态失效”保证；网站放在 localStorage 等位置的令牌可能仍在。这里借鉴其通用来源数据与登录数据分开管理的语义，使用本项目已有的账号撤销和 profile 隔离完成重置，再补上显式偏好保留。

## 验证

2026-09-15，基线 `4da48d9d`：

- 先在真实 WebView 复现外观偏好退出后从 `dark` 变为 `null`，实现后相同用例通过。另先复现账号文件清理失败跳过浏览器清理，再验证整改。
- 相关 JVM 测试共 699 项通过：source-network 61、source-import 28、source-content 120、app 490；0 失败、错误或跳过。debug 与 androidTest APK 构建通过。
- API 35 / WebView `124.0.6367.219` 整组回归：26 项通过，5 项外部条件用例按参数跳过。包含新增存储 7 项、既有原生浏览器 12 项、路由 4 项、原生环境 1 项、验证恢复 1 项和账号加密隔离 1 项。
- 存储 fixture 同时建立声明的偏好、普通命名的认证值、IndexedDB 数据和 HttpOnly Cookie。核对清理期间无网站请求，新账号只得到允许的偏好；UA、原生 webdriver 状态和无特权桥保持。另覆盖同 origin 两来源隔离、授权与声明变化、重复清理、首次导航取消、配额和原子文件写入失败。
- 原生环境用例核对前后台、跨站 iframe/fetch/Worker 的 UA、原生接口与 Cookie 行为。受控 fixture 证明这些生命周期与隔离语义，不代表任意网站都会接受新会话。

相关入口：[基础设置](SOURCE_BASIC_SETTINGS.md)、[登录状态与恢复](SOURCE_LOGIN_STATE.md)、[原生浏览器](browser/README.md)。

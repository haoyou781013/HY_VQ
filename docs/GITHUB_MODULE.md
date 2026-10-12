# GitHub 模块（API 客户端形态）

> 2026-10-12 实测驱动。不是网页版，是 API 客户端。

## 为什么不做成网页版

真实大陆网络（上海移动 AS9808）实测：

| 端点 | 成功率 | 用途 |
|---|---|---|
| `github.com` | **1/5** | 网页主站 —— 被间歇掐断 |
| `api.github.com` | **5/5** | API —— 本模块只走这里 |
| `codeload.github.com` | 5/5 | 仓库 zip/tar 下载 |
| `raw.githubusercontent.com` | 4/5 | 文件原文 |
| `objects.githubusercontent.com` | 4/5 | Release 资产 |
| `gist.github.com` | 0/5 | Gist |

**这就是 GitHub 官方 App 在国内稳定的原因**：它是 API 客户端，只访问从未被掐断的
`api.github.com`，不加载 HTML。本模块走同一条路线，因此**不需要任何代理**。

## 为什么不用公共代理

12 个候选实测（见记忆库），结论是**没有一个能代理 HTML 页面**：

| 服务 | 实测结果 |
|---|---|
| `gh-proxy.com` | `/login` → 404，返回**自家品牌页**，无任何 GitHub 表单 |
| `ghproxy.net` | `/login` → 403；首页 JS 挑战后 302 到 **http://**（混合内容被拦） |
| `ghproxy.cn` | **域名已出售** |
| `ghproxy.co` / `.link` | 停放域名 |
| `ghfast.top` | TLS 握手全失败 |
| `ghproxy.cc` | 连接全失败 |
| `hub.gitmirror.com` | NXDOMAIN（已死） |
| `gitclone.com` | 502 |
| `gh-proxy.now.sh` | 308 循环（前缀形式损坏） |
| `kgithub.com` / `bgithub.xyz` | DNS 指向 Facebook IPv6（**GFW 污染**） |

它们本质是**文件下载加速网关**（Release/Raw/Archive/Gist），不代理网页。

**红线**：公共反代不能当登录入口 —— 凭据会流经不受你控制的第三方主机。
Cookie 归属由响应 host 决定，经代理登录则 Cookie 绑到代理域名，
`github.com` 永远拿不到会话。

## 为什么不用 Edge 的登录态

Android 沙箱决定：Edge 的 Cookie 在 `/data/data/com.microsoft.emmx/`，
其他 App **无任何 API 可读**。`CookieManager` 只管本 App；
`CookieManagerCompat`（实测源码）只有一个只读方法 `getCookieInfo()`，
也只读本 App。**除非 root，否则不可行。**

## 登录方式

**OAuth Device Flow**（`GhDeviceFlow`）：
1. App 向 `github.com/login/device/code` 申请设备码 → 拿一次性短码（如 `WDJB-MJHT`）
2. 用户在**任意浏览器**（Edge 也行）打开 `github.com/login/device` 输入短码授权
3. App 轮询 `login/oauth/access_token` 换 token
4. 之后所有请求 `Authorization: Bearer`，**完全不依赖 Cookie**

> ⚠️ **需先注册 OAuth App**（一次性，约 1 分钟）：
> Settings → Developer settings → OAuth Apps → New OAuth App，
> 名称填 `HY_VQ`，Homepage 与 callback URL 都填 `https://github.com`。
> 然后把 **Client ID** 填到 `GhDeviceFlow.CLIENT_ID`。
>
> 未填时该入口自动隐藏，可改用「粘贴 Token」（Personal Access Token）。

未登录也能用：游客 **60 次/小时**，登录后 **5000 次/小时**。

## 下载：断点续传（基础版）

实测 `codeload` 拉 269MB 的 tar.gz，单次连接收到 100~200KB 就被掐断
（`Content-Length` 声明 269MB）。所以**断点续传是必需项**。

Range 支持实测（判据是 `Content-Range` 头存在）：

| 端点 | 结果 | 分段 |
|---|---|---|
| `raw.githubusercontent.com` | 206 `accept-ranges: bytes` | ✅ |
| `api.github.com` | 206 `accept-ranges: bytes` | ✅ |
| `codeload.github.com` | 200，**无 Content-Range** | ❌ |

⚠️ 排查坑：曾误判"两次请求内容不同 = 分段生效"，实际是**流被掐断**。
判断分段必须看 `Content-Range` 头，不能靠内容比较。

**当前实现**：单源 + 断点续传（`.part` 文件记进度，`Range: bytes=N-` 接上）。
**多源轮换**（raw / jsDelivr / 快链互为冗余）后续再加。

## 文件

| 文件 | 职责 |
|---|---|
| `github/GhApi.java` | API 客户端：分页、限流提示、断点续传下载 |
| `github/GhDeviceFlow.java` | Device Flow 三步 + token 存取 + PAT |
| `module/impl/GithubModule.java` | 模块 UI：账号/仓库/搜索/文件树/README |

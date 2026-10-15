# 客户端更新源顺序

> 用户指定 2026-10-12（国内优先）。实现见 `MainActivity.java`。

## 取版本列表（fetchReleases）

| 优先级 | 源 | 常量 | 凭据 |
|---|---|---|---|
| ① | **Gitee Releases**（国内主源） | `GITEE_RELEASES` | 公开仓库免鉴权 |
| ② | **GitHub 加速镜像** | `GH_MIRRORS` = `gh-proxy.com` / `ghproxy.net` | 无 |
| ③ | **GitHub 直连** | `REMOTE_RELEASES` | 无 |
| ④ | **123 云盘 WebDAV**（离线兜底） | `CN_BASE` + 内置只读凭据 | Basic 只读 |

Gitee 与 GitHub 的 Releases API **响应结构一致**
（`tag_name` / `body` / `published_at` / `assets[].browser_download_url`），
故 `fetchReleasesFrom(url, accept)` 一段解析逻辑通用。

**Gitee 已启用**（2026-10-16）：账号 `haoyou781`，仓库 `gitee.com/haoyou781/hy_vq`。
此前 `GITEE_USER` 写的是不存在的 `haoyou781013`（404），该源从未生效；
改为真实账号后 ① 成为可用主源，请求失败时仍会自动落到②。

## 下载 APK（testAllSources + 兜底重试）

下载源列表与取版本同序，但**最终顺序由测速决定**（TTFB + 速度，快��先用）。

规则：`apkUrl` 若来自 Gitee（host 含 `gitee.com`）→ **不套 GitHub 镜像前缀**，
直接用 Gitee 直链；否则套镜像 + GitHub 直链。123云盘始终作为最后兜底。

理由：GitHub 镜像前缀只对 `github.com` / `objects.githubusercontent.com` 有意义
（见 `mirrorUrl()`），套在 Gitee 上会得到无效 URL。

## 发布端对应顺序

`tools/release.sh`：① Gitee → ② GitHub 镜像 → ③ 123云盘 → ④ GitHub 更新源。

> 发布到 Gitee 用 `tools/gitee-release.sh <版本> <APK> [changelog]`：
> 创建 Release + 上传 APK 附件 + 更新仓库 raw 的 `versions.json`（`tools/release.sh` 的 ① 步即调用它）。

> 注意：④ 在发布脚本里是「确认客户端可见」的步骤，不是新的上传动作。

# HY_VQ

> ## 📦 仓库已迁移
>
> 项目现维护于 **<https://github.com/haoyou781013/HY_VQ>**（当前仓库）。
> 旧地址 <https://github.com/haoyou999/HY_VQ> 因账号双重验证密钥丢失已冻结为只读，
> 但其 raw 直链与已发布的 Release 资产**继续有效**。
> 迁移原因、对使用者的影响与事故教训详见 **[MIGRATION.md](MIGRATION.md)**。

> ## ⚠️ 重要声明
>
> **本项目的全部代码均由 AI 智能体（DeepSeek Harness 驱动的编码代理）编写**，
> 人类作者仅负责提出需求与验收结果。
>
> **AI 生成的代码不保证正确性、安全性、稳定性与适用性**，可能存在未发现的缺陷、
> 性能问题或安全隐患。本项目按「现状」提供，**不提供任何形式的担保**。
>
> 请在使用前自行审阅代码。因使用本项目而造成的任何直接或间接损失，由使用者自行承担。
>
> 若你无法接受上述条款，请不要使用本项目。
>
> 详见 [DISCLAIMER.md](DISCLAIMER.md)

---

> 极简高效的 Android 文件管理与连接工具

HY_VQ 是一个面向 Android 的文件管理器，采用「单列混排 + 双窗格独立浏览」的交互，
支持 Root / SAF / 包管理三套存储访问策略，并内置代码编辑器、压缩解压、回收站等常用能力。
应用本身以「外壳 + 模块」架构组织，文件管理为内置一级功能，其余能力以可插拔模块形式扩展。

## 功能特性

### 文件管理
- **双窗格独立浏览**：左右窗格各自维护路径、多选与滚动位置；**选中集合按窗格独立**，活动窗格由点击/长按/滚动自动切换
- **多存储源路由**：按路径自动选择数据源 —— SAF（`content://`）/ Root（已授权时优先，绕过 scoped storage）/ 包管理（`PackageManager` 还原 `/data/app`）/ 存储卷枚举（`StorageManager`）/ 原生 `File`；Root 未授权时以虚拟白名单层补齐 `/`、`/data`、`/storage` 等系统目录，效果对齐 MT 管理器
- **书签与快速访问**：侧边栏收藏目录、按扩展名归类的分类入口、回收站
- **目录缓存**：按「路径 → 条目快照 + 目录指纹（mtime / 子项数）」缓存，大目录切换显著加速
- **滚动位置记忆**：缓存 `RecyclerView` 布局状态（含像素偏移）；**仅在「从后台返回同一目录」时恢复**，普通目录切换一律从顶部开始，避免跳动
- **内置代码编辑器**：多标签页、查找替换（支持正则）、跳转行、编码（UTF-8 / GBK）与行尾符（LF / CRLF）切换、撤销重做、Markdown 预览、文件树抽屉、字号 / 等宽字体 / 主题偏好
- **压缩与解压**：**zip**（`java.util.zip`，零依赖）压缩与解压，含 Windows GBK 文件名修复与路径穿越拦截；rar / 7z / tar 等仅做分类归组与图标识别，**不支持解压**
- **FTP 服务器**：局域网内用电脑访问手机文件，用户名 `hyvq` + **每次启动随机生成的一次性密码**（未登录拒绝一切文件操作）
- **Root 高级操作**：分区 remount 读写、chmod（数字模式 + 预设 + 递归）、chown、属性查看
- **状态持久化**：排序方式、显示隐藏文件、书签、编辑器标签跨重启保留；**左右窗格目录与活动窗格刻意不持久化**（重启固定回到内部存储根，同实例后台返回才原样保留）

### 其他
- **插件系统**：以标准 zip（`module.json` + `classes.dex`）分发的外部模块，经 `DexClassLoader` 动态加载
- **在线更新**：默认从 GitHub Releases API 读取版本列表（精确直链），连不上时**自动回退国内备用源**（123 云盘 WebDAV），下载 APK 并校验 MD5 后交由系统安装器
- **内置查看器**：图片查看器（双指缩放 / 拖动 / 双击还原）与音视频播放器（含上下切换），不依赖第三方应用
- **局域网友好**：`AccessRouter` 路由与 FTP 均按「WiFi → 热点 → 移动数据」优先级挑选对外 IP，自动排除 `vgate0` 等虚拟网卡
- **界面**：Material 3 + **玻璃拟态**（glass panel + 1dp 描边 + 背景模糊）；主色固定浅蓝 `#42A5F5`，`colors.xml` 另含 8 套预留色板（当前仅启用 Blue）

## 系统要求

| 项 | 版本 |
|---|---|
| 最低 Android | 9 (API 28) |
| 目标 Android | 13 (API 33) |
| 编译 SDK | 33 |
| AGP | 8.0.0 |
| Java | 11 |

## 构建

```bash
# 需先准备 Android SDK（platforms/android-33），并在 local.properties 指明路径
echo "sdk.dir=/path/to/android-sdk" > local.properties

sh gradlew :app:assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

> 在部分受限环境（如 Termux）中，因 FUSE 文件系统限制需用 `sh gradlew` 而非 `./gradlew`。

## 项目结构

```
app/src/main/java/com/aliya/hy_vq/
├── MainActivity.java          外壳：首页 / 侧边栏 / 设置 / 更新中心 / 内嵌页（约 3000 行）
├── LoginActivity.java         登录页（本地身份初始化）
├── SignatureManager.java      身份核心（PBKDF2-HMAC-SHA256 / 12 万轮 / uid / uuid）
├── CredentialStore.java       Android Keystore AES-256-GCM 凭据加密存储
├── ImageViewerActivity.java   内置图片查看器
├── MediaPlayerActivity.java   内置音视频播放器
├── ShareAppsView.java         实用软件分享（内嵌视图）
├── WuwaEmojiView.java         鸣潮表情包（内嵌视图）
├── ThemeHelper.java           主题叠加（固定浅蓝 ThemeOverlay）
├── module/                    模块框架（HyVqModule / ModuleRegistry / ModuleServices / ModuleUiKit / HyVqTheme）
│   └── impl/
│       └── FileManagerModule  文件管理（内置一级功能，约 6900 行）
├── filemgr/                   文件列表适配器与文件操作
├── access/                    四源无感路由（AccessRouter / SafStrategy / RootFs / RootShell
│                              / RootVirtualStrategy / PkgStrategy / StorageStrategy）
├── terracotta/                房间联机（HyVqP2pBridge + punch/ 打洞套件）
└── update/                    更新管理（UpdateManager / HyVqAppEntry）

tools/                         签名、审计、校验与打包脚本
plugins/                       外部模块示例（B 站 OAuth 插件）
modules/                       模块模板
libs/terracotta/               Terracotta P2P 运行库归档（不参与构建，APK 已不再打包）
```

## 更新源

本仓库**同时充当软件更新源**（不再单独维护更新仓库）：

| 用途 | 地址 |
|---|---|
| 版本清单 | `https://raw.githubusercontent.com/haoyou781013/HY_VQ/main/latest.json` |
| 安装包 | `https://github.com/haoyou781013/HY_VQ/releases/latest/download/<apk 文件名>` |
| 国内备用源 | `https://webdav.123pan.cn/webdav/HY_VQ-updates/`（123 云盘，内置只读凭据） |

更新机制：**默认走 GitHub**，连接失败时自动回退到国内备用源。
国内源同时保存 `versions.json`（全量版本历史）与各版本 APK，作为 GitHub 不可达时的完整替代。

公开仓库的 raw 文件与 Release 资产均为**无鉴权直链**，因此客户端 APK 内不含任何账号或密钥。
应用内「设置 → 软件更新」即按上述地址检查新版本、下载 APK 并校验 MD5 后交由系统安装器安装。

## 开源许可

本项目以 **GNU General Public License v3.0** 发布，详见 [LICENSE](LICENSE)。

**代码由 AI 编写，不保证质量** —— 详见 [DISCLAIMER.md](DISCLAIMER.md)。

## 致谢

本项目的交互与实现参考了以下优秀的开源项目：

- [Material Files](https://github.com/zhanghai/MaterialFiles) —— 目录滚动位置记忆的设计思路
- [ZhuFiler](https://github.com/) —— 主题叠加与目录缓存的实现参考
- [Terracotta](https://github.com/bmax121/Terracotta) —— 局域网 P2P 直连
- [Material Components for Android](https://github.com/material-components/material-components-android) —— Material 3 组件
- [AndroidX](https://developer.android.com/jetpack/androidx) —— 基础支持库

# 增量更新包发布规范

> 适用版本：v2.33.0 起 · 容器（proot）环境

## 一、这是什么东西

**不是二进制差分（bsdiff）**，而是「只发 `classes.dex` 的整包替换」。

- 优点：实现简单、无需维护双版本编译器、崩溃可直接回滚
- 代价：包体 = 更新代码全量（示例 2.3 KB）。更新代码越多，包越大
- 适用：更新包只承载**少量增量 UI/逻辑**（横幅、扩展页）；主体功能仍走整包更新

## 二、包格式

```
hyv_update_v{N}.zip
├── manifest.json   签名与元数据
└── classes.dex     全部更新代码（须实现 HyVqAppEntry）
```

### manifest.json 字段

| 字段 | 说明 |
|---|---|
| `format` | 固定 `hyv-update` |
| `versionCode` | 更新包版本号（整数） |
| `versionName` | 展示用版本名 |
| `minBaseVersion` | 能应用此包的**最低壳 versionCode** |
| `entry` | 入口类全名（须 `implements HyVqAppEntry`） |
| `dexMd5` | `classes.dex` 的 MD5 —— 改动这个字段即验签失效 |
| `signature` | RSA 签名（base64），由 SignTool 注入 |
| `sigAlg` | `SHA256withRSA` |

## 三、签名机制（安全核心）

### 签名覆盖范围

签名的是 **canonical 串**，不是 dex 全文：

```
format|versionCode|versionName|minBaseVersion|entry|dexMd5
```

因为 `dexMd5` 已覆盖 dex 全文 MD5，所以：

- **改 dex** → `dexMd5` 对不上 → 验签失败 ✅
- **改 versionCode / entry / minBaseVersion** → canonical 变了 → 验签失败 ✅
- **改 dex 同时改 dexMd5** → canonical 变了 → 验签失败 ✅

### 公钥（过渡期双公钥）

`UpdateManager` 内置两把公钥，**release 优先、失败回退 legacy**：

| 名称 | 位长 | 来源 | 用途 |
|---|---|---|---|
| `PUBKEY_B64_RELEASE` | 4096 | `keystore/hyvq-release.jks` | **正式签名用这把** |
| `PUBKEY_B64_LEGACY_DEV` | 2048 | 历史遗留（无配套私钥） | 仅兼容老壳 |

> **为什么有两把**：v2.33.0 之前壳里只有 legacy dev 公钥，而实际发布签名用的是 release keystore
> —— 两者不是同一对密钥，导致 release 签的包**一律被拒**。v2.33.0 起改为双公钥过渡。
>
> **收尾动作**：等最低支持壳版本 ≥ 104 后，删除 `PUBKEY_B64_LEGACY_DEV`，
> `verifySignature` 改回单公钥。届时本节可删除。

## 四、打包流程

```bash
cd /root/HY_VQ

# 前置：壳必须先 build 过（更新包要编译壳的接口类 HyVqAppEntry）
/opt/build-hyvq.sh :app:assembleRelease

SIGN_JKS=/root/keys/hyvq-release.jks \
SIGN_PASS='<keystore 密码>' \
SIGN_ALIAS=hyvq \
tools/make_update_container.sh \
    <更新包源码目录> <versionCode> <versionName> <入口类全名> [输出目录]
```

产物：`update_packages/hyv_update_v{N}_{versionName}.zip`

### 打包脚本内部 5 步

1. `javac` —— **classpath 必须含壳产物**
   `app/build/intermediates/javac/release/classes`（否则编译不过接口）
2. `d8 --min-api 28` —— 只打本次编译产物
3. 生成 `manifest.json`（含 `dexMd5`）
4. `SignTool` 签名 —— **先 javac 再 java**（SignTool 是 `.java` 源码）
   参数序：`<jks> <storepass> <alias> <manifest.json> <classes.dex>`
5. zip 打包（`manifest.json` + `classes.dex`）

## 五、入口类契约

```java
public class YourEntry implements HyVqAppEntry {
    @Override public void onAttach(Context ctx) { }          // 挂载回调
    @Override public String getName() { return "…"; }         // 包名
    @Override public String getVersion() { return "…"; }      // 版本名
    @Override public View createHomeBanner(Context ctx) {     // 首页横幅，可返 null
        return null;
    }
    // createSettingsExtension 为 default 方法，可不实现
}
```

## 六、壳侧可靠性设计

| 机制 | 行为 |
|---|---|
| 原子切换 | 先解压到 `v{N}.partial`，完成后原子改名 → 中断不留半成品 |
| 回滚保护 | **先写指针、后删旧包** → 任一步失败仍指向可用的旧版本 |
| 双保险清理 | 安装后立即清理 + 启动时兜底清理 |
| 启动自愈 | 指针指向的包缺失 → 自动重置 |
| Zip Slip 防护 | 条目白名单（仅 `manifest.json` + `classes.dex`），其余**直接丢弃** |
| 强制验签 | 无签名或验签失败 → 拒绝安装 |

## 七、测试用例（发包前必跑）

用 `/root/research/verify_tests/DualTest.java`（与壳 `verifySignature` 同逻辑）：

```bash
cd /root/research/verify_tests
javac DualTest.java && java DualTest
```

| 用例 | 期望 | 拦截层 |
|---|---|---|
| 正牌包（release 签） | ✅ 通过 | — |
| 篡改 dex | ✅ 签名层放行 | **dexMd5 拦截** |
| 伪造 versionCode | ❌ **拒绝** | canonical 验签 |
| 无签名 | ❌ **拒绝** | 强制验签 |
| Zip Slip 藏匿文件 | ✅ 签名层放行 | **条目白名单丢弃** |

> 注意区分「签名层」与「完整性层」：签名只覆盖 manifest 字段，dex 内容由 `dexMd5` 兜底。

## 八、发布检查清单

- [ ] `minBaseVersion` ≤ 目标用户当前壳版本
- [ ] 壳已 build（提供 classpath）
- [ ] `DualTest` 全绿
- [ ] 包已上传 Release 资产 **与** 123云盘
- [ ] `update_packages/` 已在 `.gitignore`（不入库）
- [ ] 密钥密码未进仓库、未进日志

## 九、常见坑

| 坑 | 表现 | 解法 |
|---|---|---|
| 壳未 build | 更新包编译不过 `HyVqAppEntry` | 先 `assembleRelease` |
| SignTool 未编译 | `ClassNotFoundException` | 脚本已处理（先 `javac`） |
| jks 参数顺序写反 | 签名失败 | `<jks> <pass> <alias> <manifest> <dex>` |
| 缺 `minBaseVersion` | canonical 不一致 → 验签失败 | manifest 必须含该字段 |
| 用错 keystore | 正牌包被拒 | 过渡期 release 优先；确认壳内公钥 |
| `/tmp/canon.txt` 尾部换行 | openssl 验签失败 | canonical 文件**不能带尾换行**（JVM 侧无影响） |
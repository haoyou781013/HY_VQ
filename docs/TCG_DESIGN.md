# 七圣召唤（GITCG）内置到 HY_VQ · 设计规格

## 用户决策（2026-10-11）
- **范围：完整还原**（角色卡上场/攻击/被击败 + 元素附着反应 + 秘传牌）
- **形式：先同屏热座，预留联机接口**
- 挂在 **原神帮助 → 七圣召唤** 下，三个子页：
  1. **双人游戏**（同屏热座，AI 不可用，两人轮流）
  2. **玩法说明**（规则文档页）
  3. **卡片**（图鉴：全部卡牌 + 筛选）

## 目录结构（新包 com.aliya.hy_vq.tcg）
```
tcg/
  model/
    Element.java          火/水/风/雷/冰/草/岩/物理 共 8 种 + 无属性
    CardKind.java         ACTION(行动牌) / CHARACTER(角色牌) / NORMAL(中立牌) / SECRET(秘传)
    Card.java             抽象基类：id/名称/费用/元素/效果描述
    ActionCard.java       行动牌：主动用牌，有一次性效果
    CharacterCard.java    角色牌：可上场，血量，受击后撤退
    SecretCard.java       秘传牌：被动/常驻
    Player.java           玩家：生命/元素充能/牌库/手牌/场上角色
    GameState.java         全部对局状态（可序列化 → 预留联机）
  engine/
    CardDatabase.java     全部卡牌静态数据（代码内建，不打包图片，省体积）
    DeckBuilder.java      洗牌/发牌（用 Random，种子可复现 → 联机用）
    Phase.java            回合阶段机：DRAW/PLAY/COMBAT/DISCARD/END
    PlayValidator.java    出牌合法性：牌型/费用/管上/是否该回合可用
    ElementReaction.java  元素附着 + 7 种反应（火水/水火/雷水…）
    GameEngine.java       对局主控（无 UI 依赖，纯逻辑 → 可跑无头测试）
    Serialization.java    GameState ↔ JSON（联机预留，暂只存/读）
  ui/
    TcgGameView.java      同屏双人：上/下两个手牌区，点选操作
    TcgRulesView.java     玩法说明
    TcgCardsView.java     卡片图鉴 + 筛选栏
```

## 关键设计决策
1. **引擎与 UI 完全分离**：`GameEngine` 不 import 任何 android.view.*，可纯 JVM 测。
   → 这是「预留联机」的前提：将来接 socket 只需序列化 GameState 传输命令。
2. **卡牌数据写在代码里**（`CardDatabase`），不打包卡面图片 → 体积可控，
   图鉴页用**程序化绘制**（元素色块 + 名称 + 费用角标）表示卡面。
3. **同屏热座**：不区分"本地玩家/远端玩家"，按 `currentTurn` 高亮当前操作方，
   两边手牌都可见（热座规则）。联机时才需要隐藏对方手牌 → 引擎预留 `viewMask`。
4. **复用项目现有 UI 工具**：`ModuleUiKit.rounded/color`、`dp2()`、程序化 LinearLayout，
   与 `buildGenshinHelpView()` 同款风格。

## 页面挂载
- MainActivity 加 `PAGE_TCG_GAME=15` / `PAGE_TCG_RULES=16` / `PAGE_TCG_CARDS=17`
- `buildGenshinHelpView()` 增加「七圣召唤」入口卡 → 进 `TcgGameView`
- TcgGameView 内再给两个入口（玩法说明 / 卡片图鉴）→ 同一 MainActivity 切页

## 分工（一个代理一个文件，避免并行写冲突）
| 路 | 文件 | 依赖 |
|---|---|---|
| 1 | `model/*`（7 个文件）+ `model/Element.java` | 无 |
| 2 | `engine/CardDatabase.java` | model |
| 3 | `engine/DeckBuilder.java` + `Phase.java` | model |
| 4 | `engine/PlayValidator.java` | model + CardDatabase |
| 5 | `engine/ElementReaction.java` | model |
| 6 | `engine/GameEngine.java` | engine 2-5 |
| 7 | `ui/TcgCardsView.java` | model + CardDatabase |
| 8 | `ui/TcgRulesView.java` | 无（纯文档）|
| 9 | `ui/TcgGameView.java` | engine/GameEngine |
| 10 | MainActivity 接线 | 全部（我最后做）|

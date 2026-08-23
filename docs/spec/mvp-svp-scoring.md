# MVP/SVP 评选功能规格

## Problem Statement

当前对局同步系统只保存对战数据（比赛信息 + 时间线 + 参与者数据），但用户无法直观地看出"这场谁打得最好"。

不同英雄职责差异巨大：一个坦克的伤害必然低于 ADC，但他在团战中承受了大量伤害、控住了关键英雄；一个辅助做了大量视野、保住了队友，但 KDA 难看。如果用统一的伤害/KDA 公式评选，坦克、辅助几乎永远拿不到 MVP，评选结果缺乏说服力。

用户需要一个**按英雄职业差异化评分**的 MVP/SVP 评选：MVP 是胜方表现最佳选手，SVP 是负方表现最佳选手。

## Solution

对局同步写入后，立即按英雄职业（射手/法师/坦克/刺客/战士/辅助）动态调整评分维度权重，对每队 5 名选手做同队归一化（0-100），胜方得分最高者为 MVP、负方得分最高者为 SVP，结果存入独立 `match_mvp` 表。

- 英雄职业来源为新建的 `champion_class` 表（Flyway 初始化），以 Riot Data Dragon 的 champion tags 为基础数据
- 评分维度包括：伤害输出、KDA、经济转化、承伤能力、视野、治疗/护盾、控制时长、击杀参与率 等
- 不同职业侧重不同维度权重；刺客与战士共用"全能均衡"权重
- 大乱斗（ARAM）模式下，辅助的视野维度**完全去除**（权重为 0），因为该模式没有视野装备，视野分无意义
- 缺失职业映射时回退到全能均衡权重，不阻塞评分

## User Stories

1. 作为对局同步系统使用者，我希望保存一场对局后自动评选出胜方的 MVP 和负方的 SVP，以便知道每场最佳选手是谁
2. 作为用户，我希望 MVP/SVP 评选基于各英雄职业差异化评分，以便坦克承伤、辅助视野这类数据不被伤害/KDA 掩盖
3. 作为用户，我希望大乱斗模式下辅助的视野维度完全不参与评分，以便评选结果对极地大乱斗同样合理
4. 作为用户，我希望刺客和战士使用统一的"全能均衡"评分权重，以便按擅长方式（刺客爆发 vs 战士持续输出）各自表达
5. 作为用户，我希望看到 MVP/SVP 的评分构成明细（各维度原始值 + 归一化得分），以便理解"为什么是他"
6. 作为用户，我希望查询对局详情时能看到谁被评为 MVP/SVP，以便在复盘时定位核心选手
7. 作为开发者，我希望评分可以在对局同步时实时计算落库，以便查询接口零延迟
8. 作为开发者，我希望重复推送同一 gameId 时 MVP/SVP 不会重复写入，以便保持幂等性
9. 作为开发者，我希望英雄职业映射缺失时评选不崩溃，而是回退到均衡权重，以便新英雄上线不影响主流程
10. 作为开发者，我希望评分权重是配置化的常量，便于后续调参，以便不反复改评分引擎代码
11. 作为开发者，我希望为每个英雄职业补充中文注释，以便维护者理解权重含义

## Implementation Decisions

### 数据库变更（Flyway，新增 V4、V5）

**V4__champion_class.sql** — 新增 `champion_class` 表：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT UNSIGNED AUTO_INCREMENT PK | 主键 |
| champion_id | INT NOT NULL | 英雄 ID（幂等键） |
| class_name | VARCHAR(32) NOT NULL | 英雄职业分类：ADC/MAGE/TANK/ASSASSIN/FIGHTER/SUPPORT |
| created_at | DATETIME DEFAULT CURRENT_TIMESTAMP | 记录创建时间 |

唯一索引 `uk_champion_class_champion_id(champion_id)`。用 INSERT 初始化覆盖主流英雄（Data Dragon tags 提供基础数据），未覆盖的英雄在评分时回退到全能均衡权重。

**V5__match_mvp.sql** — 新增 `match_mvp` 表：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT UNSIGNED AUTO_INCREMENT PK | 主键 |
| match_id | BIGINT UNSIGNED NOT NULL | 所属对局（match.id 外键） |
| participant_id | BIGINT UNSIGNED NOT NULL | 获得称号的参与者的 ID（match_participant.id 外键） |
| type | VARCHAR(8) NOT NULL | 称号类型：MVP / SVP |
| score | DECIMAL(10,2) NOT NULL | 归一化总分 (0-100) |
| score_detail_json | JSON | 评分明细：`{ "维度名": { "raw": 原始值, "score": 归一化得分 } }` |
| created_at | DATETIME DEFAULT CURRENT_TIMESTAMP | 记录创建时间 |

联合唯一约束，保证每场对局每个称号只有一行：`UNIQUE uk_match_mvp(match_id, type)`。

### 模块划分

- `entity/MatchMvp.java`：`match_mvp` 实体
- `entity/ChampionClass.java`：`champion_class` 实体
- `mapper/MatchMvpMapper.java`、`mapper/ChampionClassMapper.java`：MyBatis-Plus BaseMapper
- `service/MvpScoringEngine.java`：**纯函数式评分引擎（无 HTTP、无数据库）**，输入为参与者表现数据列表 + 模式（CLASSIC/ARAM），输出为每个参与者的得分明细
- `service/MatchMvpService.java`：负责加载 `champion_class` 映射、调用 `MvpScoringEngine`、落库 `match_mvp`，整合数据库依赖
- `MatchService.saveMatch` 中插入所有参与者后，调用 `MatchMvpService` 进行评选

### 评分引擎设计（核心算法）

```
评分维度（Scoring Dimensions）：
  DPS     = 伤害输出（totalDamageDealtToChampions）
  KDA     = (kills + assists) / max(deaths, 1)
  GOLD    = 经济转化（goldEarned / gameDuration 分钟）
  TANK    = 承伤能力（totalDamageTaken）
  VISION  = 视野（visionScore + wardsPlaced）
  SUPPORT = 治疗/护盾（totalHeal + totalDamageShieldedOnTeammates）
  CC      = 控制时长（timeCCingOthers）
  CS      = 补刀/发育（totalMinionsKilled + neutralMinionsKilled）

职业权重表（配置文件或常量）：
  ADC     = { DPS: high, KDA: high, GOLD: high, 其余: low }
  MAGE    = { DPS: high, KDA: high, GOLD: high }
  TANK    = { TANK: high, CC: high, KDA: mid }
  ASSASSIN = { DPS: high, KDA: high, 万能均衡 }
  FIGHTER = 万能均衡（同 ASSASSIN）
  SUPPORT = { VISION: high, SUPPORT: high, CC: high, KDA: mid }
  UNKNOWN = 万能均衡（缺失映射时的回退）

大乱斗修正（ARAM）：
  ARAM 模式下，SUPPORT 职业的 VISION 权重 = 0（完全去除），
  因为嚎哭深渊无视野装备，视野分无意义。
```

```
归一化规则：
  每队 5 人，对每个维度分别做 0-100 线性归一化：
    score = (raw - min) / (max - min) * 100
  队内 max == min（全员一样）时，该维度所有人都得 100 分（避免除零）

总分规则：
  total = Σ(维度归一化得分 × 该维度权重) / Σ(权重)  —— 归一化后按加权平均
```

```
评选规则：
  胜方（win=true）5 人中 total 最高者 → MVP
  负方（win=false）5 人中 total 最高者 → SVP
  （平局时按 participant 顺序取先出现的，保证确定性）
```

### API 契约

不新增独立端点。在现有的对局详情响应 `MatchDetailResponse` 中扩展：

```json
{
  "data": {
    ...原有字段...,
    "mvp": {
      "participantPuuid": "xxx",
      "summonerName": "xxx",
      "championId": 123,
      "score": 92.5
    },
    "svp": {
      "participantPuuid": "yyy",
      "summonerName": "yyy",
      "championId": 456,
      "score": 85.0
    }
  }
}
```

查询队内每人得分明细（可选，进阶展示）：通过 `match_mvp.score_detail_json` 查表即可，无需新增维度。

### 幂等与并发

- `MatchMvpService` 落库前检查该 matchId 是否已有 MVP/SVP 数据（先查后插）
- `saveMatch` 外层 `@Transactional` 已保证参与者插入与 MVP/SVP 写入同事务
- 并发重复推送时，利用 `uk_match_mvp(match_id, type)` 唯一约束 + `DuplicateKeyException` 兜底（与 MatchService 现有并发模式一致）

## Testing Decisions

### 测试接缝

最高可测接缝：**`MvpScoringEngine`（纯函数）**。它无 HTTP、无数据库、无 Spring 上下文，最容易被充分测试。

次级接缝：`MatchMvpService`（依赖 Mock 的 Mapper）。

### 单元测试（核心评分逻辑）

`service/MvpScoringEngineTest`（纯 JUnit + AssertJ，不启动 Spring）：

1. **职业权重生效**：相同原始数据，射手（ADC）比坦克（TANK）在伤害上得分更高
2. **坦克承伤得分**：坦克大量承伤时，评分高于输出较低但承伤不足的射手
3. **辅助视野得分**：辅助高视野时，评分体现视野贡献
4. **大乱斗辅助视野去除**：ARAM 模式下，SUPPORT 职业 VISION 维度权重为 0，视野分完全不进入总分
5. **同队归一化**：队内最高者得 100，最低者得 0，中间者按线性插值
6. **全员相同数据**：某维度全员数值相同 → 该维度所有人 100 分（不除零）
7. **缺失职业映射回退**：未知 championId → 按 UNKNOWN（万能均衡）权重评分，不抛异常
8. **评选结果**：总得分最高者为 MVP/SVP，各队独立评选
9. **平局确定性**：得分相同时取先出现者

### 集成测试

扩展现有 `MatchServiceTest`（Mockito + `@InjectMocks`）：
- saveMatch 调用后，verify `matchMvpMapper.insert` 被调用 2 次（MVP + SVP）
- 校验插入的 participant_id 与预期一致
- 幂等：同 gameId 重复 saveMatch，`matchMvpMapper.insert` 不重复调用

（可选）`MatchControllerIntegrationTest` 扩展：真实写入一场对局后，断言详情响应中 `mvp`/`svp` 字段填充正确。

### 测试前代品（Prior Art）

- `MatchServiceTest`（`src/test/java/.../service/MatchServiceTest.java`）：Mockito mock 两个 Mapper + `@Spy ObjectMapper` 真实序列化，`verify` 断言插入行为
- `MatchControllerIntegrationTest`：`@SpringBootTest + @AutoConfigureMockMvc + @Transactional` 真实 MySQL，jsonPath 断言
- `GlobalExceptionHandlerTest`：standalone MockMvc 测试异常映射

## Out of Scope

- 历史对局的追溯评分（存量 match 数据不回填 MVP/SVP）（除非用户要求）
- MVP 排行榜 / 历史 MVP 统计聚合
- 前端页面展示（本项目为纯后端 API）
- AI 点评与 MVP/SVP 的关联（现有 AiAnalysisService 不参与）
- 手柄评分、分路（position）维度评分——只按英雄职业
- 添加英雄职业到 `champion_class` 的运行时维护接口（只通过 Flyway/代码管理）

## Further Notes

- 大乱斗模式判定：对局 `game_mode` 值为 `CHERRY`（现有 Match 实体的 gameMode 字段已确认存在该值）
- `stats_json` 中可直接读取维度原始值（totalDamageDealtToChampions / totalDamageTaken / goldEarned / totalMinionsKilled / visionScore / wardsPlaced / totalHeal / totalDamageShieldedOnTeammates / timeCCingOthers / doubleKills / tripleKills / quadraKills / pentaKills 等）
- 权重常量建议集中在 `MvpScoringEngine` 内以 `Map<ChampionClass, Map<Dimension, Double>>` 结构定义，后续调参只需改权重表
- TDD 红绿灯顺序：先写 `MvpScoringEngineTest`（红灯）→ 实现引擎（绿灯）→ 写 `MatchMvpService` → 接入 `saveMatch`
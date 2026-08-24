---
Feature: op-score-scoring
Status: ready-for-agent
Type: spec
---

# Op Score 评分系统改造

## Problem Statement

当前评分系统（MVP/SVP + 0-100 分）用同队 5 人 min-max 归一化，缺少外部参考系：一场菜鸡互啄的最高分也能拿 90+ 分，且不区分英雄之间的合理期望。用户希望复刻 op.gg 的 OP Score 评分体系（0-10 分制 + 文字等级 + 胜方 MVP / 败方 ACE），让评分在同位置/同英雄的基线校准下更有区分度和解释力。用户实际只玩大乱斗（模式名 CHERRY/ARAM），无分路概念，因此按英雄职业差异化权重，不做分路权重。

## Solution

把现有"7 维队内归一化 0-100 分"替换为"op.gg 风格 OP Score"：

- 0-10 分制（一位小数）+ 8 档中文文字等级（完美/卓越/优秀/良好/一般/偏低/较差/糟糕）
- 8 个评分维度（KDA、每分钟伤害、每分钟经济、每分钟承伤、每分钟治疗+护盾、控制时长、对塔伤害、视野），全部按分钟归一化
- 权重按英雄职业差异化（ADC/MAGE/TANK/ASSASSIN/FIGHTER/SUPPORT/UNKNOWN），存 YAML 配置可微调；大乱斗下视野权重恒 0
- 评分 = 局内比较（队内相对位置）× 基线比较（该英雄本地历史均值）混合，按英雄的样本量渐进过渡：<10 局纯局内，10-29 局线性过渡，≥30 局 50/50
- 多杀加分：双杀 +0.2、三杀 +0.5、四杀 +1.0、五杀 +2.0
- 不加胜负修正；MVP = 胜方最高分，ACE = 败方最高分
- 落库保留 + 新增 `scoring_version`：版本不匹配或历史数据时实时重算，避免迁移历史数据

## User Stories

1. 作为玩家，我希望每局每个选手有一个 0-10 的 OP Score，以便直观比较表现好坏。
2. 作为玩家，我希望看到每个选手的文字等级（完美/卓越/...），以便一眼看出表现档位。
3. 作为玩家，我希望看到每局 MVP（胜方最佳）和 ACE（败方最佳），以便快速找到关键选手。
4. 作为玩家，我希望评分对每个英雄有自己的期望基线（如坦克就该高承伤、辅助看治疗护盾），以便不同英雄间的评分可比。
5. 作为玩家，我希望长时间统计某个英雄后评分能反映该英雄的历史平均水平，以便评分随我的实际对局环境校准。
6. 作为玩家，我希望同样的每分钟伤害在不同时长对局间可比，以便 10 分钟局和 30 分钟局评分不吃亏。
7. 作为玩家，我希望拿到多杀（双杀/三杀/四杀/五杀）能获得额外加分，以便高光时刻被识别。
8. 作为玩家，我希望评分不因胜负被一刀切对待，以便败方表现好的玩家也能拿高分。
9. 作为开发者，我希望权重表放在 YAML 配置里，以便不重启改代码就能微调各维度权重。
10. 作为开发者，我希望基线数据落在数据库并累积，以便评分随数据量自然进化。
11. 作为开发者，我希望历史对局不需要迁移脚本重算，以便升级无风险。
12. 作为开发者，我希望评分版本号能标识当时算法，以便将来算法再变时可追溯。

## Implementation Decisions

### 评分模型
- 新评分刻度：**OP Score 0.0-10.0**（一位小数），取代现有 0-100 `score` 的小数语义。
- 文字等级映射（8 档）：
  | OP Score | 等级 | 中文 |
  |---|---|---|
  | 9.0-10.0 | Perfect | 完美 |
  | 8.0-8.9 | Excellent | 卓越 |
  | 7.0-7.9 | Great | 优秀 |
  | 6.0-6.9 | Good | 良好 |
  | 5.0-5.9 | Average | 一般 |
  | 4.0-4.9 | Below Average | 偏低 |
  | 3.0-3.9 | Bad | 较差 |
  | < 3.0 | Terrible | 糟糕 |
- **维度清单（8 维，全部按分钟归一化）**：
  1. `kda` — (kills+assists)/max(deaths,1)
  2. `damage` — totalDamageDealtToChampions / 分钟
  3. `gold` — goldEarned / 分钟
  4. `tank` — totalDamageTaken / 分钟
  5. `healShield` — (totalHeal + totalDamageShieldedOnTeammates) / 分钟
  6. `cc` — timeCCingOthers / 分钟
  7. `turret` — damageDealtToTurrets / 分钟
  8. `vision` — visionScore / 分钟（大乱斗默认权重 0，保留维度键）
- **去掉现有 `cs`（补刀）维度**，因为大乱斗补刀价值低、有自动金币增长。
- **多杀加分**：doubleKills+0.2 / tripleKills+0.5 / quadraKills+1.0 / pentaKills+2.0，加到 OP Score 后 clamp(0,10)。

### 职业差异化权重（YAML 可配置）
- 权重表存 YAML（`application.yml` 或独立评分配置段），启动时绑定到配置类，每行和为 1.0，`vision` 键保留但恒 0：

  | 职业 | kda | damage | gold | tank | vision | healShield | cc | turret |
  |---|---|---|---|---|---|---|---|---|
  | ADC | 0.35 | 0.30 | 0.10 | 0.05 | 0.00 | 0.00 | 0.05 | 0.15 |
  | MAGE | 0.30 | 0.35 | 0.10 | 0.05 | 0.00 | 0.00 | 0.10 | 0.10 |
  | TANK | 0.20 | 0.05 | 0.10 | 0.40 | 0.00 | 0.05 | 0.15 | 0.05 |
  | ASSASSIN | 0.40 | 0.25 | 0.10 | 0.05 | 0.00 | 0.00 | 0.10 | 0.10 |
  | FIGHTER | 0.25 | 0.20 | 0.10 | 0.25 | 0.00 | 0.05 | 0.10 | 0.05 |
  | SUPPORT | 0.20 | 0.00 | 0.05 | 0.05 | 0.00 | 0.40 | 0.25 | 0.05 |
  | UNKNOWN | 0.30 | 0.25 | 0.10 | 0.15 | 0.00 | 0.05 | 0.10 | 0.05 |

### 评分公式
1. 每个维度：先算「局内分」与「基线分」，再按混合比合成 `维度分 = 局内分×mix + 基线分×(1−mix)`，截断 0-100。
   - **局内分**：队内 5 人该维度按值排序取位次 → 100/75/50/25/0（最高到最低线性位次分）。
   - **基线分**：`(我的每分钟值 / 基线均值) × 100`，截断 0-100。
   - **混合比 mix**（按英雄独立）：基线样本量 <10 → 0（纯局内）；10-29 → `(n-10)/20 × 0.5`；≥30 → 0.5。
2. `加权总分 = Σ(维度分 × 职业权重)`，0-100。
3. `OP Score = clamp(加权总分/10 + 多杀加分, 0, 10)`。
4. 由 OP Score 映射文字等级。
5. 不加胜负修正。

### 基线（本地校准）机制
- 新增 `scoring_baseline` 表：按 `champion_id` 存样本量、各维度累计值（sum/minutes 维度），同步新对局时 UPDATE 累加。
- 配置文件提供按英雄职业的冷启动默认期望值（样本量为 0 时使用）。
- 样本量口径：按「该英雄在库中的对局数」计数，玩家同英雄玩 10 局只算 1 个基线样本（每局算一次）。

### 落库与版本
- `match_mvp` 增加 `scoring_version` 字段 + `grade` 字段 + `op_score` 字段（或改 `score` 语义为 0-10）。
- 同步时落库评分；详情/列表查询时若版本不匹配（或为空）则实时用当前算法重算并返回，不写历史数据迁移脚本，历史数据不动。
- MVP/ACE 称号：胜方最高分 = MVP，败方最高分 = ACE（沿用现有称号字段，SVP 文案改为 ACE，或新增枚举）。

### 配置项（YAML）
- `scoring.version`：当前算法版本号。
- `scoring.weights`：职业差异化权重表。
- `scoring.multi-kill-bonus`：多杀加分（double/triple/quadra/penta）。
- `scoring.baseline-threshold`：混合比过渡阈值（默认 <10 纯局内，10-29 过渡，≥30 锁定 0.5）。
- `scoring.baseline-defaults`：按职业的冷启动默认期望值。

### 数据来源（已有，无需新增采集）
- stats_json 全量透传已含：totalDamageDealtToChampions、totalDamageTaken、visionScore、totalHeal、totalDamageShieldedOnTeammates、timeCCingOthers、damageDealtToTurrets、goldEarned、kills/deaths/assists、doubleKills/tripleKills/quadraKills/pentaKills、gameDuration（match 主表）。
- `champions.killParticipation` 等 SGP 独有字段在 LCU 源下恒缺失，评分不依赖它们。

## Testing Decisions

- 只测外部行为，不测实现细节：喂入构造好的对局数据（选手表现 + 职业 + 时长），断言输出的 OP Score、grade、MVP/ACE 归属、维度明细是否符合设计公式。
- 与现有测试风格保持一致（JUnit5 + Mockito + AssertJ + Testcontainers 或 MockMvc），测试路径与被测路径一致。
- 模块与测试重点：
  - 评分引擎（纯函数）：维度归一化、职业权重、混合比阈值、多杀加分、clamp、grade 映射、平局确定性。
  - 基线累积服务：样本量口径、累加逻辑、阈值切换、冷启动默认值。
  - MatchMvp/MatchService 集成：同步落库（含版本号）、详情实时重算回退、列表 MVP/ACE 填充。
  - 控制器集成：详情/列表响应包含 opScore/grade/MVP/ACE，契约断言。
- 已有测试（MvpScoringEngineTest、MatchMvpServiceTest、MatchServiceTest、MatchControllerIntegrationTest）需要同步更新以匹配新契约。

## Out of Scope

- 不做分路（position）权重：用户只玩大乱斗，无分路概念。
- 不做排位/段位/全服统计基线：无全服数据源，只做本地校准 + 冷启动默认值。
- 不做胜负修正（加分/减分）。
- 不做目标控制（龙/先锋/男爵）维度：大乱斗无此目标。
- 不做挑战（challenges）相关维度：LCU 数据源下恒缺失。
- 不做实时热加载权重配置（重启生效即可）。
- 不做历史数据迁移脚本（留旧写新 + 实时重算回退）。
- 不保留 `cs` 补刀维度。

## Further Notes

- 现有 ADR `0001-mvp-svp-role-adaptive-scoring.md` 记录的是旧方案（同队 0-100 归一化），新方案偏离该 ADR 的核心决策（基线比较、0-10 分制、版本号）；实现时应新增一条 ADR 说明本次变更及其与原 ADR 的关系。
- 权重初稿数值是拍脑袋定的，需跑真实数据后按分布微调；权重在 YAML 配置中，微调零成本。
- 详情接口当前已是实时计算路径，列表接口查落库记录；改版后两者需统一返回 opScore/grade 语义。
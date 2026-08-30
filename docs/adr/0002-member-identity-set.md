# 0002-member-identity-set.md

# 车队成员匹配采用 puuid 身份集合，聚合键为成员而非 puuid

## Status

accepted

## Context（踩坑记录）

车队周报上线后生产环境 `fleetGames=0`，所有周都查不到数据。根因是**同一玩家存在两套互不相通的 puuid 标识符体系**：

| 来源 | 格式 | 示例 |
| --- | --- | --- |
| 客户端经 LCU/SGP 上报的对局（台服 TW2 为腾讯运营） | 腾讯侧 UUID，带连字符 | `3e242ccb-b520-5f29-8551-a7ad71b8f629` |
| Riot 官方 API（Account-V1 / MATCH-V5） | Riot 全局 puuid，无连字符长串 | `IZOp3JUSyJctQG9w...` |

初版实现把 roster（`team.roster` 的"昵称#tag"名单）经 Riot Account-V1 解析成 Riot 全局 puuid，再拿去匹配客户端上报数据里的腾讯 UUID——两个字符串永远不相等，车队对局过滤恒为空。名字解析本身是成功的，故障点在**标识符体系错配**，测试里两名成员的 puuid 恰好风格一致，未暴露该问题。

## Decision

1. **成员身份 = puuid 集合**（`RosterMember.puuids`，LinkedHashSet）：同一成员的全部已知标识符并入一个集合，匹配一律用集合（`owns(puuid)`），不再假设"一人一个 puuid"。
2. **解析顺序：库内反查优先，Riot API 补充**——
   - 先按 `summoner_name` 反查 `match_participant`：这是数据管道实际使用的标识符，LCU/SGP 局（腾讯 UUID）与 MATCH-V5 回填局（Riot puuid）都能命中，且零外部 API 消耗；
   - 再经 Riot Account-V1 补充全局 puuid，单列 `riotPuuid` 字段专供 MATCH-V5 历史回填使用；
   - 库内已命中时 Riot 失败仅降级（记 warn，回填能力受限），不阻塞周报/榜单；两套来源全空才报 400。
3. **聚合键为成员（riotId）而非 puuid**：周报/榜单/成员卡的全部聚合按成员键控，输出 DTO 取集合首项（primaryPuuid）展示——否则同一人的两种标识符会在榜单上被拆成两行。

## Considered Options

- **统一标识符**（回填时把 Riot puuid 换算/映射成腾讯 UUID 入库）→ 否决：两套标识符无法互相推导，强行映射需要额外 API 且仍有漏配风险
- **只用库内反查、放弃 Riot puuid** → 否决：MATCH-V5 历史回填必须按 Riot 全局 puuid 查询，丢掉它回填能力就没了
- **只用 Riot puuid 并在同步时归一化客户端数据** → 否决：改动客户端上报契约违反"客户端零改动"约束，且 SGP/LCU 侧无法获取 Riot 全局 puuid
- **按 summoner_name 全程匹配（不解析 puuid）** → 否决：改名后历史数据归属断裂，puuid 才是稳定身份键

## Consequences

- 新增"玩家匹配"类逻辑时，必须先确认数据横跨哪些来源（LCU/SGP 同步局 vs MATCH-V5 回填局），匹配一律走身份集合（术语见 `CONTEXT.md` 成员身份集合）
- roster 解析依赖 `summoner_name` 精确匹配，玩家改名后旧名对应的库内行不再命中（但 Riot 补充路径仍可兜底；新同步的局会以新名落库）
- 同一成员的多局数据分散在两种 puuid 下，按 puuid 直查 `match_participant`（如 Web 玩家页 `/players/:puuid`）只能看到单一体系的局——跨体系的"个人全部对局"必须走成员卡聚合接口
- Riot API 不可用且库内无记录的新成员无法入队（400 提示），需先打一场被同步的对局或恢复 Riot Key

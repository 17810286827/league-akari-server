package com.leagueakari.team;

import com.leagueakari.common.exception.ErrorCode;

import com.leagueakari.common.exception.BizException;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.leagueakari.config.TeamProperties;
import com.leagueakari.dto.riot.RiotAccountDto;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.mapper.MatchParticipantMapper;
import com.leagueakari.riot.RiotAccountClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 车队名单（roster）解析服务
 * <p>职责：把配置中的 "昵称#tag" 名单解析为成员的身份集合，供周报/榜单匹配车队成员。</p>
 *
 * <p><b>关键背景——两套 puuid 标识符体系</b>：台服（腾讯运营）客户端上报的对局里，
 * puuid 是腾讯侧 UUID（带连字符，如 3e242ccb-b520-...）；而 Riot Account-V1 API 返回的
 * 是 Riot 全局 puuid（无连字符长串）。同一个人在库里可能同时存在两种 puuid
 * （LCU/SGP 同步的局 vs MATCH-V5 回填的局），因此成员身份是 <b>puuid 集合</b> 而非单值。</p>
 *
 * <p>解析策略（按成员）：</p>
 * <ol>
 *   <li><b>库内反查优先</b>：按 summoner_name 查 match_participant——这正是实际数据管道
 *       里的标识符，天然覆盖两种来源，且零外部 API 消耗；</li>
 *   <li><b>Riot Account-V1 补充</b>：拿到 Riot 全局 puuid（MATCH-V5 历史回填按它拉取）；
 *       库内已有命中时此步为尽力而为，失败不阻塞（记 warn）；</li>
 *   <li>两个来源都查不到才判定该成员解析失败（整体抛参数异常，400 语义）。</li>
 * </ol>
 * <p>解析结果进程内缓存：改名不影响（按 puuid 集合聚合），配置变更重启生效。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamRosterService {

    /**
     * 车队成员：riotId 为配置原样（展示与聚合键），puuids 为该成员的全部已知标识符。
     * riotPuuid 为 Riot 全局标识（Account-V1 解析所得，MATCH-V5 历史回填专用；
     * 库内命中但 Riot 未命中时为 null，此时回填能力缺失）。primaryPuuid 约定取集合首项
     */
    public record RosterMember(String riotId, LinkedHashSet<String> puuids, String riotPuuid) {
        /** 便捷构造：未记录 Riot 全局 puuid 的场景（测试/库内单来源） */
        public RosterMember(String riotId, LinkedHashSet<String> puuids) {
            this(riotId, puuids, null);
        }

        /** 该 puuid 是否属于此成员（身份集合匹配） */
        public boolean owns(String puuid) {
            return puuid != null && puuids.contains(puuid);
        }

        /** 主标识符（集合首项），用于 DTO 展示与成员卡定位 */
        public String primaryPuuid() {
            return puuids.iterator().next();
        }

        /** 历史回填专用标识：优先 Riot 全局 puuid，未解析到时退回主标识 */
        public String backfillPuuid() {
            return riotPuuid != null ? riotPuuid : primaryPuuid();
        }
    }

    private final TeamProperties teamProperties;
    private final RiotAccountClient riotAccountClient;
    private final MatchParticipantMapper matchParticipantMapper;

    /** 解析缓存：进程内一次性解析（名单长期稳定，无失效需求） */
    private volatile List<RosterMember> cache;

    /**
     * 名单是否已配置（配置里有至少一个非空项）
     */
    public boolean isConfigured() {
        return teamProperties.getRoster().stream().anyMatch(name -> name != null && !name.isBlank());
    }

    /**
     * 取车队成员列表（必填语义）：
     * 未配置抛参数异常（全局处理器转 400，提示先配置名单）；
     * 首次调用解析并缓存，之后直接返回缓存
     *
     * @return 车队成员列表（按 riotId 去重，保持配置顺序）
     * @throws BizException 名单未配置（1101），或任一成员两套来源都查不到（1102）
     */
    public List<RosterMember> requireMembers() {
        // 已解析：直接返回缓存，避免每次请求重复查询
        List<RosterMember> cached = cache;
        if (cached != null) {
            return cached;
        }
        // 未配置：业务参数错误，返回 400 让管理员第一时间知道要先配名单
        if (!isConfigured()) {
            log.warn("Team roster is not configured (team.roster is empty)");
            throw new BizException(ErrorCode.ROSTER_NOT_CONFIGURED);
        }
        // 逐项解析：单个成员两套来源都失败则整体失败并带上成员名，便于定位
        LinkedHashMap<String, RosterMember> resolved = new LinkedHashMap<>();
        for (String riotId : teamProperties.getRoster()) {
            if (riotId == null || riotId.isBlank()) {
                continue;
            }
            RosterMember member = resolveMember(riotId.trim());
            // 按 riotId 去重（配置重复项只保留先出现的）
            resolved.putIfAbsent(member.riotId(), member);
        }
        List<RosterMember> members = List.copyOf(resolved.values());
        log.info("Team roster resolved: {} members: {}", members.size(),
                members.stream().map(m -> m.riotId() + "(puuid×" + m.puuids().size() + ")").toList());
        cache = members;
        return members;
    }

    /**
     * 解析单个成员的身份集合：库内反查（优先）∪ Riot Account-V1（补充）
     *
     * @param riotId 配置的 "昵称#tag"
     * @return 成员（身份集合非空）
     * @throws BizException 两套来源都查不到该成员（1102）
     */
    private RosterMember resolveMember(String riotId) {
        LinkedHashSet<String> puuids = new LinkedHashSet<>();

        // 来源一：库内按 summoner_name 反查——数据管道实际使用的标识符，
        // LCU/SGP 局（腾讯 UUID）与 MATCH-V5 回填局（Riot puuid）都能命中
        try {
            List<MatchParticipant> rows = matchParticipantMapper.selectList(
                    new QueryWrapper<MatchParticipant>()
                            .select("puuid")
                            .eq("summoner_name", riotId));
            for (MatchParticipant row : rows) {
                if (row.getPuuid() != null && !row.getPuuid().isBlank()) {
                    puuids.add(row.getPuuid());
                }
            }
        } catch (Exception e) {
            // 库查询失败不终止解析：还有 Riot 来源兜底
            log.warn("Roster db lookup failed for {}: {}", riotId, e.getMessage());
        }

        // 来源二：Riot Account-V1（命中 riot_account 缓存则零 API 消耗）；
        // 全局 puuid 供 MATCH-V5 历史回填使用。库内已命中时失败仅降级不阻塞
        String riotPuuid = null;
        try {
            RiotAccountDto account = riotAccountClient.searchByRiotId(riotId);
            if (account != null && account.getPuuid() != null && !account.getPuuid().isBlank()) {
                riotPuuid = account.getPuuid();
                puuids.add(riotPuuid);
            }
        } catch (Exception e) {
            if (puuids.isEmpty()) {
                // 两套来源都已尝试且库内无命中：这是真失败，按 400 语义抛出
                log.error("Failed to resolve roster member {}: {}", riotId, e.getMessage());
                throw new BizException(ErrorCode.ROSTER_MEMBER_RESOLVE_FAILED,
                        "车队成员解析失败：" + riotId + "（" + e.getMessage() + "）");
            }
            // 库内已有身份：Riot 失败只影响历史回填能力，记 warn 不阻塞周报/榜单
            log.warn("Riot lookup failed for {} (db identity kept, backfill unavailable): {}",
                    riotId, e.getMessage());
        }

        if (puuids.isEmpty()) {
            throw new BizException(ErrorCode.ROSTER_MEMBER_RESOLVE_FAILED,
                    "车队成员解析失败：" + riotId + "（库内无对局记录且 Riot 查询未命中）");
        }
        return new RosterMember(riotId, puuids, riotPuuid);
    }
}

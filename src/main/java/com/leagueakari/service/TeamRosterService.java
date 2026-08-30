package com.leagueakari.service;

import com.leagueakari.config.TeamProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 车队名单（roster）解析服务
 * <p>职责：把配置中的 "昵称#tag" 名单解析为 puuid（经 RiotAccountClient，命中
 * riot_account 持久化缓存则零 API 消耗），供周报/榜单按 puuid 匹配车队成员。
 * 解析结果进程内缓存：改名不影响（按 puuid 聚合），配置变更重启生效。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamRosterService {

    /** 车队成员：puuid 为唯一身份键，riotId 保留配置原样用于展示 */
    public record RosterMember(String puuid, String riotId) {}

    private final TeamProperties teamProperties;
    private final RiotAccountClient riotAccountClient;

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
     * @return 车队成员列表（按 puuid 去重，保持配置顺序）
     * @throws IllegalArgumentException 名单未配置
     * @throws IllegalStateException    任一成员解析失败（改名被回收/Riot 接口异常）
     */
    public List<RosterMember> requireMembers() {
        // 已解析：直接返回缓存，避免每次请求重复查询（库表命中也是 IO）
        List<RosterMember> cached = cache;
        if (cached != null) {
            return cached;
        }
        // 未配置：业务参数错误，返回 400 让管理员第一时间知道要先配名单
        if (!isConfigured()) {
            log.warn("Team roster is not configured (team.roster is empty)");
            throw new IllegalArgumentException("车队名单未配置：请先在服务端配置 team.roster 成员名单");
        }
        // 逐项解析：单个成员失败则整体失败并带上成员名，便于定位是哪个名字出了问题
        java.util.LinkedHashMap<String, RosterMember> resolved = new java.util.LinkedHashMap<>();
        for (String riotId : teamProperties.getRoster()) {
            if (riotId == null || riotId.isBlank()) {
                continue;
            }
            try {
                var account = riotAccountClient.searchByRiotId(riotId.trim());
                // 按 puuid 去重：两个名字解析到同一人（改名场景）只保留先出现的
                resolved.putIfAbsent(account.getPuuid(), new RosterMember(account.getPuuid(), riotId.trim()));
            } catch (Exception e) {
                // 单成员失败：按规格约定转参数异常（400 语义），提示先修正名单
                log.error("Failed to resolve roster member {}: {}", riotId, e.getMessage());
                throw new IllegalArgumentException("车队成员解析失败：" + riotId + "（" + e.getMessage() + "）");
            }
        }
        List<RosterMember> members = List.copyOf(resolved.values());
        log.info("Team roster resolved: {} members: {}", members.size(),
                members.stream().map(RosterMember::riotId).toList());
        cache = members;
        return members;
    }
}

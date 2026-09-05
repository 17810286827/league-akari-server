package com.leagueakari.team;

import com.leagueakari.dto.scoring.PlayerScoreView;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchMvp;
import com.leagueakari.entity.MatchParticipant;

import java.util.List;
import java.util.Map;

/**
 * 单场对局的完整内存视图：主表 + 参赛者 + 评选记录 + 全员 op_score
 * <p>scores 为 match_mvp 引擎的实时计算结果（puuid → 评分视图），不含落库写入。
 * 原为 TeamStatsService 私有嵌套记录，拆分后作为装载器（FleetGameLoader）与
 * 各聚合服务（周报/榜单/成员）之间的共享视图模型。</p>
 */
record GameData(Match match, List<MatchParticipant> participants,
        List<MatchMvp> awards, Map<String, PlayerScoreView> scores) {}

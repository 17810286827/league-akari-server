package com.leagueakari.team;

import com.leagueakari.dto.scoring.PlayerScoreView;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchMvp;
import com.leagueakari.entity.MatchParticipant;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * 单场对局的完整内存视图（Lombok {@code @Value} 不可变对象）：主表 + 参赛者 + 评选记录 + 全员 op_score
 * <p>scores 为 match_mvp 引擎的实时计算结果（puuid → 评分视图），不含落库写入。
 * 原为 TeamStatsService 私有嵌套记录，拆分后作为装载器（FleetGameLoader）与
 * 各聚合服务（周报/榜单/成员）之间的共享视图模型。</p>
 */
@Value
class GameData {

    /** 对局主表记录 */
    Match match;

    /** 全员参赛者记录 */
    List<MatchParticipant> participants;

    /** 评选记录（match_mvp 落库行） */
    List<MatchMvp> awards;

    /** puuid → 评分视图（实时计算，不含落库写入） */
    Map<String, PlayerScoreView> scores;
}

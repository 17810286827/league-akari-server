package com.leagueakari.dto.match;

import com.leagueakari.dto.scoring.PlayerScoreView;
import com.leagueakari.entity.MatchParticipant;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 对局详情：主表字段 + 参赛者列表（含 stats_json 全量）
 */
@Data
public class MatchDetailResponse {

    /** LCU 对局 ID */
    private Long gameId;

    /** 对局创建时间戳（ms） */
    private Long gameCreation;

    /** 对局时长（秒） */
    private Integer gameDuration;

    /** 模式，如 CLASSIC / CHERRY */
    private String gameMode;

    /** 类型，如 MATCHED_GAME */
    private String gameType;

    /** 队列 ID */
    private Integer queueId;

    /** 地图 ID */
    private Integer mapId;

    /** 游戏版本 */
    private String gameVersion;

    /** 地区，如 na1 */
    private String region;

    /** 区服，腾讯服如 SG2 */
    private String rsoPlatformId;

    /** 数据源：lcu / sgp */
    private String dataSource;

    /** 获胜队伍 ID */
    private Integer winnerTeamId;

    /** 记录本局的玩家 puuid */
    private String selfPuuid;

    /** 队伍级统计全量快照（JSON 字符串） */
    private String teamsJson;

    /** 参赛者明细列表，含 stats_json 全量 */
    private List<MatchParticipant> participants;

    /** MVP：胜方最佳选手（未评选或老数据时为 null） */
    private MvpAwardResponse mvp;

    /** ACE：败方最佳选手（未评选或老数据时为 null，旧称 SVP） */
    private MvpAwardResponse ace;

    /** 全员实时评分：puuid → OP Score + grade + 维度明细（查询时实时计算） */
    private Map<String, PlayerScoreView> playerScores;
}

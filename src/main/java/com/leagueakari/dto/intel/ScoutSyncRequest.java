package com.leagueakari.dto.intel;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 敌方侦察数据接收入参：桌面端选人阶段喂入的敌方段位 + 历史对局摘要。
 * <p>契约与桌面端 SGP 摘要对齐：段位为"每玩家一条"，历史摘要为"每局 participants"
 * （只含开黑判定与窗口胜率所需字段：puuid/teamId/胜负/召唤师名）。</p>
 */
@Data
public class ScoutSyncRequest {

    /** 敌方玩家段位（5 人；SGP leagues-ledge 直读） */
    @Valid
    @NotEmpty(message = "players 不能为空")
    private List<PlayerRank> players;

    /** 历史对局摘要（判开黑/胜率原料；可为空——仅首次喂入段位无历史时降级） */
    @Valid
    private List<MatchSummary> matches;

    /** 一名敌方玩家的段位快照 */
    @Data
    public static class PlayerRank {
        /** 玩家唯一标识（腾讯侧 puuid） */
        @NotNull(message = "puuid 不能为空")
        private String puuid;

        /** 召唤师名快照 */
        private String summonerName;

        /** 段位所属队列（如 RANKED_SOLO_5x5） */
        private String queueType;

        /** 大段位（如 DIAMOND；未定级为 null） */
        private String tier;

        /** 小段（如 I/II/III/IV） */
        private String rank;
    }

    /** 一局历史对局摘要（同队共现与胜负原料） */
    @Data
    public static class MatchSummary {
        /** 对局标识（SGP 对局 ID） */
        @NotNull(message = "matchId 不能为空")
        private String matchId;

        /** 本局队列 ID */
        private Integer queueId;

        /** 本局参与者（至少 1 人；判开黑只需 teamId/胜负） */
        @Valid
        @NotEmpty(message = "participants 不能为空")
        private List<Participant> participants;
    }

    /** 一局中一名参与者的摘要行 */
    @Data
    public static class Participant {
        /** 玩家唯一标识 */
        @NotNull(message = "puuid 不能为空")
        private String puuid;

        /** 召唤师名快照（允许为空） */
        private String summonerName;

        /** 队伍：100=蓝色方/200=红色方 */
        @NotNull(message = "teamId 不能为空")
        private Integer teamId;

        /** 是否获胜 */
        @NotNull(message = "win 不能为空")
        private Boolean win;
    }
}

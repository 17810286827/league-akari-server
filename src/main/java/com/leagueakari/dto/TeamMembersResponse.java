package com.leagueakari.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 车队成员响应：roster 名单 + 全时段车队对局出勤统计
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamMembersResponse {

    /** 成员列表（配置顺序） */
    private List<Member> members;

    /**
     * 单个成员的出勤统计
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Member {

        /** 成员 puuid */
        private String puuid;

        /** 成员 riotId（"昵称#tag"） */
        private String riotId;

        /** 参与的车队对局数 */
        private int games;

        /** 车队对局人次胜场 */
        private int wins;

        /** 胜率（0-1，一位小数精度由前端处理），无对局时为 null */
        private Double winRate;
    }
}

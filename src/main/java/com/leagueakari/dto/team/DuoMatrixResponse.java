package com.leagueakari.dto.team;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 搭档胜率矩阵响应（工单 #37 / spec #31）：
 * 车队成员两两搭档的车队对局胜率矩阵（N×N，对称）。
 * 每格胜率 + 局数（胜负按成员人次计）；小样本格子由前端以局数标注弱化，
 * 避免"2 局 100% 胜率"式误导。只统计车队对局（与七榜/周报同口径）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DuoMatrixResponse {

    /** 矩阵轴成员（roster 顺序，riotId） */
    private List<String> members;

    /** N×N 矩阵（matrix[row][col]：row/col 为成员在 members 中的下标；对称） */
    private List<List<Cell>> matrix;

    /** 对角线 cell 为成员个人车队局统计（与搭档格同结构） */

    /** 矩阵单格：搭档局数 + 人次胜负与胜率 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Cell {

        /** 搭档局数（两人同局的车队对局数） */
        private int games;

        /** 人次胜场（两人在该局各自胜利的总和） */
        private int wins;

        /** 人次负场 */
        private int losses;

        /** 胜率（wins/(wins+losses)）；0 局时为 null（前端显示"—"） */
        private Double winRate;
    }
}

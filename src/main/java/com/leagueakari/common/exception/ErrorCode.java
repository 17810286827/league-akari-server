package com.leagueakari.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 全局错误码枚举：全项目错误的<b>唯一登记处</b>，新增错误必须先在此登记。
 * <p>段位规则（写在类注释里，改段位先改这里）：</p>
 * <ul>
 *   <li>0 —— 成功；</li>
 *   <li>1xxx —— 请求与参数（调用方问题，如校验失败、gameId 不一致）；</li>
 *   <li>11xx —— 车队配置（名单未配置、成员解析失败、非车队成员等）；</li>
 *   <li>2xxx —— 对局域（对局/时间线不存在）；</li>
 *   <li>3xxx —— 账号域（召唤师不存在等 Riot 资源缺失）；</li>
 *   <li>4xxx —— 外部依赖（40 Riot / 41 AI / 42 QQ 预留：Key 未配置、调用失败）；</li>
 *   <li>5xxx —— 系统（未识别异常兜底、数据组装失败）。</li>
 * </ul>
 * <p><b>HTTP 状态码不承载错误语义</b>：所有业务响应一律 HTTP 200，错误语义全靠 code。
 * 前端双层判别式：HTTP 非 200 = 请求未达业务（路由/容器级故障）；
 * HTTP 200 且 code != 0 = 业务失败（message 可直接展示）。</p>
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    /** 成功（0 是唯一成功码） */
    SUCCESS(0, "ok"),

    // ---- 1xxx 请求与参数 ----

    /** 通用参数错误：@Valid 校验失败、请求体不可读、参数类型不匹配、内部入参缺失 */
    INVALID_ARGUMENT(1001, "请求参数错误"),
    /** 时间线同步：path 与 body 的 gameId 不一致 */
    GAME_ID_MISMATCH(1002, "path 与 body 的 gameId 不一致"),
    /** 召唤师搜索：riotName 缺少 #tag */
    INVALID_RIOT_NAME(1003, "召唤师名格式错误，应为 昵称#tag"),

    // ---- 11xx 车队配置 ----

    /** team.roster 名单未配置 */
    ROSTER_NOT_CONFIGURED(1101, "车队名单未配置：请先在服务端配置 team.roster 成员名单"),
    /** 名单成员库内与 Riot 双来源都解析失败 */
    ROSTER_MEMBER_RESOLVE_FAILED(1102, "车队成员解析失败"),
    /** 成员卡查询：puuid 不属于车队名单 */
    NOT_TEAM_MEMBER(1103, "非车队成员"),
    /** 榜单维度参数不在可选集合内 */
    UNKNOWN_DIMENSION(1104, "未知榜单维度"),

    // ---- 2xxx 对局域 ----

    /** 对局主表按 gameId 未命中 */
    MATCH_NOT_FOUND(2001, "对局不存在"),
    /** 对局时间线按 gameId 未命中或快照损坏 */
    TIMELINE_NOT_FOUND(2002, "对局时间线不存在"),

    // ---- 3xxx 账号域 ----

    /** Riot 侧资源 404（召唤师搜索路径语义为"召唤师不存在"） */
    RIOT_ACCOUNT_NOT_FOUND(3001, "召唤师不存在"),

    // ---- 4xxx 外部依赖 ----

    /** Riot API Key 未配置（搜索/回填依赖 Riot 时） */
    RIOT_API_KEY_MISSING(4001, "Riot API Key 未配置"),
    /** Riot API 调用失败：网络异常、非 2xx、限流重试无效、响应数据异常 */
    RIOT_API_ERROR(4002, "Riot API 调用失败"),
    /** AI API Key 未配置（分析/锐评依赖 AI 时） */
    AI_KEY_MISSING(4101, "AI API Key 未配置"),
    /** AI 接口调用失败：非 200、网络异常、重试后正文仍为空 */
    AI_API_ERROR(4102, "AI 接口调用失败"),

    // ---- 5xxx 系统 ----

    /** 未识别异常的系统兜底（响应不透出内部细节，完整堆栈落日志） */
    INTERNAL_ERROR(5000, "服务器内部错误"),
    /** 数据组装/序列化失败（对局摘要、周报摘要等内部投影） */
    DATA_ASSEMBLY_FAILED(5001, "数据组装失败");

    /** 业务码：0=成功，非 0=失败（前端唯一判失败依据） */
    private final int code;

    /** 默认文案：抛出点无动态上下文时直接展示；有上下文由 BizException 覆盖 */
    private final String defaultMessage;
}

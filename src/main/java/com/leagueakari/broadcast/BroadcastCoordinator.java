package com.leagueakari.broadcast;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.leagueakari.config.PushProperties;
import com.leagueakari.config.TeamProperties;
import com.leagueakari.entity.Match;
import com.leagueakari.entity.MatchMvp;
import com.leagueakari.entity.MatchParticipant;
import com.leagueakari.mapper.MatchMapper;
import com.leagueakari.mapper.MatchMvpMapper;
import com.leagueakari.mapper.MatchParticipantMapper;
import com.leagueakari.match.MatchSavedEvent;
import com.leagueakari.qqbot.QqBotClient;
import com.leagueakari.common.exception.QqPushException;
import com.leagueakari.reportimage.ReportImageRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.leagueakari.team.TeamRosterService;
import com.leagueakari.team.TeamStatsService;

/**
 * 局后播报编排（Post-Game Broadcast）：纯推送状态机。
 * 对局数据入库事务提交后由 MatchSavedEvent 事件触发 {@link #onMatchSaved}，内部判定
 * "是否刚结束的车队对局"（状态门控 + 车队成员数 + 时间窗 + 开关），
 * 通过后组装战报并向车队群发送，全程落库推送状态。
 * <p>状态机（match.push_status）：
 * PENDING/FAILED →(CAS)→ PUSHING → 成功 SENT / 失败 FAILED。
 * 发送失败置 FAILED，桌面端轮询补推同一局时会再次进入，天然重试；
 * 服务重启中断的 PUSHING 由 {@link #recoverInterruptedPush} 启动时恢复为 FAILED。</p>
 * <p>数据组装不在本类：一局摘要见 FleetGameSummaryService，
 * 战报图投影见 ReportImageProjector，AI 输入投影见 PostGameSummaryBuilder。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastCoordinator {

    /** 推送状态（与 V7__match_push_status.sql 注释对应） */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PUSHING = "PUSHING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_AI_FAILED = "AI_FAILED";

    /** 错误原因落库上限（列 VARCHAR(512)） */
    private static final int ERROR_MAX_LENGTH = 500;

    /** AI 全挂时的缺席提示（战报图已送达后补发，保证群里永远有交代） */
    private static final String AI_ABSENCE_TIP =
            "🤖 AI 评阅官本局不在线，锐评缺席一次——战报图已送达，欢迎人工复盘。";

    private final MatchMapper matchMapper;
    private final MatchParticipantMapper participantMapper;
    private final MatchMvpMapper mvpMapper;
    private final PushProperties pushProperties;
    private final TeamProperties teamProperties;
    private final TeamRosterService rosterService;
    /** 一局摘要组装：车队视角口径唯一实现（主队判定/比分/排序/称号） */
    private final FleetGameSummaryService summaryService;
    /** 战报图投影：一局摘要 → 渲染规格 */
    private final ReportImageProjector reportImageProjector;
    /** AI 输入投影：一局摘要 → 锐评 JSON */
    private final PostGameSummaryBuilder postGameSummaryBuilder;
    private final QqBotClient qqBotClient;
    /** 战报图渲染器：渲染规格 → PNG 字节（Java2D，见 reportimage 包） */
    private final ReportImageRenderer reportImageRenderer;
    /** 局后锐评：图发送后生成第二条文本消息；AI 不可用由本编排降级 */
    private final PostGameCommentService postGameCommentService;
    private final Clock clock;

    /**
     * 服务重启恢复：启动时把残留的 PUSHING（上一进程中断在发送途中）恢复为 FAILED，
     * 使桌面端补推同一局时可再次触发播报，避免永久卡死
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedPush() {
        int recovered = matchMapper.update(null, new UpdateWrapper<Match>()
                .set("push_status", STATUS_FAILED)
                .set("push_error", "服务重启中断发送，待补推重试")
                .eq("push_status", STATUS_PUSHING));
        if (recovered > 0) {
            log.warn("Recovered interrupted broadcasts: count={}", recovered);
        }
    }

    /**
     * 局后播报入口：监听"对局已同步"事件，事务提交后执行（AFTER_COMMIT）——
     * 播报永远基于已提交数据，发送期间不占用落库事务的数据库连接。
     * <p>发布方（MatchIngestService）每次同步都发布（含幂等跳过），是否播报由本类
     * 内部门控判定；全部判定不通过时零副作用，判定通过则发送并落库状态。</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMatchSaved(MatchSavedEvent event) {
        Long gameId = event.gameId();
        if (gameId == null) {
            return;
        }
        try {
            doBroadcast(gameId);
        } catch (Exception e) {
            // 兜底：编排异常（如状态更新失败）不影响已提交的落库事务，落库失败原因等待补推
            log.error("Broadcast orchestration failed: gameId={}", gameId, e);
            markFailed(gameId, e.getMessage());
        }
    }

    /** 判定 + 发送主流程 */
    private void doBroadcast(Long gameId) {
        // 1. 状态门控：读库判定是否已处理/处理中，避免重复播报与并发双发
        Match match = matchMapper.selectOne(new QueryWrapper<Match>().eq("game_id", gameId));
        if (match == null) {
            log.info("Broadcast skipped: match not found, gameId={}", gameId);
            return;
        }
        String status = match.getPushStatus();
        if (STATUS_SENT.equals(status) || STATUS_AI_FAILED.equals(status) || STATUS_PUSHING.equals(status)) {
            log.info("Broadcast skipped: status={}, gameId={}", status, gameId);
            return;
        }

        // 2. 开关与配置门控：未启用/未配置视为"未开通"，不落状态（配置好后自然生效）
        if (!pushProperties.isEnabled()) {
            log.info("Broadcast skipped: push disabled, gameId={}", gameId);
            return;
        }
        if (!pushProperties.isConfigured()) {
            log.warn("Broadcast skipped: push not configured (group/appId/secret), gameId={}", gameId);
            return;
        }

        // 3. 时间窗门控：距估算的对局结束时刻超过窗口视为旧局（backfill/补推历史），不播报
        long endAtMs = match.getGameCreation() + match.getGameDuration() * 1000L;
        long windowMs = pushProperties.getRecentWindowMinutes() * 60_000L;
        if (clock.millis() - endAtMs > windowMs) {
            log.info("Broadcast skipped: stale game, gameId={}, endAt={}, window={}min",
                    gameId, endAtMs, pushProperties.getRecentWindowMinutes());
            // 置 SENT 防补推反复检查该旧局
            markSent(gameId);
            return;
        }

        // 4. 车队局门控：同局车队成员数 ≥ 阈值才播报（口径与周报一致：成员身份集合匹配）
        List<MatchParticipant> participants = participantMapper.selectList(
                new QueryWrapper<MatchParticipant>().eq("match_id", match.getId()));
        List<TeamRosterService.RosterMember> roster;
        try {
            roster = rosterService.requireMembers();
        } catch (Exception e) {
            // 名单解析依赖 Riot API（首次/改名后）：暂时跳过，补推时再试
            log.warn("Broadcast skipped: roster unavailable, gameId={}, err={}", gameId, e.getMessage());
            return;
        }
        Map<String, TeamRosterService.RosterMember> memberByPuuid = memberIndex(roster);
        long fleetCount = participants.stream()
                .filter(p -> memberByPuuid.containsKey(p.getPuuid()))
                .count();
        if (fleetCount < Math.max(1, teamProperties.getMinSharedMembers())) {
            log.info("Broadcast skipped: not a fleet game, gameId={}, fleetMembers={}",
                    gameId, fleetCount);
            // 个人局永远不播报：置 SENT 免去后续每次补推的重复判定
            markSent(gameId);
            return;
        }

        // 5. 抢占：CAS 推进 PUSHING，仅成功者继续（并发双发防线）
        int claimed = matchMapper.update(null, new UpdateWrapper<Match>()
                .set("push_status", STATUS_PUSHING)
                .eq("game_id", gameId)
                .in("push_status", STATUS_PENDING, STATUS_FAILED));
        if (claimed == 0) {
            log.info("Broadcast skipped: claim failed (concurrent), gameId={}", gameId);
            return;
        }
        // 抢占成功：PENDING/FAILED → PUSHING，开始本局播报（补推重试也走此路径，可据日志数重试次数）
        log.info("Broadcast claimed: gameId={}, status={}->PUSHING", gameId, status);

        // 6. 组装一局摘要 → 战报图投影 → 渲染 PNG → 图片消息发送（图只管数据，不依赖 AI）
        List<MatchMvp> awards = mvpMapper.selectList(
                new QueryWrapper<MatchMvp>().eq("match_id", match.getId()));
        FleetGameSummary summary = summaryService.build(match, participants, memberByPuuid, awards);
        byte[] png;
        try {
            png = reportImageRenderer.renderPng(reportImageProjector.project(summary));
        } catch (Exception e) {
            log.error("Broadcast render failed: gameId={}", gameId, e);
            markFailed(gameId, "战报图渲染失败: " + e.getMessage());
            return;
        }
        try {
            qqBotClient.sendGroupImageMessage(pushProperties.getGroupOpenId(), png);
            // 图已送达：先落 SENT 与发送时间，锐评结果随后补状态（comment 阶段失败不重发图）
            markSent(gameId);
            log.info("Broadcast image sent: gameId={}, fleetMembers={}, pngBytes={}",
                    gameId, fleetCount, png.length);
        } catch (QqPushException e) {
            log.error("Broadcast send failed: gameId={}", gameId, e);
            markFailed(gameId, e.getMessage());
            return;
        }

        // 7. 局后锐评（第二条消息）：AI 生成失败重试耗尽后发缺席提示；
        //    提示也失败则整局 FAILED，由桌面端补推整局重播
        if (pushProperties.isAiCommentEnabled()) {
            broadcastComment(gameId, postGameSummaryBuilder.build(summary));
        }
    }

    /** 局后锐评阶段：生成 → 发 Markdown（**加粗** 醒目标记）→ 记送达；AI/发送失败 → 缺席提示兜底 */
    private void broadcastComment(Long gameId, Map<String, Object> aiSummary) {
        String comment;
        try {
            comment = postGameCommentService.generateComment(aiSummary);
        } catch (Exception e) {
            // AI 不可用（key 未配/接口失败/重试后空正文）：发缺席提示，不静默
            log.warn("Post-game comment failed, send absence tip: gameId={}, err={}",
                    gameId, e.getMessage());
            sendAbsenceTip(gameId);
            return;
        }
        try {
            // 锐评用 Markdown 通道（msg_type=2）：人名/称号/关键数据以 **加粗** 渲染
            qqBotClient.sendGroupMarkdownMessage(pushProperties.getGroupOpenId(), comment);
            markCommentDelivered(gameId);
            log.info("Post-game comment sent: gameId={}, length={}", gameId, comment.length());
        } catch (QqPushException e) {
            log.error("Post-game comment send failed, send absence tip: gameId={}", gameId, e);
            sendAbsenceTip(gameId);
        }
    }

    /** 发送 AI 缺席提示：成功 → AI_FAILED（图已发、AI 缺席已提示）；失败 → FAILED 等补推 */
    private void sendAbsenceTip(Long gameId) {
        try {
            qqBotClient.sendGroupTextMessage(pushProperties.getGroupOpenId(), AI_ABSENCE_TIP);
            markAiAbsent(gameId);
            log.info("AI absence tip sent: gameId={}", gameId);
        } catch (QqPushException e) {
            log.error("AI absence tip send failed: gameId={}", gameId, e);
            markFailed(gameId, "AI 缺席提示发送失败: " + e.getMessage());
        }
    }

    /** 推送成功：状态 SENT + 战报消息发送时间（终态，此后补推不再重发） */
    private void markSent(Long gameId) {
        matchMapper.update(null, new UpdateWrapper<Match>()
                .set("push_status", STATUS_SENT)
                .set("push_image_at", LocalDateTime.now())
                .eq("game_id", gameId));
        // 状态机审计：任一状态变更都留痕，便于按 gameId 还原整局推送轨迹
        log.info("Broadcast status -> SENT: gameId={}", gameId);
    }

    /** 推送失败：状态 FAILED + 错误原因（截断落库），等待桌面端补推重试 */
    private void markFailed(Long gameId, String error) {
        String detail = error == null ? "unknown" : error;
        if (detail.length() > ERROR_MAX_LENGTH) {
            detail = detail.substring(0, ERROR_MAX_LENGTH);
        }
        matchMapper.update(null, new UpdateWrapper<Match>()
                .set("push_status", STATUS_FAILED)
                .set("push_error", detail)
                .eq("game_id", gameId));
        // 状态机审计：FAILED 是补推重试的入口，日志含原因，可按 gameId 聚合重试次数
        log.warn("Broadcast status -> FAILED: gameId={}, reason={}", gameId, detail);
    }

    /** 锐评文本已送达：补记发送时间（状态保持 SENT） */
    private void markCommentDelivered(Long gameId) {
        matchMapper.update(null, new UpdateWrapper<Match>()
                .set("push_comment_at", LocalDateTime.now())
                .eq("game_id", gameId));
        log.info("Broadcast comment delivered: gameId={}", gameId);
    }

    /** AI 缺席：状态 AI_FAILED（图已发、缺席提示已发）+ 提示发送时间 */
    private void markAiAbsent(Long gameId) {
        matchMapper.update(null, new UpdateWrapper<Match>()
                .set("push_status", STATUS_AI_FAILED)
                .set("push_comment_at", LocalDateTime.now())
                .eq("game_id", gameId));
        log.warn("Broadcast status -> AI_FAILED: gameId={} (image sent, AI comment absent)",
                gameId);
    }

    /**
     * 成员身份索引：puuid → 成员（跨两种 puuid 体系，与 TeamStatsService 口径一致）。
     * 车队对局判定与摘要组装都用它
     */
    private Map<String, TeamRosterService.RosterMember> memberIndex(
            List<TeamRosterService.RosterMember> roster) {
        Map<String, TeamRosterService.RosterMember> index = new HashMap<>();
        for (TeamRosterService.RosterMember member : roster) {
            for (String puuid : member.puuids()) {
                index.put(puuid, member);
            }
        }
        return index;
    }
}

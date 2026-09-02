package com.leagueakari.qqbot;

import com.leagueakari.reportimage.ReportImageData;
import com.leagueakari.reportimage.ReportImageRenderer;
import com.leagueakari.service.PostGameCommentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实链路冒烟测试（端到端验证用，非 CI 常规用例）：
 * <p>三个环境变量齐备才运行（QQ_BOT_APP_ID / QQ_BOT_CLIENT_SECRET / PUSH_GROUP_OPEN_ID），
 * CI 与未配置的本地环境自动跳过。跑通后会在车队群真实收到两条消息：
 * ① 战报图（渲染 → 凭证换取 → 分片上传 → 图片消息）
 * ② AI 锐评（真实模型调用 → 文本消息）——与生产触发路径完全一致。</p>
 * <p>本地运行：</p>
 * <pre>
 *   export QQ_BOT_APP_ID=xxx QQ_BOT_CLIENT_SECRET=xxx PUSH_GROUP_OPEN_ID=xxx
 *   mvn test -Dtest=QqRealSendSmokeTest
 * </pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "QQ_BOT_APP_ID", matches = ".+")
@EnabledIfEnvironmentVariable(named = "QQ_BOT_CLIENT_SECRET", matches = ".+")
@EnabledIfEnvironmentVariable(named = "PUSH_GROUP_OPEN_ID", matches = ".+")
class QqRealSendSmokeTest {

    @Autowired
    private QqBotClient qqBotClient;

    @Autowired
    private PostGameCommentService postGameCommentService;

    /**
     * 用例：真实发送战报图 + AI 锐评到车队群（人工去群里确认两条消息）
     */
    @Test
    void sendRealReportImageAndComment() {
        String groupOpenId = System.getenv("PUSH_GROUP_OPEN_ID");

        // ① 渲染一张胜局战报图（样例数据）并真实发送
        byte[] png = new ReportImageRenderer().renderPng(sampleWinData());
        assertThat(png).isNotEmpty();
        qqBotClient.sendGroupImageMessage(groupOpenId, png);
        System.out.println("[smoke] 战报图已发送，请到群里确认图片");

        // ② 真实 AI 调用生成锐评并发送（约 10~30 秒）
        String comment = postGameCommentService.generateComment(sampleSummary());
        assertThat(comment).isNotBlank();
        qqBotClient.sendGroupTextMessage(groupOpenId, comment);
        System.out.println("[smoke] AI 锐评已发送: " + comment);
    }

    /** 样例胜局数据（与 ReportImageRendererTest.winData 同构） */
    private ReportImageData sampleWinData() {
        ReportImageData d = new ReportImageData();
        d.teamName = "iKun";
        d.metaLine = "灵活组排 · 召唤师峡谷 · 2026.08.30 21:47 · 28'42\"";
        d.win = true;
        d.resultLabel = "VICTORY · 胜利";
        d.mainScore = 32;
        d.otherScore = 18;
        d.mainTower = 9;
        d.otherTower = 4;
        d.mainDragon = 3;
        d.otherDragon = 1;
        d.mainBaron = 2;
        d.otherBaron = 0;
        d.mainFirstBlood = true;
        d.footerLeft = "iKun · LEAGUE AKARI";
        d.footerRight = "32:18 · AI 已评阅";
        ReportImageData.Player hero = player("赌书消得泼茶香", "阿狸", 103, 12, 3, 7,
                0.212, 0.126, 1.87, "MVP", "本场 MVP · 全队输出第一", 9.8);
        d.hero = hero;
        d.mainTeam = List.of(hero,
                player("手裂鬼子", "大师", 11, 8, 4, 10, 0.148, 0.079, 1.58, null, null, 8.7),
                player("夜雨听澜", "墨菲特", 54, 4, 2, 14, 0.066, 0.224, 0.86, null, null, 8.2),
                player("小羊别送", "金克丝", 222, 7, 5, 8, 0.176, 0.081, 1.36, null, null, 6.1),
                player("盾辅阿离", "蕾欧娜", 89, 1, 4, 17, 0.028, 0.198, 0.61, null, null, 7.5));
        d.otherTeam = List.of(
                player("青衫仗剑", "雷克顿", 58, 5, 7, 3, 0.094, 0.072, 1.02, null, null, 6.4),
                player("别打野区", "嘉文四世", 59, 2, 8, 4, 0.048, 0.051, 0.74, null, null, 3.5),
                player("午夜诗人", "佐伊", 142, 9, 4, 5, 0.126, 0.019, 1.76, "MVP", null, 8.9),
                player("一杯敬月光", "韦鲁斯", 110, 4, 6, 6, 0.078, 0.034, 0.98, null, null, 5.8),
                player("温柔辅助", "锤石", 412, 1, 7, 9, 0.024, 0.116, 0.44, null, null, 4.9));
        return d;
    }

    private ReportImageData.Player player(String name, String champion, int cid,
                                          int k, int d, int a, double dmg, double taken,
                                          double dpg, String tag, String sub, double op) {
        ReportImageData.Player p = new ReportImageData.Player();
        p.summonerName = name;
        p.championName = champion;
        p.championId = cid;
        p.kills = k;
        p.deaths = d;
        p.assists = a;
        p.damageShare = dmg;
        p.damageTakenShare = taken;
        p.damagePerGold = dpg;
        p.titleTag = tag;
        p.heroSub = sub;
        p.opScore = op;
        return p;
    }

    /** 样例锐评输入摘要（与 BroadcastCoordinator.buildCommentSummary 同构） */
    private Map<String, Object> sampleSummary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("result", "胜利");
        s.put("meta", "灵活组排 · 28分42秒");
        s.put("teamName", "iKun");
        s.put("fleet", List.of(
                Map.of("name", "赌书消得泼茶香", "champion", "阿狸", "kda", "12/3/7", "title", "MVP"),
                Map.of("name", "手裂鬼子", "champion", "大师", "kda", "8/4/10"),
                Map.of("name", "夜雨听澜", "champion", "墨菲特", "kda", "4/2/14"),
                Map.of("name", "小羊别送", "champion", "金克丝", "kda", "7/5/8"),
                Map.of("name", "盾辅阿离", "champion", "蕾欧娜", "kda", "1/4/17")));
        s.put("hero", Map.of("name", "赌书消得泼茶香", "champion", "阿狸", "kda", "12/3/7"));
        return s;
    }
}

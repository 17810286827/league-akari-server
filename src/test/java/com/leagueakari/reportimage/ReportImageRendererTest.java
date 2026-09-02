package com.leagueakari.reportimage;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReportImageRenderer 渲染冒烟测试：
 * 数据模型驱动渲染，验证 PNG 输出结构契约（900px 宽、高度合理、胜/负两态均可渲染）
 */
class ReportImageRendererTest {

    private final ReportImageRenderer renderer = new ReportImageRenderer();

    /** 构造一局完整数据：胜局、含焦点卡与双方阵容 */
    private ReportImageData winData() {
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
        d.footerLeft = "⚔ iKun · LEAGUE AKARI";
        d.footerRight = "32:18 · AI 已评阅";

        ReportImageData.Player mvp = player("赌书消得泼茶香", "阿狸", 103, 12, 3, 7,
                0.212, 0.126, 1.87, "MVP", "本场 MVP · 全队输出第一", 9.8);
        d.hero = mvp;
        d.mainTeam = List.of(
                mvp,
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
                                          double dpg, String tag, String heroSub, double opScore) {
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
        p.heroSub = heroSub;
        p.opScore = opScore;
        return p;
    }

    /** 败局数据：负色系渲染路径 */
    private ReportImageData loseData() {
        ReportImageData d = winData();
        d.win = false;
        d.resultLabel = "DEFEAT · 败北";
        d.mainScore = 18;
        d.otherScore = 27;
        d.mainTower = 3;
        d.otherTower = 8;
        d.mainFirstBlood = false;
        // 败局焦点：ACE（尽力）
        ReportImageData.Player ace = player("盾辅阿离", "诺提勒斯", 111, 1, 4, 12,
                0.042, 0.236, 0.52, "尽力", "尽力局 · 全队视野与开团支柱", 6.3);
        d.hero = ace;
        ReportImageData.Player feed = player("峡谷养鱼人", "佛耶戈", 234, 3, 8, 5,
                0.084, 0.112, 0.88, "背锅", null, 3.2);
        d.mainTeam = List.of(feed, d.hero,
                player("中路杀神", "劫", 238, 6, 6, 4, 0.169, 0.078, 1.41, null, null, 5.8),
                player("小羊别送", "泽丽", 221, 5, 4, 3, 0.143, 0.069, 1.08, null, null, 4.6),
                player("夜雨听澜", "奥恩", 516, 2, 5, 9, 0.061, 0.195, 0.71, null, null, 5.1));
        return d;
    }

    /** 用例：胜局数据渲染输出 900px 宽 PNG，内容非空 */
    @Test
    void renderWinData_producesPngBytes() throws Exception {
        byte[] png = renderer.renderPng(winData());

        assertThat(png).isNotEmpty();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(img).isNotNull();
        assertThat(img.getWidth()).isEqualTo(900);
        assertThat(img.getHeight()).isBetween(700, 1600);
    }

    /** 用例：败局数据渲染不抛错（负色路径）且输出同样结构 */
    @Test
    void renderLoseData_producesPngBytes() throws Exception {
        byte[] png = renderer.renderPng(loseData());

        assertThat(png).isNotEmpty();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(img.getWidth()).isEqualTo(900);
    }

    /** 用例：图像确有绘制内容（非纯背景色）——抽样若干像素与背景不同 */
    @Test
    void render_containsDrawnContent() throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(renderer.renderPng(winData())));

        // 顶部背景应为深蓝渐变，中部应有卡片/文字等亮色像素（比背景亮）
        boolean hasBrightPixel = false;
        for (int x = 100; x < 800 && !hasBrightPixel; x += 37) {
            for (int y = 60; y < img.getHeight() - 40; y += 23) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                // 文字/高亮内容显著亮于深蓝背景（~16,26,46）
                if (r > 120 && g > 120 && b > 120) {
                    hasBrightPixel = true;
                    break;
                }
            }
        }
        assertThat(hasBrightPixel).as("图像应包含文字等高亮像素").isTrue();
    }

    /** 用例：数据为空（无焦点/空阵容）时也能渲染不崩（防御性） */
    @Test
    void renderEmptyData_doesNotThrow() throws Exception {
        ReportImageData d = new ReportImageData();
        d.mainTeam = List.of();
        d.otherTeam = List.of();

        byte[] png = renderer.renderPng(d);

        assertThat(png).isNotEmpty();
    }
}

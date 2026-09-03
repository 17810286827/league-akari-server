package com.leagueakari.reportimage;

import com.leagueakari.service.ChampionIconService;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReportImageRenderer 渲染契约测试：
 * 数据模型驱动渲染，验证 PNG 输出结构（900px 宽、v3 布局高度、胜/负两态可渲染、
 * 真实头像路径可用、文本定宽截断规则）。默认无头（头像服务为 null 时走降级色块圆盘）。
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
                0.212, 0.126, 1.87, "MVP", 9.8);
        d.hero = mvp;
        d.mainTeam = List.of(
                mvp,
                player("手裂鬼子", "大师", 11, 8, 4, 10, 0.148, 0.079, 1.58, null, 8.7),
                player("夜雨听澜", "墨菲特", 54, 4, 2, 14, 0.066, 0.224, 0.86, null, 8.2),
                player("小羊别送", "金克丝", 222, 7, 5, 8, 0.176, 0.081, 1.36, null, 6.1),
                player("盾辅阿离", "蕾欧娜", 89, 1, 4, 17, 0.028, 0.198, 0.61, null, 7.5));
        d.otherTeam = List.of(
                player("青衫仗剑", "雷克顿", 58, 5, 7, 3, 0.094, 0.072, 1.02, null, 6.4),
                player("别打野区", "嘉文四世", 59, 2, 8, 4, 0.048, 0.051, 0.74, null, 3.5),
                player("午夜诗人", "佐伊", 142, 9, 4, 5, 0.126, 0.019, 1.76, "MVP", 8.9),
                player("一杯敬月光", "韦鲁斯", 110, 4, 6, 6, 0.078, 0.034, 0.98, null, 5.8),
                player("温柔辅助", "锤石", 412, 1, 7, 9, 0.024, 0.116, 0.44, null, 4.9));
        return d;
    }

    private ReportImageData.Player player(String name, String champion, int cid,
                                          int k, int d, int a, double dmg, double taken,
                                          double dpg, String tag, double opScore) {
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
        // 败局焦点：ACE（尽力，银徽）
        ReportImageData.Player ace = player("盾辅阿离", "诺提勒斯", 111, 1, 4, 12,
                0.042, 0.236, 0.52, "尽力", 6.3);
        d.hero = ace;
        ReportImageData.Player feed = player("峡谷养鱼人", "佛耶戈", 234, 3, 8, 5,
                0.084, 0.112, 0.88, "背锅", 3.2);
        d.mainTeam = List.of(feed, d.hero,
                player("中路杀神", "劫", 238, 6, 6, 4, 0.169, 0.078, 1.41, null, 5.8),
                player("小羊别送", "泽丽", 221, 5, 4, 3, 0.143, 0.069, 1.08, null, 4.6),
                player("夜雨听澜", "奥恩", 516, 2, 5, 9, 0.061, 0.195, 0.71, null, 5.1));
        return d;
    }

    /** 用例：胜局数据渲染输出 900px 宽 PNG，高度符合 v3 布局（焦点卡 150 + 行高 78） */
    @Test
    void renderWinData_producesPngBytes() throws Exception {
        byte[] png = renderer.renderPng(winData());

        assertThat(png).isNotEmpty();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(img).isNotNull();
        assertThat(img.getWidth()).isEqualTo(900);
        // v3 布局公式：PAD + 顶栏84 + 资源条54 + 焦点卡(16+150+16) + 标题/列头70 + 5×行高78 + 底栏36 + PAD
        assertThat(img.getHeight()).isEqualTo(894);
    }

    /** 用例：败局数据渲染不抛错（负色路径）且输出同样结构 */
    @Test
    void renderLoseData_producesPngBytes() throws Exception {
        byte[] png = renderer.renderPng(loseData());

        assertThat(png).isNotEmpty();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(img.getWidth()).isEqualTo(900);
        assertThat(img.getHeight()).isEqualTo(894);
    }

    /** 用例：注入头像服务（真实头像路径）时渲染不抛错，布局高度不变 */
    @Test
    void renderWithIconService_producesPng() throws Exception {
        ReportImageRenderer withIcons = new ReportImageRenderer(new FakeIconService());

        BufferedImage img = ImageIO.read(new ByteArrayInputStream(withIcons.renderPng(winData())));

        assertThat(img).isNotNull();
        assertThat(img.getWidth()).isEqualTo(900);
        assertThat(img.getHeight()).isEqualTo(894);
    }

    /** 用例：超长召唤师名（30 字）不抛错，且像素级验证被截断在列内（不越界） */
    @Test
    void renderWithLongSummonerName_clipsWithinColumn() throws Exception {
        ReportImageData d = winData();
        d.otherTeam.get(4).summonerName =
                "一杯敬月光与酒与晚风与小桥流水人家灯火阑珊夜未央人未眠";
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(renderer.renderPng(d)));

        assertThat(img).isNotNull();
        assertThat(img.getWidth()).isEqualTo(900);
        // 红方列最后一行（第5行）主行名字带：列 x=469..888，名字 y 带≈714..744（布局公式推算）。
        // 召唤师名 #e9f0fb（蓝通道≈251）显著亮于 KDA #b9c7db（≈219）与背景——扫最亮像素最右位置。
        int rightmost = -1;
        for (int y = 714; y <= 744; y++) {
            for (int x = 530; x < 895; x++) {
                if ((img.getRGB(x, y) & 0xff) > 240) {
                    rightmost = Math.max(rightmost, x);
                }
            }
        }
        // 若未截断，30 字 ×15px≈450px 会从 x=531 画到 ~980（越出列与卡片）
        assertThat(rightmost).as("长召唤师名必须被省略号截断在列内").isBetween(700, 860);
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

    /** 用例：顶栏右上角绘制比分文字（回归：比分数据漏填曾导致显示 0 : 0） */
    @Test
    void renderHeader_showsScoreTextAtTopRight() throws Exception {
        ReportImageData d = winData();
        d.mainScore = 32;
        d.otherScore = 18;
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(renderer.renderPng(d)));

        // 比分 36px 粗体右对齐到 WIDTH-PAD，baseline≈y+98：扫描右上比分区找亮色文字像素
        boolean hasScorePixel = false;
        for (int x = 620; x < 870 && !hasScorePixel; x += 5) {
            for (int y = 62; y <= 108; y += 3) {
                int rgb = img.getRGB(x, y);
                if (((rgb >> 16) & 0xff) > 200 && ((rgb >> 8) & 0xff) > 200 && (rgb & 0xff) > 200) {
                    hasScorePixel = true;
                    break;
                }
            }
        }
        assertThat(hasScorePixel).as("顶栏右上角应绘制比分").isTrue();
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

    // ---------- 定宽省略号截断（ellipsize）单元用例 ----------

    private FontMetrics metrics(float size) {
        BufferedImage probe = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = probe.createGraphics();
        return g.getFontMetrics(new Font(Font.SANS_SERIF, Font.PLAIN, Math.round(size)));
    }

    /** 用例：短文本不截断，原样返回 */
    @Test
    void ellipsize_shortText_unchanged() {
        String text = "夜雨听澜";

        String out = ReportImageRenderer.ellipsize(text, metrics(15), 400);

        assertThat(out).isEqualTo(text);
    }

    /** 用例：超宽文本截断并追加省略号，且实测宽度不超过限定宽 */
    @Test
    void ellipsize_longText_fitsMaxWidth() {
        String text = "一杯敬月光与酒与晚风与小桥流水人家灯火阑珊夜未央人未眠";
        FontMetrics fm = metrics(15);

        String out = ReportImageRenderer.ellipsize(text, fm, 200);

        assertThat(out).endsWith("…");
        assertThat(out).hasSizeLessThan(text.length());
        assertThat(fm.stringWidth(out)).isLessThanOrEqualTo(200);
    }

    /** 用例：窄到连省略号都放不下时返回空串（宁可缺字不破框） */
    @Test
    void ellipsize_extremeNarrow_returnsEmptyOrSuffix() {
        FontMetrics fm = metrics(15);
        // 省略号本身宽于限宽 → 返回空；仅容得下省略号 → 返回省略号
        assertThat(ReportImageRenderer.ellipsize("很长很长的名字", fm, 1)).isEqualTo("");
    }

    /** 用例：null 文本安全返回 */
    @Test
    void ellipsize_nullText_safe() {
        assertThat(ReportImageRenderer.ellipsize(null, metrics(15), 100)).isNull();
    }

    /** 假头像服务：不触网，固定返回 16x16 图（覆盖真实头像绘制分支） */
    static class FakeIconService extends ChampionIconService {
        FakeIconService() {
            super(null);
        }

        @Override
        protected BufferedImage fetch(int championId) {
            BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(0x4f8dff));
            g.fillRect(0, 0, 16, 16);
            g.dispose();
            return img;
        }
    }
}

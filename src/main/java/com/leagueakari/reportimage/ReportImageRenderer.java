package com.leagueakari.reportimage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 战报图渲染器（Java2D headless，无系统字体依赖）：
 * 按视觉原型"方案 C v2"绘制 900px 宽对局战报 PNG——深蓝渐变底、圆角分层卡片、
 * 胜负胶囊与比分、资源对比条、焦点玩家大卡（含三指标）、双方阵容行（输出/承伤条 + 伤转）。
 * <p>输入为纯 DTO {@link ReportImageData}（与数据库解耦），输出 PNG 字节；
 * 字体随包内置思源黑体（/fonts/SourceHanSansSC-Regular.otf，OFL 可商用），
 * 加载失败时回退逻辑字体（极端场景中文可能缺字形，仅作兜底）。</p>
 */
@Slf4j
@Service
public class ReportImageRenderer {

    // ---------- 画布与布局常量（原型 900px 宽基准） ----------
    private static final int WIDTH = 900;
    private static final int PAD = 36;

    // ---------- 调色板（对齐原型 C v2 与海克斯魔典衍生色） ----------
    private static final Color BG_TOP = new Color(0x101a2e);
    private static final Color BG_BOTTOM = new Color(0x0b1220);
    private static final Color TEXT_MAIN = new Color(0xe9f0fb);
    private static final Color TEXT_SUB = new Color(0x7e92ad);
    private static final Color TEXT_DIM = new Color(0x6d819d);
    private static final Color TEXT_KDA = new Color(0xb9c7db);
    private static final Color WIN_BLUE = new Color(0x4b7be5);
    private static final Color WIN_BLUE_LIGHT = new Color(0x8ab0ff);
    private static final Color WIN_BLUE_GRAD_A = new Color(0x4f8dff);
    private static final Color WIN_BLUE_GRAD_B = new Color(0x1d4fae);
    private static final Color LOSE_RED = new Color(0xe03e52);
    private static final Color LOSE_RED_LIGHT = new Color(0xff8a98);
    private static final Color LOSE_RED_GRAD_A = new Color(0xa12b3c);
    private static final Color LOSE_RED_GRAD_B = new Color(0xff6b7d);
    private static final Color GOLD = new Color(0xffd76e);
    private static final Color ROW_BG = new Color(255, 255, 255, 9);
    private static final Color ROW_BORDER = new Color(255, 255, 255, 13);
    private static final Color Mvp_ROW_BORDER = new Color(255, 215, 110, 128);
    private static final Color Mvp_ROW_BG = new Color(255, 215, 110, 15);
    private static final Color BAR_TRACK = new Color(255, 255, 255, 20);

    /** 英雄头像底色板：按 championId 取模映射（原型固定色集） */
    private static final int[] HERO_COLORS = {
            0xd98a3d, 0x2f6fdd, 0xa05ce6, 0xe0a21e, 0x3fa9a0,
            0xc0392b, 0x4b8a3d, 0x7f5fc0, 0xb5532f, 0x3a7d5c,
            0x5b8cff, 0xd94f6b, 0x4d9e8f, 0x9a6b3f, 0x6b5fd9,
            0xcf7a3a, 0x2f8f6f, 0xb04d5a
    };

    /** 内置思源黑体（classpath），懒加载一次 */
    private static volatile Font baseFont;

    // ---------- 公开入口 ----------

    /**
     * 渲染战报图为 PNG 字节
     *
     * @param data 聚合后的战报数据（行列必须已排好序）
     */
    public byte[] renderPng(ReportImageData data) {
        int height = layoutHeight(data);
        BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            // 背景垂直渐变：深蓝卡片底（原型 #101a2e → #0b1220）
            g.setPaint(new GradientPaint(0, 0, BG_TOP, 0, height, BG_BOTTOM));
            g.fillRect(0, 0, WIDTH, height);

            int y = PAD;
            y = drawHeader(g, data, y);
            y = drawResourceBar(g, data, y);
            y = drawHeroCard(g, data, y);
            y = drawRosters(g, data, y);
            drawFooter(g, data, y);
            return toPngBytes(image);
        } finally {
            g.dispose();
        }
    }

    // ---------- 区块绘制 ----------

    /** 顶栏：左（队名 + 元信息）与右（胜负胶囊 + 比分） */
    private int drawHeader(Graphics2D g, ReportImageData d, int y) {
        int innerRight = WIDTH - PAD;
        // 左：队名
        drawText(g, d.teamName == null ? "车队" : d.teamName,
                PAD, y + 24, font(22, Font.BOLD), TEXT_MAIN, LEFT);
        String meta = d.metaLine == null ? "" : d.metaLine;
        drawText(g, meta, PAD, y + 48, font(13, Font.PLAIN), TEXT_SUB, LEFT);

        // 右：胜负胶囊（圆角全透明底 + 彩色描边）
        String pill = d.resultLabel == null ? "" : d.resultLabel;
        Color pillFg = d.win ? WIN_BLUE_LIGHT : LOSE_RED_LIGHT;
        Color pillBorder = d.win ? WIN_BLUE : LOSE_RED;
        FontMetrics pm = g.getFontMetrics(font(13, Font.BOLD));
        int pillW = pm.stringWidth(pill) + 32;
        int pillH = 28;
        int pillX = innerRight - pillW;
        g.setColor(new Color(pillBorder.getRed(), pillBorder.getGreen(), pillBorder.getBlue(),
                d.win ? 128 : 128));
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(pillX, y, pillW, pillH, pillH, pillH);
        drawText(g, pill, innerRight - pillW / 2f, y + pillH / 2f + 4,
                font(13, Font.BOLD), pillFg, CENTER);

        // 右：比分（胶囊下方）
        String score = d.mainScore + " : " + d.otherScore;
        drawText(g, score, innerRight, y + pillH + 34, font(36, Font.BOLD), TEXT_MAIN, RIGHT);
        return y + pillH + 44;
    }

    /** 资源对比条：推塔/小龙/大龙/一血（主队占优数值高亮，-1 格跳过） */
    private int drawResourceBar(Graphics2D g, ReportImageData d, int y) {
        int yBar = y + 12;
        int barH = 42;
        int barX = PAD;
        int barW = WIDTH - 2 * PAD;
        // 条底：半透明圆角
        g.setColor(new Color(255, 255, 255, 8));
        g.fillRoundRect(barX, yBar, barW, barH, 12, 12);
        g.setColor(new Color(255, 255, 255, 15));
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(barX, yBar, barW, barH, 12, 12);

        // 各资源格：标签 + 主:客 数值
        java.util.List<String[]> cells = java.util.List.of(
                new String[]{"推塔", num(d.mainTower), num(d.otherTower)},
                new String[]{"小龙", num(d.mainDragon), num(d.otherDragon)},
                new String[]{"大龙", num(d.mainBaron), num(d.otherBaron)});
        int cellX = PAD + 26;
        int baseline = yBar + barH / 2 + 5;
        for (String[] cell : cells) {
            if (cell[1].isEmpty()) {
                continue;
            }
            drawText(g, cell[0], cellX, baseline, font(12, Font.PLAIN), TEXT_SUB, LEFT);
            int vx = cellX + 34;
            drawText(g, cell[1], vx, baseline, font(13, Font.BOLD), TEXT_KDA, LEFT);
            drawText(g, ":", vx + 20, baseline, font(13, Font.PLAIN), TEXT_DIM, LEFT);
            drawText(g, cell[2], vx + 36, baseline, font(13, Font.BOLD), TEXT_KDA, LEFT);
            cellX += 30 + 34 + 70 + 8;
        }
        // 一血格（有数据才画）
        if (d.mainFirstBlood != null) {
            String fb = d.mainFirstBlood ? "一血 主队" : "一血 对方";
            drawText(g, fb, cellX, baseline, font(12, Font.PLAIN), TEXT_SUB, LEFT);
        }
        return yBar + barH;
    }

    /** 焦点玩家大卡：英雄圆盘 + 名字/副行/三指标；右侧 KDA 大字 + OP Score */
    private int drawHeroCard(Graphics2D g, ReportImageData d, int y) {
        ReportImageData.Player hero = d.hero;
        int cardH = 128;
        int cardX = PAD;
        int cardW = WIDTH - 2 * PAD;
        // 卡底：胜蓝/负红 12% 渐变 + 描边
        Color tintA = d.win ? new Color(75, 123, 229, 36) : new Color(224, 62, 82, 33);
        Color tintB = d.win ? new Color(75, 123, 229, 8) : new Color(224, 62, 82, 8);
        g.setPaint(new GradientPaint(cardX, y, tintA, cardX + cardW, y, tintB));
        g.fillRoundRect(cardX, y, cardW, cardH, 16, 16);
        g.setColor(d.win ? new Color(75, 123, 229, 72) : new Color(224, 62, 82, 66));
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(cardX, y, cardW, cardH, 16, 16);

        if (hero != null) {
            // 英雄圆盘（车队色渐变 + 英雄名）
            drawHeroDot(g, cardX + 24, y + 38, 52, hero.championName, hero.championId, 14);
            int tx = cardX + 96;
            drawText(g, hero.summonerName, tx, y + 44, font(19, Font.BOLD), TEXT_MAIN, LEFT);
            String sub = hero.heroSub == null ? "" : hero.heroSub;
            drawText(g, sub, tx, y + 66, font(12, Font.PLAIN), TEXT_SUB, LEFT);
            // 三指标行
            int my = y + 94;
            drawMetric(g, tx, my, "输出占比", formatPercent(hero.damageShare), TEXT_MAIN);
            drawMetric(g, tx + 150, my, "承伤占比", formatPercent(hero.damageTakenShare), TEXT_MAIN);
            drawMetric(g, tx + 300, my, "伤害转化", formatPercent(hero.damagePerGold), GOLD);

            // 右侧：OP Score 圆徽 + KDA 大字
            int rx = cardX + cardW - 24;
            drawOpScore(g, rx, y + 44, hero.opScore);
            String kda = hero.kills + " / " + hero.deaths + " / " + hero.assists;
            drawText(g, kda, rx - 84, y + 70, font(24, Font.BOLD), TEXT_MAIN, RIGHT);
            drawText(g, "K / D / A", rx - 84, y + 90, font(10, Font.PLAIN), TEXT_DIM, RIGHT);
        }
        return y + cardH + 16;
    }

    /** 阵容两列：列头 + 每列 5 行（行含头像/名字/KDA/称号与三指标） */
    private int drawRosters(Graphics2D g, ReportImageData d, int y) {
        // 区块标题
        drawText(g, "阵容对阵 LINEUP", PAD, y + 8, font(13, Font.PLAIN), TEXT_SUB, LEFT);
        int titleBottom = y + 34;

        int colGap = 14;
        int colW = (WIDTH - 2 * PAD - colGap) / 2;
        // 列头
        int headY = titleBottom + 10;
        drawColumnHead(g, PAD, headY, d.win, true);
        drawColumnHead(g, PAD + colW + colGap, headY, d.win, false);

        // 行区
        int rowY = headY + 26;
        drawColumnRows(g, PAD, rowY, colW, d.mainTeam, d.win);
        drawColumnRows(g, PAD + colW + colGap, rowY, colW, d.otherTeam, d.win);
        int rowsH = 5 * ROW_H;
        return rowY + rowsH + 6;
    }

    private static final int ROW_H = 62;

    /** 列头：队色圆点 + 队名（蓝方·舰队（胜）/ 红方·对手） */
    private void drawColumnHead(Graphics2D g, int x, int y, boolean win, boolean isMain) {
        Color dotColor = isMain ? (win ? WIN_BLUE : LOSE_RED) : (win ? LOSE_RED : WIN_BLUE);
        g.setColor(dotColor);
        g.fill(new Ellipse2D.Double(x, y + 2, 8, 8));
        String label = (isMain ? "蓝方" : "红方") + " · "
                + (isMain ? "舰队" : "对手") + (win ? "（胜）" : "（负）");
        drawText(g, label, x + 18, y + 12, font(13, Font.PLAIN), TEXT_SUB, LEFT);
    }

    /** 单列 5 行：每行 = 主行（头像圆 + 名字 + KDA + 称号）+ 指标行（输出/承伤条 + 伤转） */
    private void drawColumnRows(Graphics2D g, int x, int y, int colW, List<ReportImageData.Player> rows,
                                boolean mainSideWin) {
        for (int i = 0; i < rows.size(); i++) {
            ReportImageData.Player p = rows.get(i);
            int ry = y + i * ROW_H;
            boolean mvp = p.titleTag != null;
            // 行底
            g.setColor(mvp ? Mvp_ROW_BG : ROW_BG);
            g.fillRoundRect(x, ry, colW, ROW_H - 6, 10, 10);
            g.setColor(mvp ? Mvp_ROW_BORDER : ROW_BORDER);
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(x, ry, colW, ROW_H - 6, 10, 10);

            // 主行：头像圆（34）+ 名字；右侧 KDA 与称号
            drawHeroDot(g, x + 10, ry + 8, 34, p.championName, p.championId, 10);
            int tx = x + 56;
            drawText(g, p.summonerName, tx, ry + 26, font(15, Font.BOLD), TEXT_MAIN, LEFT);
            String kda = p.kills + "/" + p.deaths + "/" + p.assists;
            drawText(g, kda, x + colW - 14, ry + 26, font(14, Font.PLAIN), TEXT_KDA, RIGHT);
            if (p.titleTag != null) {
                // 称号小标签（金/灰底）
                drawTitleTag(g, p.titleTag, x + colW - 14, ry + 6);
            }

            // 指标行：输出占比（队色条）· 承伤占比（队色条）· 伤转（金，无条）
            int my = ry + 42;
            int mx = x + 46;
            boolean sideColor = mainSideWin; // 条色：主队用胜色、客队用败色
            drawBarMetric(g, mx, my, "输出", p.damageShare, sideColor ? WIN_BLUE_GRAD_A : LOSE_RED_GRAD_A,
                    sideColor ? WIN_BLUE_GRAD_B : LOSE_RED_GRAD_B);
            drawBarMetric(g, mx + 150, my, "承伤", p.damageTakenShare, sideColor ? WIN_BLUE_GRAD_A : LOSE_RED_GRAD_A,
                    sideColor ? WIN_BLUE_GRAD_B : LOSE_RED_GRAD_B);
            // 伤转：金色数字（量纲与占比不同，不画条）
            drawText(g, "伤转", mx + 300, my + 10, font(10, Font.PLAIN), TEXT_DIM, LEFT);
            drawText(g, formatPercent(p.damagePerGold), mx + 300 + 30, my + 11,
                    font(12, Font.BOLD), GOLD, LEFT);
        }
    }

    /** 底栏：分隔线 + 两端署名 */
    private void drawFooter(Graphics2D g, ReportImageData d, int y) {
        g.setColor(new Color(126, 146, 173, 38));
        g.fillRect(PAD, y, WIDTH - 2 * PAD, 1);
        drawText(g, d.footerLeft == null ? "" : d.footerLeft, PAD, y + 24,
                font(13, Font.BOLD), new Color(200, 170, 110, 204), LEFT);
        drawText(g, d.footerRight == null ? "" : d.footerRight, WIDTH - PAD, y + 24,
                font(12, Font.PLAIN), TEXT_DIM, RIGHT);
    }

    // ---------- 基础图元 ----------

    /** 条式指标：标签 + 彩色渐变条 + 百分比值 */
    private void drawBarMetric(Graphics2D g, int x, int y, String label, double share,
                               Color gradA, Color gradB) {
        drawText(g, label, x, y + 10, font(10, Font.PLAIN), TEXT_DIM, LEFT);
        // 条：宽度按 30% 封顶（占比超出视为满条）
        int trackW = 34;
        int trackX = x + 34;
        g.setColor(BAR_TRACK);
        g.fillRoundRect(trackX, y + 3, trackW, 4, 2, 2);
        int fillW = Math.max(2, (int) Math.round(Math.min(1.0, share / 0.30) * trackW));
        g.setPaint(new GradientPaint(trackX, 0, gradA, trackX + fillW, 0, gradB));
        g.fillRoundRect(trackX, y + 3, fillW, 4, 2, 2);
        drawText(g, formatPercent(share), trackX + trackW + 6, y + 11,
                font(12, Font.BOLD), TEXT_KDA, LEFT);
    }

    /** 文本指标（焦点卡用，无条）：标签灰 + 值白/金 */
    private void drawMetric(Graphics2D g, int x, int y, String label, String value, Color valueColor) {
        Font labelFont = font(12, Font.PLAIN);
        drawText(g, label, x, y, labelFont, TEXT_SUB, LEFT);
        // 值紧跟标签后：用 FontMetrics 实测标签宽度（中文字宽 ≠ 字号，不能按字符数估算）
        int vx = x + g.getFontMetrics(labelFont).stringWidth(label) + 8;
        drawText(g, value, vx, y, font(13, Font.BOLD), valueColor, LEFT);
    }

    /** 英雄圆盘：渐变圆底（车队色或英雄色） + 中央英雄中文名 + 圆描边 */
    private void drawHeroDot(Graphics2D g, int x, int y, int diameter, String championName,
                             int championId, int textSize) {
        // 车队色渐变（蓝）统一英雄圆盘；底色微调避免与红方混淆时仍可读
        g.setPaint(new GradientPaint(x, y, WIN_BLUE_GRAD_A, x + diameter, y + diameter, WIN_BLUE_GRAD_B));
        g.fill(new Ellipse2D.Double(x, y, diameter, diameter));
        g.setColor(new Color(255, 255, 255, 60));
        g.setStroke(new BasicStroke(1f));
        g.draw(new Ellipse2D.Double(x, y, diameter, diameter));
        String name = championName == null ? "?" : championName;
        // 过长英雄名（4 字以上）缩小字号，确保不溢出圆盘
        int size = name.length() > 4 ? Math.max(8, textSize - 3) : textSize;
        drawText(g, name, x + diameter / 2f, y + diameter / 2f + size / 2f - 1,
                font(size, Font.BOLD), Color.WHITE, CENTER);
    }

    /** OP Score 圆徽：金边圆 + 金色数值 */
    private void drawOpScore(Graphics2D g, int rightX, int y, double opScore) {
        int d = 44;
        int x = rightX - d;
        g.setColor(new Color(255, 215, 110, 36));
        g.fill(new Ellipse2D.Double(x, y, d, d));
        g.setColor(new Color(255, 215, 110, 128));
        g.setStroke(new BasicStroke(1f));
        g.draw(new Ellipse2D.Double(x, y, d, d));
        String text = String.format("%.1f", opScore);
        drawText(g, text, x + d / 2f, y + d / 2f + 6, font(16, Font.BOLD), GOLD, CENTER);
    }

    /** 称号小标签（行尾）：金底（MVP 类）或灰底（背锅）圆角小方块 */
    private void drawTitleTag(Graphics2D g, String tag, int rightX, int y) {
        boolean gold = "MVP".equals(tag) || "SVP".equals(tag) || "尽力".equals(tag);
        Font tagFont = font(10, Font.BOLD);
        FontMetrics fm = g.getFontMetrics(tagFont);
        int tw = fm.stringWidth(tag) + 14;
        int th = 18;
        int x = rightX - tw;
        if (gold) {
            g.setColor(new Color(255, 215, 110, 230));
            g.fillRoundRect(x, y, tw, th, 6, 6);
            drawText(g, tag, x + tw / 2f, y + th / 2f + 3, tagFont, new Color(0x1a1405), CENTER);
        } else {
            g.setColor(new Color(138, 148, 168, 220));
            g.fillRoundRect(x, y, tw, th, 6, 6);
            drawText(g, tag, x + tw / 2f, y + th / 2f + 3, tagFont, new Color(0x10141c), CENTER);
        }
    }

    /** 布局高度：按固定区块 + 5 行阵容累加（与 draw 顺序一致） */
    private int layoutHeight(ReportImageData d) {
        int h = PAD;
        h += 84;            // 顶栏
        h += 12 + 42;       // 资源条（含前距）
        h += 16 + 128 + 16; // 焦点卡
        h += 34 + 10 + 26;  // 标题与列头
        h += 5 * ROW_H + 6; // 阵容行
        h += 36;            // 底栏
        return h + PAD;
    }

    // ---------- 字体与工具 ----------

    /**
     * 取字体：内置思源黑体按需派生（Bold 用合成粗体），
     * 保证渲染不依赖运行环境是否安装中文字体
     */
    private static Font font(float size, int style) {
        if (baseFont == null) {
            synchronized (ReportImageRenderer.class) {
                if (baseFont == null) {
                    baseFont = loadBaseFont();
                }
            }
        }
        return baseFont.deriveFont(style, size);
    }

    /** 从 classpath 加载思源黑体；失败回退逻辑字体（仅极端场景出现豆腐块） */
    private static Font loadBaseFont() {
        try (InputStream in = ReportImageRenderer.class.getResourceAsStream(
                "/fonts/SourceHanSansSC-Regular.otf")) {
            if (in != null) {
                Font font = Font.createFont(Font.TRUETYPE_FONT, in);
                log.info("Report image font loaded: {}", font.getFontName());
                return font;
            }
        } catch (IOException | java.awt.FontFormatException e) {
            log.warn("Failed to load bundled font, fallback to logical font: {}", e.getMessage());
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 16);
    }

    /** 渲染 BufferedImage → PNG 字节 */
    private byte[] toPngBytes(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("战报图 PNG 编码失败", e);
        }
    }

    /** 数值格式化：占比（1.0 → 100.0%）与伤转（1.5 → 150%）共用百分号 */
    private static String formatPercent(double ratio) {
        if (Double.isNaN(ratio) || Double.isInfinite(ratio)) {
            return "0%";
        }
        return Math.round(ratio * 1000) / 10.0 + "%";
    }

    /** 资源数值：-1 视为无数据返回空串（格子跳过） */
    private static String num(int v) {
        return v < 0 ? "" : String.valueOf(v);
    }

    // ---------- 文本对齐 ----------
    private static final int LEFT = 0;
    private static final int CENTER = 1;
    private static final int RIGHT = 2;

    /** 绘制文本（x 为锚点，按对齐方式计算），返回基线 y（不变） */
    private void drawText(Graphics2D g, String text, float x, float y, Font f, Color c, int align) {
        g.setFont(f);
        g.setColor(c);
        FontMetrics fm = g.getFontMetrics(f);
        float drawX = x;
        if (align == CENTER) {
            drawX = x - fm.stringWidth(text) / 2f;
        } else if (align == RIGHT) {
            drawX = x - fm.stringWidth(text);
        }
        g.drawString(text, drawX, y);
    }
}

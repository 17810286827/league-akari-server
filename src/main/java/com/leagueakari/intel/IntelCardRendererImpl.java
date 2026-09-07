package com.leagueakari.intel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 敌方情报卡渲染器（Java2D headless）：900px 宽社交卡片 PNG。
 * <p>布局（自上而下）：标题区（车队名 + 红蓝方醒目胶囊 + 队列名）→ 敌方 5 人行
 * （召唤师名 + 段位 + 近 20 局窗口胜率，小样本标局数不裸百分比）→ 开黑分组视觉
 * （同组同色边框分组，无开黑时显示"全员路人"）。缺失的段位/历史如实渲染"未知"。</p>
 * <p>复用战报图渲染器的视觉语言（深蓝渐变底、同款调色板、思源黑体字体资源），
 * 但不复用其战报图专用结构——情报卡是单列 5 人 + 分组标注，布局不同。</p>
 */
@Slf4j
@Component
public class IntelCardRendererImpl implements IntelCardRenderer {

    // ---------- 画布与布局常量 ----------
    private static final int WIDTH = 900;
    private static final int PAD = 36;
    private static final int ROW_H = 72;

    // ---------- 调色板（对齐战报图风格） ----------
    private static final Color BG_TOP = new Color(0x101a2e);
    private static final Color BG_BOTTOM = new Color(0x0b1220);
    private static final Color TEXT_MAIN = new Color(0xe9f0fb);
    private static final Color TEXT_SUB = new Color(0x7e92ad);
    private static final Color TEXT_DIM = new Color(0x6d819d);
    private static final Color BLUE = new Color(0x4b7be5);
    private static final Color BLUE_LIGHT = new Color(0x8ab0ff);
    private static final Color RED = new Color(0xe03e52);
    private static final Color RED_LIGHT = new Color(0xff8a98);
    private static final Color GOLD = new Color(0xffd76e);
    private static final Color ROW_BG = new Color(255, 255, 255, 9);
    private static final Color ROW_BORDER = new Color(255, 255, 255, 13);
    private static final Color TEXT_KDA = new Color(0xb9c7db);

    /** 英雄头像降级色块色板：按 championId 取模 */
    private static final int[] HERO_COLORS = {
            0xd98a3d, 0x2f6fdd, 0xa05ce6, 0xe0a21e, 0x3fa9a0,
            0xc0392b, 0x4b8a3d, 0x7f5fc0, 0xb5532f, 0x3a7d5c,
            0x5b8cff, 0xd94f6b, 0x4d9e8f, 0x9a6b3f, 0x6b5fd9,
            0xcf7a3a, 0x2f8f6f, 0xb04d5a
    };

    /** 开黑分组边框色板：按组序号取模（同组同色，视觉分组） */
    private static final Color[] GROUP_COLORS = {
            new Color(0xffd76e), new Color(0x8ab0ff), new Color(0xff8a98),
            new Color(0x7ee2b8), new Color(0xd7a6ff)
    };

    /** 文本对齐常量 */
    private static final int LEFT = 0;
    private static final int CENTER = 1;
    private static final int RIGHT = 2;

    /** 内置思源黑体（classpath），懒加载一次 */
    private static volatile Font baseFont;

    /** 英雄头像服务（null 时全部降级色块圆盘，供单测/降级路径） */
    private final com.leagueakari.gamedata.ChampionIconService iconService;

    /** 无头构造：不注入头像服务，头像一律降级色块圆盘（单测友好） */
    public IntelCardRendererImpl() {
        this.iconService = null;
    }

    /** Spring 构造：注入头像服务，绘制真实英雄头像 */
    public IntelCardRendererImpl(com.leagueakari.gamedata.ChampionIconService iconService) {
        this.iconService = iconService;
    }

    @Override
    public byte[] render(EnemyIntel intel) {
        int height = layoutHeight(intel);
        BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, BG_TOP, 0, height, BG_BOTTOM));
            g.fillRect(0, 0, WIDTH, height);

            int y = PAD;
            y = drawHeader(g, intel, y);
            y = drawTable(g, intel, y);
            return toPngBytes(image);
        } finally {
            g.dispose();
        }
    }

    /** 卡片总高度：标题区 + 表头 + 敌方行数 + 分组区 + 底边距 */
    private int layoutHeight(EnemyIntel intel) {
        int headerH = 96;
        int tableHeaderH = 34;
        int enemiesH = intel.getEnemies().size() * ROW_H;
        int groupsH = intel.getPremadeGroups().isEmpty() ? 40 : (36 + intel.getPremadeGroups().size() * 30);
        return PAD + headerH + tableHeaderH + enemiesH + groupsH + 24;
    }

    /** 标题区：车队名 + 红蓝方胶囊 + 队列名 */
    private int drawHeader(Graphics2D g, EnemyIntel intel, int y) {
        // 左：车队名
        drawText(g, intel.getTeamName() == null ? "车队" : intel.getTeamName(),
                PAD, y + 26, font(22, Font.BOLD), TEXT_MAIN, LEFT);
        // 副行：队列名
        drawText(g, intel.getQueueName() == null ? "对局" : intel.getQueueName(),
                PAD, y + 50, font(13, Font.PLAIN), TEXT_SUB, LEFT);

        // 右：红蓝方醒目胶囊
        boolean blue = intel.getFriendlyTeamId() != null && intel.getFriendlyTeamId() == 100;
        String sideLabel = blue ? "蓝色方" : "红色方";
        Color sideFg = blue ? BLUE_LIGHT : RED_LIGHT;
        Color sideBorder = blue ? BLUE : RED;
        FontMetrics pm = g.getFontMetrics(font(15, Font.BOLD));
        int pillW = pm.stringWidth(sideLabel) + 40;
        int pillH = 34;
        int pillX = WIDTH - PAD - pillW;
        g.setColor(new Color(sideBorder.getRed(), sideBorder.getGreen(), sideBorder.getBlue(), 128));
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(pillX, y + 8, pillW, pillH, pillH, pillH);
        drawText(g, sideLabel, pillX + pillW / 2f, y + 8 + pillH / 2f + 5,
                font(15, Font.BOLD), sideFg, CENTER);

        // 分隔线
        g.setColor(new Color(255, 255, 255, 14));
        g.drawLine(PAD, y + 78, WIDTH - PAD, y + 78);
        return y + 96;
    }

    /** E 变体：数据密集表格（表头 + 每行头像/英雄名/玩家名/段位/胜率/开黑） */
    private int drawTable(Graphics2D g, EnemyIntel intel, int y) {
        // 分组索引映射：puuid → 组序号
        Map<String, Integer> groupIndexOf = new HashMap<>();
        List<PremadeDetector.PremadeGroup> groups = intel.getPremadeGroups();
        for (int i = 0; i < groups.size(); i++) {
            for (String puuid : groups.get(i).getPlayers()) {
                groupIndexOf.put(puuid, i);
            }
        }
        // 组胜率（组序号 → PremadeWinRate，行内展示搭档胜率）
        Map<Integer, EnemyIntel.PremadeWinRate> wrByGroup = new HashMap<>();
        List<EnemyIntel.PremadeWinRate> winRates = intel.getPremadeWinRates();
        for (int i = 0; i < groups.size(); i++) {
            wrByGroup.put(i, winRates != null && i < winRates.size() ? winRates.get(i) : null);
        }

        // 表头
        int headerY = y;
        drawTableHeader(g, headerY);

        // 数据行
        int rowY = y + 34;
        for (int i = 0; i < intel.getEnemies().size(); i++) {
            EnemyIntel.EnemyPlayer e = intel.getEnemies().get(i);
            Integer groupIdx = groupIndexOf.get(e.getPuuid());
            drawTableRow(g, e, rowY, i, groupIdx, groupIdx != null ? wrByGroup.get(groupIdx) : null);
            rowY += ROW_H;
        }

        // 开黑分组说明（表格下方）
        return drawGroups(g, intel, rowY + 4);
    }

    /** 表头：英雄 / 玩家 / 段位 / 近20局 / 开黑 */
    private void drawTableHeader(Graphics2D g, int y) {
        Font hf = font(11, Font.BOLD);
        g.setColor(new Color(255, 255, 255, 10));
        g.fillRoundRect(PAD, y, WIDTH - 2 * PAD, 26, 8, 8);
        drawText(g, "英雄", PAD + 64, y + 18, hf, TEXT_DIM, LEFT);
        drawText(g, "玩家", PAD + 200, y + 18, hf, TEXT_DIM, LEFT);
        drawText(g, "段位", PAD + 420, y + 18, hf, TEXT_DIM, LEFT);
        drawText(g, "近20局", PAD + 560, y + 18, hf, TEXT_DIM, LEFT);
        drawText(g, "开黑", WIDTH - PAD - 16, y + 18, hf, TEXT_DIM, RIGHT);
    }

    /** 单个敌方玩家数据行 */
    private void drawTableRow(Graphics2D g, EnemyIntel.EnemyPlayer e, int ry, int idx,
                              Integer groupIdx, EnemyIntel.PremadeWinRate groupWr) {
        int rowH = ROW_H - 8;
        // 行底：开黑组边框高亮，否则普通半透明底
        Color border = ROW_BORDER;
        Color bg = ROW_BG;
        if (groupIdx != null) {
            Color gc = GROUP_COLORS[groupIdx % GROUP_COLORS.length];
            border = new Color(gc.getRed(), gc.getGreen(), gc.getBlue(), 170);
            bg = new Color(gc.getRed(), gc.getGreen(), gc.getBlue(), 14);
        }
        g.setColor(bg);
        g.fillRoundRect(PAD, ry, WIDTH - 2 * PAD, rowH, 10, 10);
        g.setColor(border);
        g.setStroke(new BasicStroke(groupIdx != null ? 1.4f : 1f));
        g.drawRoundRect(PAD, ry, WIDTH - 2 * PAD, rowH, 10, 10);

        int cy = ry + rowH / 2;
        // 列1：头像（圆形裁切）+ 英雄名
        drawAvatar(g, PAD + 18, cy - 20, 40, e);
        drawText(g, e.getChampionName() == null ? "未知" : e.getChampionName(),
                PAD + 64, cy + 4, font(12, Font.PLAIN), TEXT_SUB, LEFT);

        // 列2：玩家名
        String name = e.getSummonerName() == null ? "未知召唤师" : e.getSummonerName();
        drawTextFit(g, name, PAD + 200, cy + 5, font(14, Font.BOLD), TEXT_MAIN, 190);

        // 列3：段位
        drawText(g, formatTier(e), PAD + 420, cy + 4, font(12.5f, Font.PLAIN), TEXT_KDA, LEFT);

        // 列4：近20局窗口胜率
        String winRate = formatWinRate(e);
        Color wrColor = winRate.startsWith("近") && !winRate.contains("无数据") ? GOLD : TEXT_DIM;
        drawText(g, winRate, PAD + 560, cy + 4, font(12.5f, Font.BOLD), wrColor, LEFT);

        // 列5：开黑标识（组标签 + 搭档胜率）
        if (groupIdx != null) {
            Color gc = GROUP_COLORS[groupIdx % GROUP_COLORS.length];
            String label = String.format("组%d · %s", groupIdx + 1, formatPremadeWinRate(groupWr));
            drawText(g, label, WIDTH - PAD - 16, cy + 4, font(11.5f, Font.BOLD), gc, RIGHT);
        } else {
            drawText(g, "路人", WIDTH - PAD - 16, cy + 4, font(11.5f, Font.PLAIN), TEXT_DIM, RIGHT);
        }
    }

    /** 英雄头像（圆形裁切）；无图标服务或加载失败降级色块圆盘 */
    private void drawAvatar(Graphics2D g, int x, int y, int diameter, EnemyIntel.EnemyPlayer e) {
        Integer championId = e.getChampionId();
        BufferedImage icon = iconService == null || championId == null || championId <= 0
                ? null : iconService.loadIcon(championId);
        if (icon == null) {
            drawHeroDot(g, x, y, diameter, e.getChampionName(), championId == null ? 0 : championId);
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setClip(new Ellipse2D.Double(x, y, diameter, diameter));
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(icon, x, y, diameter, diameter, null);
        } finally {
            g2.dispose();
        }
        g.setColor(new Color(255, 255, 255, 60));
        g.setStroke(new BasicStroke(1f));
        g.draw(new Ellipse2D.Double(x + 0.5, y + 0.5, diameter - 1, diameter - 1));
    }

    /** 英雄色块圆盘降级底：渐变圆 + 中央英雄名 */
    private void drawHeroDot(Graphics2D g, int x, int y, int diameter, String championName, int championId) {
        int c = HERO_COLORS[Math.floorMod(championId, HERO_COLORS.length)];
        Color a = new Color(c);
        g.setPaint(new GradientPaint(x, y, a.brighter(), x + diameter, y + diameter, a.darker()));
        g.fill(new Ellipse2D.Double(x, y, diameter, diameter));
        g.setColor(new Color(255, 255, 255, 60));
        g.setStroke(new BasicStroke(1f));
        g.draw(new Ellipse2D.Double(x, y, diameter, diameter));
        String name = championName == null ? "?" : championName;
        int size = name.length() > 4 ? 8 : 10;
        drawText(g, name, x + diameter / 2f, y + diameter / 2f + size / 2f - 1,
                font(size, Font.BOLD), Color.WHITE, CENTER);
    }

    /** 开黑分组说明区 */
    private int drawGroups(Graphics2D g, EnemyIntel intel, int y) {
        List<PremadeDetector.PremadeGroup> groups = intel.getPremadeGroups();
        if (groups.isEmpty()) {
            drawText(g, "· 未检测到开黑队伍（全员路人）", PAD + 8, y + 20,
                    font(13, Font.PLAIN), TEXT_DIM, LEFT);
            return y + 40;
        }
        drawText(g, "· 开黑分组", PAD + 8, y + 20, font(13, Font.BOLD), TEXT_SUB, LEFT);
        int gy = y + 20;
        List<EnemyIntel.PremadeWinRate> winRates = intel.getPremadeWinRates();
        for (int i = 0; i < groups.size(); i++) {
            PremadeDetector.PremadeGroup grp = groups.get(i);
            Color gc = GROUP_COLORS[i % GROUP_COLORS.length];
            EnemyIntel.PremadeWinRate wr = winRates != null && i < winRates.size() ? winRates.get(i) : null;
            String winRateText = formatPremadeWinRate(wr);
            String line = String.format("组%d（%d人 · 搭档%d局 · 搭档胜率%s）",
                    i + 1, grp.getPlayers().size(), grp.getTimes(), winRateText);
            drawText(g, line, PAD + 28, gy + 22, font(12.5f, Font.PLAIN), gc, LEFT);
            gy += 30;
        }
        return gy + 10;
    }

    /** 搭档胜率格式化：小样本标局数；无数据标"未知" */
    private String formatPremadeWinRate(EnemyIntel.PremadeWinRate wr) {
        if (wr == null || wr.getWinRate() == null || wr.getGames() == 0) {
            return "未知";
        }
        return String.format("%.0f%%", wr.getWinRate() * 100);
    }

    /** 段位格式化：DIAMOND III → 钻三；缺失 → 未知 */
    private String formatTier(EnemyIntel.EnemyPlayer e) {
        if (e.getTier() == null || e.getTier().isBlank()) {
            return "段位未知";
        }
        String cn = TIER_NAMES.getOrDefault(e.getTier().toUpperCase(), e.getTier());
        if (e.getRank() != null && !e.getRank().isBlank()) {
            cn = cn + " " + RANK_NAMES.getOrDefault(e.getRank().toUpperCase(), e.getRank());
        }
        return cn;
    }

    /** 窗口胜率格式化：小样本标局数（如"近 3 局 2 胜"），空窗口标"无数据" */
    private String formatWinRate(EnemyIntel.EnemyPlayer e) {
        WindowWinRate wr = e.getWinRate();
        if (wr == null || !wr.hasData()) {
            return "无数据";
        }
        return String.format("近 %d 局 %d 胜", wr.getGames(), wr.getWins());
    }

    /** 大段位中文映射（SGP tier 值 → 中文简称） */
    private static final Map<String, String> TIER_NAMES = Map.ofEntries(
            Map.entry("IRON", "黑铁"),
            Map.entry("BRONZE", "青铜"),
            Map.entry("SILVER", "白银"),
            Map.entry("GOLD", "黄金"),
            Map.entry("PLATINUM", "铂金"),
            Map.entry("EMERALD", "翡翠"),
            Map.entry("DIAMOND", "钻石"),
            Map.entry("MASTER", "大师"),
            Map.entry("GRANDMASTER", "宗师"),
            Map.entry("CHALLENGER", "王者"));

    /** 小段位罗马数字 → 中文数字 */
    private static final Map<String, String> RANK_NAMES = Map.of(
            "I", "一", "II", "二", "III", "三", "IV", "四");

    // ---------- 字体与工具（轻量复刻战报图渲染器，不引其私有方法） ----------

    private static Font font(float size, int style) {
        if (baseFont == null) {
            synchronized (IntelCardRendererImpl.class) {
                if (baseFont == null) {
                    baseFont = loadBaseFont();
                }
            }
        }
        return baseFont.deriveFont(style, size);
    }

    private static Font loadBaseFont() {
        try (InputStream in = IntelCardRendererImpl.class.getResourceAsStream(
                "/fonts/SourceHanSansSC-Regular.otf")) {
            if (in != null) {
                Font font = Font.createFont(Font.TRUETYPE_FONT, in);
                log.info("Intel card font loaded: {}", font.getFontName());
                return font;
            }
        } catch (IOException | java.awt.FontFormatException e) {
            log.warn("Failed to load bundled font, fallback to logical font: {}", e.getMessage());
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 16);
    }

    private byte[] toPngBytes(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("情报卡 PNG 编码失败", e);
        }
    }

    private void drawText(Graphics2D g, String text, float x, float y, Font f, Color c, int align) {
        if (text == null || text.isEmpty()) {
            return;
        }
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

    private void drawTextFit(Graphics2D g, String text, float x, float y, Font f, Color c, int maxWidth) {
        drawText(g, ellipsize(text, g.getFontMetrics(f), maxWidth), x, y, f, c, LEFT);
    }

    private static String ellipsize(String text, FontMetrics fm, int maxWidth) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (fm.stringWidth(text) <= maxWidth) {
            return text;
        }
        String suffix = "…";
        String head = text;
        while (head.length() > 1 && fm.stringWidth(head + suffix) > maxWidth) {
            head = head.substring(0, head.length() - 1);
        }
        return fm.stringWidth(head + suffix) > maxWidth ? suffix : head + suffix;
    }
}

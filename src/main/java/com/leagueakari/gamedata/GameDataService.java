package com.leagueakari.gamedata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 游戏资源数据服务：英雄/装备的官方中文名映射（CommunityDragon 镜像的 LCU game-data，
 * 与前端 game-resource.ts 同一数据源）。
 * <p>用途：AI 分析摘要组装前把英雄 ID/装备 ID 转换为中文名——实测推理模型
 * 在非思考模式下凭记忆猜 ID 会出错（103 猜成瑞兹），转换后模型不再需要猜。
 * 数据懒加载 + JVM 内存缓存（首次调用拉取，失败下次重试，中文 locale 失败降级 default 英文名）</p>
 */
@Slf4j
@Service
public class GameDataService {

    /** CommunityDragon 数据根地址（与前端 game-resource.ts 常量一致） */
    private static final String BASE_URL =
            "https://raw.communitydragon.org/latest/plugins/rcp-be-lol-game-data/global";

    /** 英雄摘要文件：数组 [{id, name}]（champions.json 已 404，主仓库同用 summary） */
    private static final String CHAMPION_FILE = "champion-summary.json";

    /** 装备文件：数组 [{id, name}] */
    private static final String ITEMS_FILE = "items.json";

    /** 召唤师技能文件：数组 [{id, name}]（AI 加厚 spec #43） */
    private static final String SPELLS_FILE = "summoner-spells.json";

    /** 常用队列中文名静态映射（spec #43：原 PostGameSummaryBuilder 硬编码 switch 收编至此，
     * 全站唯一出口，新增队列只改一处）；多队列 ID 共用名（如海克斯乱斗 2400/2410/2450） */
    private static final Map<Integer, String> QUEUE_NAMES = Map.ofEntries(
            Map.entry(420, "单双排"),
            Map.entry(430, "匹配"),
            Map.entry(440, "灵活组排"),
            Map.entry(450, "极地大乱斗"),
            Map.entry(1700, "斗魂竞技场"),
            Map.entry(2400, "海克斯乱斗"),
            Map.entry(2410, "海克斯乱斗"),
            Map.entry(2450, "海克斯乱斗"));

    /** 海克斯乱斗（KIWI）队列 ID 集合（工单 #53：英雄×强化统计的样本白名单，
     * 从 QUEUE_NAMES 派生——队列中文名与白名单同一处维护，新增 KIWI 队列号只改上面映射） */
    private static final Set<Integer> KIWI_QUEUE_IDS = QUEUE_NAMES.entrySet().stream()
            .filter(e -> "海克斯乱斗".equals(e.getValue()))
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /**
     * 判定队列是否海克斯乱斗（KIWI，工单 #53 样本双条件之一）：
     * 白名单唯一出口，消费方（AugmentWinRateService 等）禁止各自硬编码队列号
     *
     * @param queueId 队列 ID
     * @return true = 海克斯乱斗系队列（2400/2410/2450）
     */
    public static boolean isKiwiQueue(int queueId) {
        return KIWI_QUEUE_IDS.contains(queueId);
    }

    /**
     * 海克斯乱斗（KIWI）队列 ID 集合（工单 #53：SQL 预筛 in 条件用）
     *
     * @return 不可变队列 ID 集合（当前 2400/2410/2450）
     */
    public static Set<Integer> kiwiQueueIds() {
        return KIWI_QUEUE_IDS;
    }

    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;

    /** 英雄名缓存：ID → 中文名（懒加载；空 Map 表示未加载或加载失败） */
    private volatile Map<Integer, String> championNames = Map.of();

    /** 装备名缓存：ID → 中文名（懒加载；空 Map 表示未加载或加载失败） */
    private volatile Map<Integer, String> itemNames = Map.of();

    /** 召唤师技能名缓存：ID → 中文名（懒加载；空 Map 表示未加载或加载失败） */
    private volatile Map<Integer, String> spellNames = Map.of();

    /** 加载中标记：double-check 防止并发请求重复拉取同一文件 */
    private final AtomicBoolean championLoading = new AtomicBoolean(false);
    private final AtomicBoolean itemLoading = new AtomicBoolean(false);

    /** 召唤师技能加载中标记（double-check 防并发重复拉取） */
    private final AtomicBoolean spellLoading = new AtomicBoolean(false);

    /**
     * 构造注入 HTTP 客户端与 JSON 解析器
     */
    public GameDataService(CloseableHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 取英雄中文名；数据未加载/未命中时回退 ID 字符串（模型按提示词 ID 对照表兜底），
     * 首字母大写场景（如 -1 特殊值）由调用方自行处理
     *
     * @param championId 英雄 ID
     * @return 英雄名（如"阿狸"），未知 ID 返回 ID 字符串
     */
    public String championName(int championId) {
        ensureLoaded(CHAMPION_FILE, () -> championNames, championLoading, v -> championNames = v);
        return championNames.getOrDefault(championId, String.valueOf(championId));
    }

    /**
     * 取装备中文名；数据未加载/未命中时回退 ID 字符串
     *
     * @param itemId 装备 ID
     * @return 装备名（如"收集者"），未知 ID 返回 ID 字符串
     */
    public String itemName(int itemId) {
        ensureLoaded(ITEMS_FILE, () -> itemNames, itemLoading, v -> itemNames = v);
        return itemNames.getOrDefault(itemId, String.valueOf(itemId));
    }

    /**
     * 取召唤师技能中文名（AI 加厚 spec #43）；数据未加载/未命中时回退 ID 字符串
     *
     * @param spellId 召唤师技能 ID（statsJson 的 spell1Id/spell2Id）
     * @return 技能名（如"闪现"），未知 ID 返回 ID 字符串
     */
    public String summonerSpellName(int spellId) {
        ensureLoaded(SPELLS_FILE, () -> spellNames, spellLoading, v -> spellNames = v);
        return spellNames.getOrDefault(spellId, String.valueOf(spellId));
    }

    /**
     * 取队列中文名（AI 加厚 spec #43）：常用队列静态映射，全站唯一出口
     * （战报 meta 行与 AI 摘要共用文案）；不触发任何网络加载
     *
     * @param queueId 队列 ID（如 420 单双排 / 450 极地大乱斗）
     * @return 队列中文名；未知队列返回 ID 字符串，null 返回"对局"
     */
    public String queueName(Integer queueId) {
        if (queueId == null) {
            return "对局";
        }
        return QUEUE_NAMES.getOrDefault(queueId, String.valueOf(queueId));
    }

    /**
     * 按需加载映射表（double-check）：首次调用时同步拉取并缓存；
     * 加载失败保持空 Map，允许下次调用重试
     */
    private void ensureLoaded(String file, java.util.function.Supplier<Map<Integer, String>> cache,
            AtomicBoolean loading, java.util.function.Consumer<Map<Integer, String>> setter) {
        if (cache.get().isEmpty() && loading.compareAndSet(false, true)) {
            try {
                Map<Integer, String> loaded = loadNames(file);
                setter.accept(loaded);
                log.info("Game data loaded: file={}, count={}", file, loaded.size());
            } catch (Exception e) {
                // 加载失败：保持空缓存，下次调用重新拉取（CDN 偶发故障可自愈）
                log.error("Failed to load game data: file={}, error={}", file, e.getMessage());
            } finally {
                loading.set(false);
            }
        }
    }

    /**
     * 拉取并解析映射文件：中文 locale（zh_cn）优先，失败降级 default（英文名）；
     * 两个 locale 都失败返回空 Map
     */
    private Map<Integer, String> loadNames(String file) {
        // 中文优先：模型输出要中文英雄/装备名；default 为兜底（至少保证有名字可用）
        for (String locale : List.of("zh_cn", "default")) {
            try {
                String url = BASE_URL + "/" + locale + "/v1/" + file;
                HttpGet request = new HttpGet(url);
                try (CloseableHttpResponse response = httpClient.execute(request)) {
                    if (response.getCode() == HttpStatus.OK.value()) {
                        String body = response.getEntity() != null
                                ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8) : "";
                        Map<Integer, String> parsed = parseNames(body);
                        if (!parsed.isEmpty()) {
                            return parsed;
                        }
                    }
                    log.warn("Game data fetch failed: file={}, locale={}, status={}",
                            file, locale, response.getCode());
                }
            } catch (Exception e) {
                log.warn("Game data fetch error: file={}, locale={}, error={}", file, locale, e.getMessage());
            }
        }
        return Map.of();
    }

    /**
     * 解析映射 JSON：直接数组 [{id, name}]（CDragon 格式），兼容 {data: [...]} 包装；
     * 过滤负 ID（如 -1"无"）与空名，返回 ID → name
     */
    private Map<Integer, String> parseNames(String body) throws Exception {
        JsonNode node = objectMapper.readTree(body);
        JsonNode array = node.isArray() ? node : node.path("data");
        Map<Integer, String> result = new HashMap<>();
        if (array.isArray()) {
            for (JsonNode item : array) {
                int id = item.path("id").asInt(-1);
                String name = item.path("name").asText("").trim();
                if (id >= 0 && !name.isBlank()) {
                    result.put(id, name);
                }
            }
        }
        return result;
    }
}

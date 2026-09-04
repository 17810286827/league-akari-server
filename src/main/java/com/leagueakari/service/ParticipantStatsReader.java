package com.leagueakari.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 参与者统计读取门面：stats_json 快照的统一读取口径（薄门面，按"读法"暴露）。
 * <p>口径契约（全项目唯一实现，测试面见 ParticipantStatsReaderTest）：
 * 字段缺失、为 null、非数字时补 0（布尔补 false）；损坏 JSON 记 warn 日志后按全缺失处理，
 * 绝不向调用方抛错——下游渲染/评分/聚合始终拿到稳定值。</p>
 * <p>按读法暴露而非按字段暴露（不做 kills(p)/damageDealt(p) 字段清单）：
 * 新增统计项无需改本门面接口；字段语义（哪些原始值构成评分维度）留在各调用方。
 * 四个读方：MatchQueryService（列表/详情组装）、MatchMvpService（评分输入）、
 * TeamStatsService（车队统计）、FleetGameSummaryService（一局摘要）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParticipantStatsReader {

    private final ObjectMapper objectMapper;

    /**
     * 读取顶层数值字段（int 语义）：缺失/null/非数字/JSON 损坏返回 0
     */
    public int intVal(String statsJson, String key) {
        JsonNode stats = node(statsJson);
        if (stats == null || !stats.has(key) || stats.get(key).isNull()) {
            return 0;
        }
        return stats.get(key).asInt(0);
    }

    /**
     * 读取顶层数值字段（double 语义，评分维度的原始量纲）：缺失/null/损坏返回 0.0
     */
    public double doubleVal(String statsJson, String key) {
        JsonNode stats = node(statsJson);
        if (stats == null || !stats.has(key) || stats.get(key).isNull()) {
            return 0.0;
        }
        return stats.get(key).asDouble(0);
    }

    /**
     * 读取 stats.challenges 嵌套数值字段（SGP 独有挑战数据，LCU 缺失）：一律补 0
     */
    public int challengeInt(String statsJson, String key) {
        JsonNode stats = node(statsJson);
        if (stats == null || !stats.has("challenges") || !stats.get("challenges").has(key)) {
            return 0;
        }
        JsonNode value = stats.get("challenges").get(key);
        if (value.isNull()) {
            return 0;
        }
        return value.asInt(0);
    }

    /**
     * 读取布尔字段：缺失/null/损坏返回 false
     */
    public boolean boolVal(String statsJson, String key) {
        JsonNode stats = node(statsJson);
        if (stats == null || !stats.has(key) || stats.get(key).isNull()) {
            return false;
        }
        return stats.get(key).asBoolean(false);
    }

    /**
     * 按连续键名读取整数列表（如 item0-6、playerAugment1-6、perk0-5）：
     * 按传入键顺序取值，缺失/null 的键跳过
     */
    public List<Integer> listVal(String statsJson, String... keys) {
        List<Integer> values = new ArrayList<>();
        JsonNode stats = node(statsJson);
        if (stats == null) {
            return values;
        }
        for (String key : keys) {
            if (stats.has(key) && !stats.get(key).isNull()) {
                values.add(stats.get(key).asInt(0));
            }
        }
        return values;
    }

    /**
     * 在指定节点（顶层或嵌套对象）上读取数组字段为整数列表（如 SGP 嵌套 perks.perkIds）：
     * 字段缺失或非数组返回空列表
     */
    public List<Integer> arrayVal(JsonNode node, String key) {
        List<Integer> values = new ArrayList<>();
        if (node == null || !node.has(key) || !node.get(key).isArray()) {
            return values;
        }
        node.get(key).forEach(n -> values.add(n.asInt(0)));
        return values;
    }

    /**
     * 解析 stats_json 为 JsonNode（顶层节点）：null/空白返回 null，损坏记 warn 后返回 null
     * （调用方按全缺失处理）——本门面的唯一解析入口。需要嵌套探测时配合
     * {@link #nested} 使用
     */
    public JsonNode node(String statsJson) {
        if (statsJson == null || statsJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(statsJson);
        } catch (Exception e) {
            // 快照损坏不阻断调用方：留日志排查，按缺失字段处理
            log.warn("Parse statsJson failed, treat as empty: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 在指定节点上取嵌套对象（如 SGP 的 perks 对象，供双路径判定）：
     * 不存在或非对象返回 null，调用方走平铺路径
     */
    public JsonNode nested(JsonNode node, String key) {
        if (node == null) {
            return null;
        }
        JsonNode child = node.get(key);
        return child != null && child.isObject() ? child : null;
    }
}

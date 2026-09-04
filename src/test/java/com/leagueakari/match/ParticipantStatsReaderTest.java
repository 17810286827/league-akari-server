package com.leagueakari.match;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ParticipantStatsReader 单元测试（stats_json 读取口径的唯一测试面）：
 * 缺失补 0/false、challenges 嵌套、连续键列表、数组字段、损坏 JSON 兜底。
 * 四个读方（查询组装/评分/MVP 评选/车队统计）的口径契约全部锚定在此。
 */
class ParticipantStatsReaderTest {

    private ParticipantStatsReader reader;

    @BeforeEach
    void setUp() {
        reader = new ParticipantStatsReader(new ObjectMapper());
    }

    @Test
    @DisplayName("intVal：顶层数值字段正常读取，缺失/null 补 0")
    void intVal_readsTopLevelWithZeroFallback() {
        String stats = "{\"totalDamageDealtToChampions\":25430,\"goldEarned\":null}";
        assertThat(reader.intVal(stats, "totalDamageDealtToChampions")).isEqualTo(25430);
        assertThat(reader.intVal(stats, "goldEarned")).isZero();
        assertThat(reader.intVal(stats, "missingKey")).isZero();
        // null / 空白 / 损坏 JSON：一律补 0 不抛错
        assertThat(reader.intVal(null, "totalDamageDealtToChampions")).isZero();
        assertThat(reader.intVal("", "totalDamageDealtToChampions")).isZero();
        assertThat(reader.intVal("{not-json", "totalDamageDealtToChampions")).isZero();
    }

    @Test
    @DisplayName("doubleVal：顶层浮点字段读取（评分维度的原始量纲）")
    void doubleVal_readsTopLevelDouble() {
        String stats = "{\"visionScore\":42.5,\"totalHeal\":null}";
        assertThat(reader.doubleVal(stats, "visionScore")).isEqualTo(42.5);
        assertThat(reader.doubleVal(stats, "totalHeal")).isZero();
        assertThat(reader.doubleVal("bad{", "visionScore")).isZero();
    }

    @Test
    @DisplayName("challengeInt：challenges 嵌套字段（SGP 独有），缺失按 0")
    void challengeInt_readsNestedChallenges() {
        String stats = "{\"challenges\":{\"soloKills\":3,\"killsNearEnemyTurret\":null}}";
        assertThat(reader.challengeInt(stats, "soloKills")).isEqualTo(3);
        assertThat(reader.challengeInt(stats, "killsNearEnemyTurret")).isZero();
        assertThat(reader.challengeInt(stats, "maxCsAdvantageOnLaneOpponent")).isZero();
        // 无 challenges 对象 / 损坏：补 0
        assertThat(reader.challengeInt("{\"kda\":1}", "soloKills")).isZero();
        assertThat(reader.challengeInt(null, "soloKills")).isZero();
    }

    @Test
    @DisplayName("boolVal：布尔字段读取，缺失/非法按 false")
    void boolVal_readsBooleanWithFalseFallback() {
        String stats = "{\"gameEndedInSurrender\":true,\"quadraKills\":1}";
        assertThat(reader.boolVal(stats, "gameEndedInSurrender")).isTrue();
        // 非布尔字段按 asBoolean(false) 语义处理（1 → true 是 Jackson 默认，此处锁现状）
        assertThat(reader.boolVal(stats, "missing")).isFalse();
        assertThat(reader.boolVal(null, "gameEndedInSurrender")).isFalse();
    }

    @Test
    @DisplayName("listVal：按连续键名读取（item0-6/perk0-5），缺失键跳过")
    void listVal_readsSequentialKeys() {
        String stats = "{\"item0\":3157,\"item2\":3020,\"spell1Id\":4}";
        List<Integer> items = reader.listVal(stats, "item0", "item1", "item2");
        // item1 缺失被跳过，顺序保持传入键序
        assertThat(items).containsExactly(3157, 3020);
        assertThat(reader.listVal(null, "item0")).isEmpty();
    }

    @Test
    @DisplayName("arrayVal：数组字段读取（顶层或嵌套对象上），非数组返回空")
    void arrayVal_readsArrayField() {
        // 嵌套用法（SGP perks）：先 node() 拿嵌套对象，再在其上读数组——与旧 statIntArray(nested, ...) 同构
        String stats = "{\"perks\":{\"perkIds\":[8112,8128,8009]}}";
        var perks = reader.nested(reader.node(stats), "perks");
        assertThat(perks).isNotNull();
        assertThat(reader.arrayVal(perks, "perkIds")).containsExactly(8112, 8128, 8009);
        // 顶层数组同样可读
        assertThat(reader.arrayVal(reader.node("{\"ids\":[1,2]}"), "ids")).containsExactly(1, 2);
        // 字段缺失或非数组：空列表
        assertThat(reader.arrayVal(reader.node("{\"perks\":{}}"), "perkIds")).isEmpty();
        assertThat(reader.arrayVal(reader.node("{\"perks\":{\"perkStyle\":8100}}"), "perkIds")).isEmpty();
        assertThat(reader.arrayVal(null, "perkIds")).isEmpty();
    }

    @Test
    @DisplayName("node：SGP 嵌套对象探测（perks 双路径判定用），缺失返回 null")
    void node_exposesNestedObjectForPathDetection() {
        String stats = "{\"perks\":{\"perkIds\":[1],\"perkStyle\":8100}}";
        var perks = reader.nested(reader.node(stats), "perks");
        assertThat(perks).isNotNull();
        assertThat(perks.path("perkStyle").asInt()).isEqualTo(8100);
        // 平铺路径（LCU）无 perks 对象
        assertThat(reader.nested(reader.node("{\"perk0\":8112}"), "perks")).isNull();
        assertThat(reader.nested(reader.node(null), "perks")).isNull();
    }
}

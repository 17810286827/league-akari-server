package com.leagueakari.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.leagueakari.entity.Match;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MatchMapper 冒烟测试：验证 Flyway 建表后插入与回查链路
 */
@SpringBootTest
@Transactional // 每个测试回滚，不污染数据
class MatchMapperSmokeTest {

    @Autowired
    private MatchMapper matchMapper;

    @Test
    void insertAndQueryMatch() {
        Match match = new Match();
        match.setGameId(1000000001L);
        match.setGameCreation(1720000000000L);
        match.setGameDuration(1830);
        match.setGameMode("CLASSIC");
        match.setGameType("MATCHED_GAME");
        match.setQueueId(420);
        match.setMapId(11);
        match.setGameVersion("25.4.1");
        match.setRegion("na1");
        match.setRsoPlatformId("");
        match.setDataSource("lcu");
        match.setSelfPuuid("self-puuid-1");
        matchMapper.insert(match);

        Match loaded = matchMapper.selectOne(
                new QueryWrapper<Match>().eq("game_id", 1000000001L));
        assertThat(loaded).isNotNull();
        assertThat(loaded.getGameMode()).isEqualTo("CLASSIC");
    }
}

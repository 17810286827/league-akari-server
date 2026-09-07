package com.leagueakari.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leagueakari.entity.EnemyScoutRank;
import org.apache.ibatis.annotations.Mapper;

/**
 * 敌方侦察段位快照 Mapper：按 puuid 幂等 upsert 与情报卡段位读取
 */
@Mapper
public interface EnemyScoutRankMapper extends BaseMapper<EnemyScoutRank> {
}

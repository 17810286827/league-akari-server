package com.leagueakari.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leagueakari.entity.EnemyScoutMatch;
import org.apache.ibatis.annotations.Mapper;

/**
 * 敌方侦察摘要缓存 Mapper：开黑判定/窗口胜率的原料读取与幂等写入
 */
@Mapper
public interface EnemyScoutMatchMapper extends BaseMapper<EnemyScoutMatch> {
}

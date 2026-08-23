package com.leagueakari.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leagueakari.entity.ChampionClass;
import org.apache.ibatis.annotations.Mapper;

/**
 * 英雄职业分类 Mapper
 */
@Mapper
public interface ChampionClassMapper extends BaseMapper<ChampionClass> {
}
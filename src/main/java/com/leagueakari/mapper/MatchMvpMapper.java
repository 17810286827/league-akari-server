package com.leagueakari.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leagueakari.entity.MatchMvp;
import org.apache.ibatis.annotations.Mapper;

/**
 * MVP/SVP 评选结果 Mapper
 */
@Mapper
public interface MatchMvpMapper extends BaseMapper<MatchMvp> {
}
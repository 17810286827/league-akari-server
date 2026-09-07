package com.leagueakari.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leagueakari.entity.IntelGameStart;
import org.apache.ibatis.annotations.Mapper;

/**
 * 敌方情报推送去重与状态 Mapper：游戏开始信号按 gameId 幂等写入与状态推进
 */
@Mapper
public interface IntelGameStartMapper extends BaseMapper<IntelGameStart> {
}

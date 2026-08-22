package com.leagueakari.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leagueakari.entity.RiotAccount;
import org.apache.ibatis.annotations.Mapper;

/**
 * Riot 账号缓存 Mapper：查库命中直接返回，未命中回写 API 结果
 */
@Mapper
public interface RiotAccountMapper extends BaseMapper<RiotAccount> {
}

package com.leagueakari.controller;

import com.leagueakari.dto.RiotAccountDto;
import com.leagueakari.service.RiotAccountClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Riot 召唤师搜索接口（路由层职责：参数校验与返回值封装，业务逻辑下沉 service）
 */
@RestController
@RequestMapping("/api/riot/accounts")
public class RiotController {

    private final RiotAccountClient riotAccountClient;

    /**
     * 构造注入搜索客户端
     */
    public RiotController(RiotAccountClient riotAccountClient) {
        this.riotAccountClient = riotAccountClient;
    }

    /**
     * 按"昵称#tag"搜索召唤师账号（Riot Account-V1，带 JVM 缓存）
     *
     * @param riotName 召唤师名，格式 "昵称#tag"（如 "赌书消得泼茶香#iKun"）
     * @return 账号信息（puuid/gameName/tagLine）
     */
    @GetMapping("/by-name")
    public RiotAccountDto searchByRiotId(@RequestParam String riotName) {
        // 参数格式校验（缺 #tag）与业务异常均由 service/全局异常处理器处理
        return riotAccountClient.searchByRiotId(riotName);
    }
}

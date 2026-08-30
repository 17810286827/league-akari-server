package com.leagueakari.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 车队功能配置：车队成员名单与车队对局判定阈值
 * <p>从 application.yml 的 team.* 前缀加载，启动时绑定。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "team")
public class TeamProperties {

    /**
     * 车队成员名单，格式 "昵称#tag"（如 赌书消得泼茶香#iKun）。
     * 由车队管理员在此维护：增删成员改这里即可，无需改代码、无需动数据库
     */
    private List<String> roster = new ArrayList<>();

    /**
     * 车队名：周报标题与分享图展示用（如"周末开黑小队"）
     */
    private String name = "车队";

    /**
     * 车队对局判定阈值：一场对局中同局出现的车队成员数 ≥ 该值，
     * 才算"车队对局"（开黑局）——用于周报/榜单过滤掉成员的单人排位/路人局
     */
    private int minSharedMembers = 2;
}

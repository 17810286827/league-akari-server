package com.leagueakari.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 根路径控制器：裸地址访问（GET /）返回服务引导信息。
 * 避免浏览器/探测工具直接访问根路径时落入静态资源未命中（404/500 堆栈），
 * 给出明确的 200 响应与可用端点提示
 */
@RestController
public class RootController {

    /**
     * 服务引导信息：code=0 表示服务在线，附带健康检查端点便于快速探测
     */
    @GetMapping("/")
    public Map<String, Object> root() {
        return Map.of(
                "code", 0,
                "service", "league-akari-server",
                "health", "/actuator/health");
    }
}

package com.leagueakari.controller;

import com.leagueakari.common.web.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 根路径控制器：裸地址访问（GET /）返回服务引导信息。
 * 避免浏览器/探测工具直接访问根路径时落入静态资源未命中（404/500 堆栈），
 * 给出明确的 200 响应与可用端点提示；响应形状与其他接口统一走 ApiResult
 */
@RestController
public class RootController {

    /**
     * 服务引导信息：code=0 表示服务在线，data 附带健康检查端点便于快速探测
     */
    @GetMapping("/")
    public ApiResult<Map<String, String>> root() {
        return ApiResult.success(Map.of(
                "service", "league-akari-server",
                "health", "/actuator/health"));
    }
}

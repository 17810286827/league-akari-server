package com.leagueakari.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 跨域配置：允许浏览器从任意来源直连 /api 接口。
 * <p>背景：前端 Vite dev server 运行在 localhost:5173，与后端 8080 端口不同源，
 * 浏览器对跨源请求会先发 OPTIONS 预检，后端不返回 CORS 头则请求被直接拦截。
 * 本机自用场景无鉴权，因此对任意来源放开跨域。</p>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 注册 /api/** 的跨域规则（WebMvcConfigurer 提供的 CORS 扩展点）
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 仅对 API 路径开放跨域，其他路径不受影响
        registry.addMapping("/api/**")
                // 允许任意来源；allowedOriginPatterns 支持 "*" 通配并回显请求 Origin
                .allowedOriginPatterns("*")
                // 放开全部 HTTP 方法（GET/POST/OPTIONS 等）与全部请求头，兼容预检
                .allowedMethods("*")
                .allowedHeaders("*");
    }
}

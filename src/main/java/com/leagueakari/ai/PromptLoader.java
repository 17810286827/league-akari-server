package com.leagueakari.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * AI 提示词加载器：classpath 提示词文件读取 + 内置默认回退，全项目唯一实现。
 * <p>三个 AI 场景（单局分析 SSE / 周报锐评 / 局后锐评）的加载口径一致：
 * 文件存在读文件（用户编辑后即时生效，无需重启），缺失/读取失败回退调用方给的
 * 内置默认文案，保证接口永远可用。属纯资源加载知识，不进 AiClient（客户端不管资源）。</p>
 */
@Slf4j
@Component
public class PromptLoader {

    /**
     * 加载提示词
     *
     * @param classpathLocation classpath 路径（如 ai/post-game-prompt.md）
     * @param builtinFallback   文件缺失/读取失败时的内置默认文案（各场景自带，不共享）
     * @return 提示词正文（永不返回 null）
     */
    public String load(String classpathLocation, String builtinFallback) {
        try {
            ClassPathResource resource = new ClassPathResource(classpathLocation);
            if (resource.exists()) {
                return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("Failed to load AI prompt file {}: {}", classpathLocation, e.getMessage());
        }
        return builtinFallback;
    }
}

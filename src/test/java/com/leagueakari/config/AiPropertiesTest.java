package com.leagueakari.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AiProperties 绑定契约测试：直接绑定 classpath 的 application.yml（真实真值文件），
 * 逐字段断言 yml 当前值即生产意图——防止"yml 改了而没人同步"或"注释/代码默认值与 yml
 * 各说各话"的漂移再次发生（见 docs/adr/0004）。新增 ai.* 键时必须同步更新本测试。
 * <p>说明：apiKey 绑定自 ${AI_API_KEY:} 占位符，原始 PropertySource 不做占位符解析，
 * 且其值由部署环境注入，故不在断言范围。</p>
 */
class AiPropertiesTest {

    /** 从 classpath 的 application.yml 加载 ai.* 段并绑定为 AiProperties */
    private AiProperties bindFromApplicationYml() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources =
                loader.load("application.yml", new ClassPathResource("application.yml"));
        // 单文档 yml：只取第一个属性源
        PropertySource<?> source = sources.get(0);
        Binder binder = new Binder(ConfigurationPropertySources.from(source));
        return binder.bind("ai", Bindable.of(AiProperties.class)).get();
    }

    /** 用例：yml 是 AI 配置唯一真值——全部采样参数逐项与生产意图一致 */
    @Test
    void bindsFullAiSectionFromYml() throws Exception {
        AiProperties props = bindFromApplicationYml();

        // 网关与模型（决策见 docs/adr/0004、0006 更新记录）：
        // 2026-09-05 切换至 pianyitoken 网关 + deepseek-v4-flash；
        // 2026-09-07 参数归一（ADR 0009）：全部场景统一读 ai.model，场景级
        // post-game-model / weekly-max-tokens / post-game-max-tokens 键已删除
        assertThat(props.getBaseUrl()).isEqualTo("https://pianyitoken.gay/v1");
        assertThat(props.getModel()).isEqualTo("deepseek-v4-flash");

        // 提示词文件四件套必须齐全（weekly-prompt-file 曾缺失、靠代码默认值兜底，现补入 yml）
        assertThat(props.getPromptFile()).isEqualTo("ai/system-prompt.md");
        assertThat(props.getReplayPromptFile()).isEqualTo("ai/replay-prompt.md");
        assertThat(props.getWeeklyPromptFile()).isEqualTo("ai/weekly-prompt.md");
        assertThat(props.getPostGamePromptFile()).isEqualTo("ai/post-game-prompt.md");

        // 采样与输出参数：yml 当前值即意图（与四个 AI 服务共用同一份）。
        // max-tokens 16384 为全场景统一输出上限（永不截断的天花板，计费按实际生成量）
        assertThat(props.getTemperature()).isEqualTo(1.0);
        assertThat(props.getFrequencyPenalty()).isEqualTo(1.0);
        assertThat(props.getPresencePenalty()).isEqualTo(0.5);
        assertThat(props.getMaxTokens()).isEqualTo(16384);
        // 思考模式开关（2026-09-07 关闭，ADR 0009）：实测网关不执行 vLLM 式
        // chat_template_kwargs 开关/预算，唯一有效开关是 DeepSeek 官方嵌套参数
        // thinking:{type:enabled|disabled}；关闭后首 token 约 2s（原开启约 2.5-7s）
        assertThat(props.isThinking()).isFalse();
        // 思考强度（官方 reasoning_effort，low/high/max；空 = 不传该参数）：
        // 与 apiKey 同理，yml 值为 ${AI_THINKING_EFFORT:} 占位符，原始 PropertySource
        // 不做占位符解析（实际值由部署环境注入、空 = 不传），只断言键存在
        assertThat(props.getThinkingEffort()).isEqualTo("${AI_THINKING_EFFORT:}");
        // 重试次数（失败后重试次数，不含首次）：四个 AI 场景统一读此键
        assertThat(props.getRetryCount()).isEqualTo(3);
    }
}

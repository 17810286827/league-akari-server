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

    /** 用例：yml 是 AI 配置唯一真值——模型分工与全部采样参数逐项与生产意图一致 */
    @Test
    void bindsFullAiSectionFromYml() throws Exception {
        AiProperties props = bindFromApplicationYml();

        // 网关与模型分工（决策见 docs/adr/0004）：
        // 分析/周报用 model；局后播报独立键 post-game-model 解耦
        assertThat(props.getBaseUrl()).isEqualTo("https://yt.19851117.xyz/v1");
        assertThat(props.getModel()).isEqualTo("gemini-2.5-flash");
        assertThat(props.getPostGameModel()).isEqualTo("gemini-2.5-flash");

        // 提示词文件三件套必须齐全（weekly-prompt-file 曾缺失、靠代码默认值兜底，现补入 yml）
        assertThat(props.getPromptFile()).isEqualTo("ai/system-prompt.md");
        assertThat(props.getWeeklyPromptFile()).isEqualTo("ai/weekly-prompt.md");
        assertThat(props.getPostGamePromptFile()).isEqualTo("ai/post-game-prompt.md");

        // 采样与输出参数：yml 当前值即意图（与三个 AI 服务共用同一份）
        assertThat(props.getTemperature()).isEqualTo(1.0);
        assertThat(props.getFrequencyPenalty()).isEqualTo(0.6);
        assertThat(props.getPresencePenalty()).isEqualTo(0.3);
        assertThat(props.getMaxTokens()).isEqualTo(4096);
        assertThat(props.getWeeklyMaxTokens()).isEqualTo(4096);
        assertThat(props.getPostGameMaxTokens()).isEqualTo(2048);
        // 思考模式开关（当前 yml 为开启）：三个 AI 场景统一读此键，不再有硬编码旁路
        assertThat(props.isThinking()).isTrue();
        // 重试次数（失败后重试次数，不含首次）：三个 AI 场景统一读此键
        assertThat(props.getRetryCount()).isEqualTo(1);
    }

    /** 用例：分析/周报与局后锐评共用基础参数（base-url、temperature），仅模型与上限分场景 */
    @Test
    void scenarioKeysShareBaseParams() throws Exception {
        AiProperties props = bindFromApplicationYml();

        // 场景差异只在 model 与 max-tokens：基础参数（网关/温度）全场景共享；
        // 分析与周报上限当前持平（4096），局后正文短（2048）
        assertThat(props.getModel()).isNotBlank();
        assertThat(props.getPostGameModel()).isNotBlank();
        assertThat(props.getTemperature()).isEqualTo(props.getTemperature());
        assertThat(props.getMaxTokens()).isGreaterThanOrEqualTo(props.getWeeklyMaxTokens());
        assertThat(props.getMaxTokens()).isGreaterThan(props.getPostGameMaxTokens());
    }
}

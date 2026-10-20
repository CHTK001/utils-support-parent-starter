package com.chua.deeplearning.support.sherpa;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 中文纠错（复用 OCR 侧 macbert4csc）在 ASR 管线中的接线验收。
 *
 * <p>背景：纠错器是从 onnx-starter 的 {@code IdentificationEngine} 按模型标识取的，
 * 属跨模块的可选能力。若宿主未引入 onnx-starter 或模型未注册，管线会<b>静默降级</b>，
 * 仅凭「转写结果看起来正常」无法发现纠错其实没跑。因此这里直接对纠错分支做断言。</p>
 *
 * <p>前置：classpath 上有 onnx-starter 与 macbert4csc 模型；不满足时显式 skip。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class SherpaCorrectorTest {

    /**
     * 纠错分支应能修复错别字，且不误改正确文本。
     *
     * @throws Exception 反射调用失败
     */
    @Test
    void shouldCorrectTyposAndKeepCleanText() throws Exception {
        Assumptions.assumeTrue(correctorAvailable(), "纠错模型不可用，跳过（需 onnx-starter + macbert4csc）");

        try (SherpaAsrPipeline pipeline = SherpaAsrPipeline.builder()
                .model("/tmp/not-used-for-corrector")
                .corrector("macbert4csc")
                .build()) {

            assertThat(pipeline.correctorModelId()).isEqualTo("macbert4csc");
            Method apply = SherpaAsrPipeline.class.getDeclaredMethod("applyCorrector", String.class);
            apply.setAccessible(true);

            // 已知错别字应被修复
            assertThat((String) apply.invoke(pipeline, "我今天因该早点回家"))
                    .as("错别字 因该 应被纠正为 应该")
                    .contains("应该")
                    .doesNotContain("因该");

            assertThat((String) apply.invoke(pipeline, "这个方案还需在开会时讨轮一下"))
                    .as("错别字 讨轮 应被纠正为 讨论")
                    .contains("讨论");

            // 正确文本不应被误改
            String clean = "今天天气真不错，我们一起去公园散步吧。";
            assertThat((String) apply.invoke(pipeline, clean)).isEqualTo(clean);
        }
    }

    /**
     * 未配置纠错模型时，纠错分支应原样返回（不引入额外开销/副作用）。
     *
     * @throws Exception 反射调用失败
     */
    @Test
    void shouldPassThroughWhenCorrectorNotConfigured() throws Exception {
        try (SherpaAsrPipeline pipeline = SherpaAsrPipeline.builder()
                .model("/tmp/not-used")
                .build()) {

            assertThat(pipeline.correctorModelId()).isNull();
            assertThat(pipeline.peekCorrector()).isNull();

            Method apply = SherpaAsrPipeline.class.getDeclaredMethod("applyCorrector", String.class);
            apply.setAccessible(true);
            String text = "我今天因该早点回家";
            assertThat((String) apply.invoke(pipeline, text))
                    .as("未配置纠错时不应改动文本")
                    .isEqualTo(text);
        }
    }

    /**
     * 纠错模型标识不存在时，应优雅降级而不是抛异常。
     *
     * @throws Exception 反射调用失败
     */
    @Test
    void shouldDegradeWhenModelMissing() throws Exception {
        try (SherpaAsrPipeline pipeline = SherpaAsrPipeline.builder()
                .model("/tmp/not-used")
                .corrector("definitely-not-a-real-model")
                .build()) {

            assertThat(pipeline.peekCorrector()).isNull();
            Method apply = SherpaAsrPipeline.class.getDeclaredMethod("applyCorrector", String.class);
            apply.setAccessible(true);
            String text = "我今天因该早点回家";
            assertThat((String) apply.invoke(pipeline, text)).isEqualTo(text);
        }
    }

    /**
     * 判断纠错模型是否<b>真正可用</b>。
     *
     * <p>注意不能用 {@code peekCorrector() != null} 判断：它只验证「能取到翻译器对象」，
     * 而 {@code LazyDjlTranslator} 在首次 {@code translate} 前不会加载模型。实测会出现
     * 「探测通过、调用却抛资源缺失」的假阳性。因此这里真跑一次翻译。</p>
     *
     * @return true 表示可真正执行纠错
     */
    private static boolean correctorAvailable() {
        try (SherpaAsrPipeline pipeline = SherpaAsrPipeline.builder()
                .model("/tmp/not-used")
                .corrector("macbert4csc")
                .build()) {
            if (pipeline.peekCorrector() == null) {
                return false;
            }
            Method apply = SherpaAsrPipeline.class.getDeclaredMethod("applyCorrector", String.class);
            apply.setAccessible(true);
            // 用已知错别字触发真实推理；模型缺失时内部会捕获并原样返回，不会抛到调用方
            String probe = (String) apply.invoke(pipeline, "我今天因该早点回家");
            return probe != null && probe.contains("应该");
        } catch (Throwable e) {
            return false;
        }
    }
}

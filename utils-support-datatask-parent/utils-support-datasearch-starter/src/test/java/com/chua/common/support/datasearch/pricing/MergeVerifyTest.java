package com.chua.common.support.datasearch.pricing;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.DataSearchModelPricingProvider;
import com.chua.common.support.datasearch.pricing.spi.impl.ArtificialAnalysisModelMetricsProvider;
import com.chua.common.support.datasearch.pricing.spi.impl.OpenRouterModelMetricsProvider;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 临时验证:OpenRouter 数据解析 + DataSearchModelPricingProvider 多源合并逻辑。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MergeVerifyTest {

    /**
     * 运行验证。
     *
     * @param args ignored
     */
    public static void main(String[] args) throws Exception {
        // 1) OpenRouter 抓取解析
        List<ModelDefinition> orList = new OpenRouterModelMetricsProvider().fetchOnlinePricing();
        System.out.println("== openrouter total: " + orList.size());
        for (ModelDefinition md : orList) {
            if ("deepseek-v4-flash".equals(md.getId()) || "glm-5.3-flash".equals(md.getId())
                    || "gpt-5-mini".equals(md.getId())) {
                System.out.printf("OR %-18s in=%-9s out=%-9s cacheHit=%-9s image=%-10s search=%-9s imgIn=%-5s webSearch=%s%n",
                        md.getId(), md.getInputUnitPrice(), md.getOutputUnitPrice(),
                        md.getCacheHitPrice(), md.getImagePrice(), md.getWebSearchPrice(),
                        md.getImageInput(), md.getWebSearch());
            }
        }

        // 2) 合并逻辑验证(反射调用 private merge:AA 为基础,OpenRouter 补齐能力)
        List<ModelDefinition> aaList = new ArtificialAnalysisModelMetricsProvider().fetchOnlinePricing();
        ModelDefinition aaGpt5 = find(aaList, "gpt-5-mini");
        ModelDefinition orGpt5 = find(orList, "gpt-5-mini");
        System.out.println("== AA gpt-5-mini: in=" + aaGpt5.getInputUnitPrice() + " out=" + aaGpt5.getOutputUnitPrice()
                + " cacheHit=" + aaGpt5.getCacheHitPrice() + " ctx=" + aaGpt5.getContextWindowTokens()
                + " icon=" + aaGpt5.getIconUrl());
        System.out.println("== OR gpt-5-mini: imgIn=" + orGpt5.getImageInput() + " webSearch=" + orGpt5.getWebSearch()
                + " searchPrice=" + orGpt5.getWebSearchPrice());

        DataSearchModelPricingProvider bridge = new DataSearchModelPricingProvider();
        Method merge = DataSearchModelPricingProvider.class.getDeclaredMethod(
                "merge", ModelDefinition.class, ModelDefinition.class);
        merge.setAccessible(true);
        ModelDefinition merged = (ModelDefinition) merge.invoke(bridge, (Object) null, aaGpt5);
        merged = (ModelDefinition) merge.invoke(bridge, merged, orGpt5);
        System.out.printf("== MERGED gpt-5-mini: in=%-9s out=%-9s cacheHit=%-9s ctx=%-9s imgIn=%-5s webSearch=%-5s searchPrice=%-9s icon=%s%n",
                merged.getInputUnitPrice(), merged.getOutputUnitPrice(),
                merged.getCacheHitPrice(), merged.getContextWindowTokens(),
                merged.getImageInput(), merged.getWebSearch(), merged.getWebSearchPrice(),
                merged.getIconUrl());
    }

    /**
     * 按 id 查找模型。
     *
     * @param list 模型列表
     * @param id   模型 id
     * @return 模型定义；未找到返回 null
     */
    private static ModelDefinition find(List<ModelDefinition> list, String id) {
        for (ModelDefinition md : list) {
            if (id.equals(md.getId())) {
                return md;
            }
        }
        return null;
    }
}

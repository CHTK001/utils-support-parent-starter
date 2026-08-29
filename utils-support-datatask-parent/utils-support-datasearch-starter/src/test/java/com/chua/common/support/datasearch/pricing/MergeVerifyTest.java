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
                    || "gpt-5-mini".equals(md.getId()) || "gemini-3.7-flash".equals(md.getId())) {
                System.out.printf("OR %-18s in=%-9s out=%-9s cacheHit=%-9s image=%-10s search=%-9s imgIn=%-5s webSearch=%-5s reasoning=%-9s outMods=%s%n",
                        md.getId(), md.getInputUnitPrice(), md.getOutputUnitPrice(),
                        md.getCacheHitPrice(), md.getImagePrice(), md.getWebSearchPrice(),
                        md.getImageInput(), md.getWebSearch(),
                        md.getInternalReasoningPrice(), md.getOutputModalities());
            }
        }

        // 2) 合并逻辑验证(反射调用 private merge:AA 官方价为基础,OpenRouter 补齐能力)
        List<ModelDefinition> aaList = new ArtificialAnalysisModelMetricsProvider().fetchOnlinePricing();
        mergeAndPrint(aaList, orList, "deepseek-v4-flash");
        mergeAndPrint(aaList, orList, "gpt-5-mini");
        mergeAndPrint(aaList, orList, "gemini-3.7-flash");
    }

    /**
     * 合并单个模型并打印:验证官方价(AA)优先 + OpenRouter 补能力。
     *
     * @param aaList AA 抓取结果
     * @param orList OpenRouter 抓取结果
     * @param id     模型 id
     */
    private static void mergeAndPrint(List<ModelDefinition> aaList, List<ModelDefinition> orList, String id)
            throws Exception {
        ModelDefinition aa = find(aaList, id);
        ModelDefinition or = find(orList, id);
        if (aa == null || or == null) {
            System.out.println("== " + id + " 缺少数据源: aa=" + (aa != null) + " or=" + (or != null));
            return;
        }
        System.out.println("== AA " + id + ": in=" + aa.getInputUnitPrice() + " out=" + aa.getOutputUnitPrice()
                + " cacheHit=" + aa.getCacheHitPrice() + " ctx=" + aa.getContextWindowTokens()
                + " e2e=" + aa.getEndToEndResponseTimeSeconds() + " deprecated=" + aa.getDeprecated()
                + " imgIn=" + aa.getImageInput() + " webSearch=" + aa.getWebSearch());
        System.out.println("== OR " + id + ": in=" + or.getInputUnitPrice() + " out=" + or.getOutputUnitPrice()
                + " cacheHit=" + or.getCacheHitPrice() + " imgIn=" + or.getImageInput()
                + " webSearch=" + or.getWebSearch() + " searchPrice=" + or.getWebSearchPrice()
                + " reasoningPrice=" + or.getInternalReasoningPrice()
                + " outMods=" + or.getOutputModalities());

        DataSearchModelPricingProvider bridge = new DataSearchModelPricingProvider();
        Method merge = DataSearchModelPricingProvider.class.getDeclaredMethod(
                "merge", ModelDefinition.class, ModelDefinition.class);
        merge.setAccessible(true);
        ModelDefinition merged = (ModelDefinition) merge.invoke(bridge, (Object) null, aa);
        merged = (ModelDefinition) merge.invoke(bridge, merged, or);
        System.out.printf("== MERGED %-16s: in=%-9s out=%-9s cacheHit=%-9s ctx=%-9s e2e=%-8s deprecated=%-5s imgIn=%-5s webSearch=%-5s searchPrice=%-9s reasoningPrice=%-9s outMods=%s%n",
                id, merged.getInputUnitPrice(), merged.getOutputUnitPrice(),
                merged.getCacheHitPrice(), merged.getContextWindowTokens(),
                merged.getEndToEndResponseTimeSeconds(), merged.getDeprecated(),
                merged.getImageInput(), merged.getWebSearch(), merged.getWebSearchPrice(),
                merged.getInternalReasoningPrice(), merged.getOutputModalities());
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

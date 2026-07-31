package com.chua.common.support.datasearch.pricing.spi.impl;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.datasearch.pricing.spi.AbstractPricingProvider;
import com.chua.common.support.spi.annotations.Spi;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * DeepSeek 系列模型定价提供者。
 *
 * <p>包含 V3、R1 等模型定价。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("deepseek")
public class DeepSeekPricingProvider extends AbstractPricingProvider {

    private static final String PRICING_URL = "https://api-docs.deepseek.com/quick_start/pricing/";

    @Override
    public List<ModelDefinition> fetchOnlinePricing() {
        String html = fetchUrl(PRICING_URL);
        if (html == null || html.isEmpty()) {
            return readClasspathPricing();
        }
        try {
            Document doc = Jsoup.parse(html);
            Elements rows = doc.select("table tbody tr");
            if (rows.isEmpty()) {
                return readClasspathPricing();
            }
            List<ModelDefinition> result = new ArrayList<>();
            for (Element row : rows) {
                Elements cols = row.select("td");
                if (cols.size() < 3) continue;
                String model = cols.get(0).text().trim();
                String inputPriceStr = cols.get(1).text().replace("$", "").trim();
                String outputPriceStr = cols.get(2).text().replace("$", "").trim();
                if (model.isEmpty()) continue;
                try {
                    BigDecimal inputPrice = new BigDecimal(inputPriceStr);
                    BigDecimal outputPrice = new BigDecimal(outputPriceStr);
                    result.add(ModelDefinition.builder()
                            .id(model)
                            .name(model)
                            .provider("deepseek")
                            .capabilities(List.of("chat"))
                            .inputUnitPrice(inputPrice)
                            .outputUnitPrice(outputPrice)
                            .currency("USD")
                            .build());
                } catch (NumberFormatException ignored) {
                    // 跳过无效行
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        } catch (Exception e) {
            log.debug("[deepseek] 解析定价页面失败: {}", e.getMessage());
        }
        return readClasspathPricing();
    }
}

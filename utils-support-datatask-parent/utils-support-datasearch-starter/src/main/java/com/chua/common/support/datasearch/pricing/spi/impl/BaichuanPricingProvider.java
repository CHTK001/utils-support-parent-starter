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
 * 百川智能 Baichuan 系列模型定价提供者。
 *
 * <p>包含 Baichuan 等模型定价。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("baichuan")
public class BaichuanPricingProvider extends AbstractPricingProvider {

    private static final String PRICING_URL = "https://platform.baichuan-ai.com/docs/pricing";

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
                if (cols.size() < 3) {
                String model = cols.get(0).text().trim();
                String inputPriceStr = cols.get(1).text().replace("元", "").replace("$", "").trim();
                String outputPriceStr = cols.get(2).text().replace("元", "").replace("$", "").trim();
                if (model.isEmpty()) {
                try {
                    BigDecimal inputPrice = new BigDecimal(inputPriceStr);
                    BigDecimal outputPrice = new BigDecimal(outputPriceStr);
                    result.add(ModelDefinition.builder()
                            .id(model)
                            .name(model)
                            .provider("baichuan")
                            .capabilities(List.of("chat"))
                            .inputUnitPrice(inputPrice)
                            .outputUnitPrice(outputPrice)
                            .currency("CNY")
                            .build());
                } catch (NumberFormatException ignored) {
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        } catch (Exception e) {
            log.debug("[baichuan] 解析定价页面失败: {}", e.getMessage());
        }
        return readClasspathPricing();
    }
}

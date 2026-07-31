package com.chua.common.support.datasearch.express.spi.impl;

import com.chua.common.support.datasearch.express.model.ExpressTrace;
import com.chua.common.support.datasearch.express.spi.ExpressProvider;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClient;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 基于快递100的快递物流查询提供器（在线 API）。
 *
 * <p>默认调用快递100公开查询接口：
 * <ul>
 *   <li>智能识别公司：{@code https://www.kuaidi100.com/autonumber/autoComNum?resultv2=1&text={no}}</li>
 *   <li>轨迹查询：{@code https://www.kuaidi100.com/query?type={company}&postid={no}}</li>
 * </ul>
 * 生产环境快递100已要求鉴权（customer/key），可通过构造参数注入自定义地址模板。
 *
 * @author CH
 * @since 2026/07/27
 */
@Spi("kuaidi100")
public class Kuaidi100ExpressProvider implements ExpressProvider {

    private static final Logger log = LoggerFactory.getLogger(Kuaidi100ExpressProvider.class);

    private static final String DEFAULT_QUERY = "https://www.kuaidi100.com/query?type=%s&postid=%s";

    private static final String DEFAULT_AUTO = "https://www.kuaidi100.com/autonumber/autoComNum?resultv2=1&text=%s";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String queryUrlTemplate;

    private final String autoUrlTemplate;

    private final HttpClient httpClient;

    public Kuaidi100ExpressProvider() {
        this(DEFAULT_QUERY, DEFAULT_AUTO);
    }

    /**
     * 构造一个指定地址模板的提供器（支持鉴权代理）。
     *
     * @param queryUrlTemplate 轨迹查询模板，含 {@code %s,%s} → company, no
     * @param autoUrlTemplate  公司识别模板，含 {@code %s} → no
     */
    public Kuaidi100ExpressProvider(String queryUrlTemplate, String autoUrlTemplate) {
        this.queryUrlTemplate = queryUrlTemplate;
        this.autoUrlTemplate = autoUrlTemplate;
        this.httpClient = HttpClientFactory.getClient();
    }

    @Override
    public String name() {
        return "kuaidi100";
    }

    @Override
    public List<ExpressTrace> query(String trackingNo) {
        String company = detect(trackingNo);
        if (company == null || company.isEmpty()) {
            log.warn("无法识别快递公司: {}", trackingNo);
            return Collections.emptyList();
        }
        return query(company, trackingNo);
    }

    @Override
    public List<ExpressTrace> query(String companyCode, String trackingNo) {
        String url = String.format(queryUrlTemplate, companyCode, trackingNo);
        try {
            ClientResponse resp = httpClient.get(url);
            if (!resp.isSuccess()) {
                log.warn("快递查询失败: {} -> {}", url, resp.getStatusCode());
                return Collections.emptyList();
            }
            return parse(resp.getBodyString());
        } catch (Exception e) {
            log.warn("快递查询异常: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String detect(String no) {
        try {
            ClientResponse resp = httpClient.get(String.format(autoUrlTemplate, no));
            if (!resp.isSuccess()) {
                return null;
            }
            JsonNode root = MAPPER.readTree(resp.getBodyString());
            JsonNode auto = root.get("auto");
            if (auto != null && auto.isArray() && auto.size() > 0) {
                JsonNode first = auto.get(0);
                JsonNode com = first.get("comCode");
                return com == null ? null : com.asText();
            }
        } catch (Exception e) {
            log.warn("快递公司识别异常: {}", e.getMessage());
        }
        return null;
    }

    private List<ExpressTrace> parse(String json) {
        List<ExpressTrace> list = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                return list;
            }
            for (JsonNode d : data) {
                list.add(new ExpressTrace(text(d, "time"), text(d, "context"), text(d, "location")));
            }
        } catch (Exception e) {
            log.warn("快递轨迹解析失败: {}", e.getMessage());
        }
        return list;
    }

    private static String text(JsonNode n, String k) {
        JsonNode v = n.get(k);
        return v == null ? "" : v.asText();
    }
}

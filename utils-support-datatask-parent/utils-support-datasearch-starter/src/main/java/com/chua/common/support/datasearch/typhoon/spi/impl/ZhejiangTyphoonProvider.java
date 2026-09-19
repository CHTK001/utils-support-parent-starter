package com.chua.common.support.datasearch.typhoon.spi.impl;

import com.chua.common.support.datasearch.typhoon.model.TyphoonActivity;
import com.chua.common.support.datasearch.typhoon.model.TyphoonDetail;
import com.chua.common.support.datasearch.typhoon.spi.TyphoonProvider;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 浙江省水利厅台风数据源实现。
 *
 * <p>政府官方数据（完全免费，无 key）：</p>
 * <ul>
 *   <li>活跃台风列表 {@code /Api/TyhoonActivity}：HttpClient 实体请求
 * （httpinvoker 声明式代理不支持泛型 列表 返回，故用 类型引用 解析）</li>
 *   <li>单个台风详情 {@code /Api/TyphoonInfo/{tfid}}：HttpClient 动态路径请求，
 *       含历史路径与 4 家机构（中国/日本/美国等）预报</li>
 * </ul>
 *
 * <p>30 分钟内存缓存（惰性刷新，不内置定时任务）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("zhejiang-typhoon")
public class ZhejiangTyphoonProvider implements TyphoonProvider {

    private static final Logger log = LoggerFactory.getLogger(ZhejiangTyphoonProvider.class); // 日志

    private static final ObjectMapper MAPPER = new ObjectMapper(); // 映射器

    /** 活跃台风列表地址 */
    private static final String ACTIVITY_URL = "https://typhoon.slt.zj.gov.cn/Api/TyhoonActivity";

    /** 详情接口地址模板 */
    private static final String INFO_URL = "https://typhoon.slt.zj.gov.cn/Api/TyphoonInfo/%s";

    /** 缓存有效期（毫秒）：30 分钟 */
    private static final long CACHE_TTL_MILLIS = 30 * 60 * 1000L;

    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126 Safari/537.36";

    /** 活跃列表缓存 */
    private volatile List<TyphoonActivity> cachedList;
    private volatile long listCachedAt; // 列表缓存at

    /** 详情缓存（tfid -> 详情） */
    private final Map<String, TyphoonDetail> cachedDetail = new ConcurrentHashMap<>();
    private final Map<String, Long> detailCachedAt = new ConcurrentHashMap<>(); // detail缓存at

    @Override
    public String name() {
        return "zhejiang-typhoon";
    }

    @Override
    public List<TyphoonActivity> getActiveTyphoons() {
        if (cachedList != null && System.currentTimeMillis() - listCachedAt < CACHE_TTL_MILLIS) {
            return cachedList;
        }
        try {
            String json = HttpClientFactory.of(ACTIVITY_URL)
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .get().getBodyString();
            List<TyphoonActivity> list = MAPPER.readValue(json,
                    new TypeReference<List<TyphoonActivity>>() {
                    });
            if (list != null) {
                cachedList = list;
                listCachedAt = System.currentTimeMillis();
                return list;
            }
        } catch (Exception e) {
            log.warn("[zhejiang-typhoon] 获取活跃台风失败: {}", e.getMessage());
        }
        return cachedList == null ? Collections.emptyList() : cachedList;
    }

    @Override
    public TyphoonDetail getTyphoon(String tfid) {
        if (tfid == null || tfid.isBlank()) {
            return null;
        }
        Long cachedAt = detailCachedAt.get(tfid);
        if (cachedAt != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MILLIS) {
            return cachedDetail.get(tfid);
        }
        try {
            String json = HttpClientFactory.of(String.format(INFO_URL, tfid.trim()))
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .get().getBodyString();
            TyphoonDetail detail = MAPPER.readValue(json, TyphoonDetail.class);
            if (detail != null) {
                cachedDetail.put(tfid, detail);
                detailCachedAt.put(tfid, System.currentTimeMillis());
            }
            return detail;
        } catch (Exception e) {
            log.warn("[zhejiang-typhoon] 获取台风详情失败: tfid={}, msg={}", tfid, e.getMessage());
            return cachedDetail.get(tfid);
        }
    }
}

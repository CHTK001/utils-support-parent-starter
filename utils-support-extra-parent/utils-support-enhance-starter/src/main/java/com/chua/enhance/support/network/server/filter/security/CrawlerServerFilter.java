package com.chua.enhance.support.network.server.filter.security;

import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterConfig;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
* 爬虫拦截过滤器，基于 用户-智能体 识别并拦截爬虫请求，并检测客户端的周期性重复请求。
*
* <p><b>User-Agent 拦截：</b>内置常见爬虫特征关键词（搜索引擎爬虫、下载工具、HTTP 客户端库等），
* 命中后默认返回 403 拦截；可通过配置关闭拦截仅记录日志。</p>
*
* <p><b>周期性重复请求检测：</b>按「客户端 IP + 请求方法 + 请求地址」聚合，在检测窗口内统计相同请求的
* 次数与时间间隔。当窗口内请求次数达到阈值且时间间隔呈现规律周期时，仅记录告警日志，不拦截请求。</p>
*
* <h2>配置参数</h2>
* <ul>
*   <li>{@code crawler.enabled} — 是否启用，默认 true</li>
*   <li>{@code crawler.blockEnabled} — User-Agent 命中后是否拦截，默认 true</li>
*   <li>{@code crawler.blockStatus} — 拦截状态码，默认 403</li>
*   <li>{@code crawler.customUaKeywords} — 自定义追加的爬虫关键词（逗号分隔）</li>
*   <li>{@code crawler.periodEnabled} — 是否启用周期性重复请求检测，默认 true</li>
*   <li>{@code crawler.periodMinTimes} — 窗口内相同请求次数阈值，默认 5</li>
*   <li>{@code crawler.periodWindowSeconds} — 检测窗口（秒），默认 60</li>
*   <li>{@code crawler.periodMaxIntervalRatio} — 周期判定阈值（最大间隔/最小间隔），默认 1.5</li>
*   <li>{@code crawler.cleanupIntervalSeconds} — 过期记录清理周期（秒），默认 300</li>
* </ul>
*
* @author CH
* @since 2026/07/16
 */
@Slf4j
public class CrawlerServerFilter implements ServerFilter {

    /**
    * 默认爬虫 用户-智能体 特征关键词
     */
    private static final Set<String> DEFAULT_UA_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "googlebot", "bingbot", "yandexbot", "baiduspider", "sogou", "360spider", "bytespider",
            "petalbot", "duckduckbot", "ia_archiver", "mj12bot", "ahrefsbot", "semrushbot", "dotbot",
            "rogerbot", "exabot", "slurp", "crawler", "spider", "mediapartners-google", "adsbot-google",
            "facebookexternalhit", "facebookbot", "twitterbot", "linkedinbot", "whatsapp",
            "curl", "wget", "python-requests", "python-urllib", "go-http-client", "httpclient",
            "apache-httpclient", "okhttp", "scrapy", "nutch", "headlesschrome", "phantomjs",
            "java/", "libwww-perl", "bot"
    )));

    /**
    * 默认启用
     */
    private static final boolean DEFAULT_ENABLED = true;
    /**
    * 默认启用 用户-智能体 拦截
     */
    private static final boolean DEFAULT_BLOCK_ENABLED = true;
    /**
    * 默认拦截状态码
     */
    private static final int DEFAULT_BLOCK_STATUS = 403;
    /**
    * 默认启用周期性重复请求检测
     */
    private static final boolean DEFAULT_PERIOD_ENABLED = true;
    /**
    * 默认窗口内相同请求次数阈值
     */
    private static final int DEFAULT_PERIOD_MIN_TIMES = 5;
    /**
    * 默认检测窗口（秒）
     */
    private static final int DEFAULT_PERIOD_WINDOW_SECONDS = 60;
    /**
    * 默认周期判定阈值
     */
    private static final double DEFAULT_PERIOD_MAX_INTERVAL_RATIO = 1.5;
    /**
    * 默认清理周期（秒）
     */
    private static final long DEFAULT_CLEANUP_INTERVAL_SECONDS = 300;

    /** 是否启用 */
    private boolean enabled = DEFAULT_ENABLED;
    /** Block是否启用 */
    private boolean blockEnabled = DEFAULT_BLOCK_ENABLED;
    /** Block状态 */
    private int blockStatus = DEFAULT_BLOCK_STATUS;
    /** 周期是否启用 */
    private boolean periodEnabled = DEFAULT_PERIOD_ENABLED;
    /** 周期最小值时间 */
    private int periodMinTimes = DEFAULT_PERIOD_MIN_TIMES;
    /** Periodwindow秒 */
    private int periodWindowSeconds = DEFAULT_PERIOD_WINDOW_SECONDS;
    /** 周期最大值间隔比率 */
    private double periodMaxIntervalRatio = DEFAULT_PERIOD_MAX_INTERVAL_RATIO;
    /** Cleanup间隔秒 */
    private long cleanupIntervalSeconds = DEFAULT_CLEANUP_INTERVAL_SECONDS;

    /**
    * 爬虫 用户-智能体 关键词集合（内置 + 自定义）
     */
    private final Set<String> uaKeywords = new HashSet<>(DEFAULT_UA_KEYWORDS);

    /**
    * 客户端周期请求记录：键 = IP|方法|URI → 窗口内请求时间戳队列
     */
    private final Map<String, Deque<Long>> periodRecords = new ConcurrentHashMap<>();

    /**
    * 过期记录清理任务
     */
    private ScheduledExecutorService cleanupExecutor;

    @Override
    /** 初始化 */
    public void init(ServerFilterConfig config) throws Exception {
        String enabledVal = config.getInitParameter("crawler.enabled");
        if (enabledVal != null && !enabledVal.isEmpty()) {
            this.enabled = Boolean.parseBoolean(enabledVal);
        }
        String blockVal = config.getInitParameter("crawler.blockEnabled");
        if (blockVal != null && !blockVal.isEmpty()) {
            this.blockEnabled = Boolean.parseBoolean(blockVal);
        }
        String statusVal = config.getInitParameter("crawler.blockStatus");
        if (statusVal != null && !statusVal.isEmpty()) {
            this.blockStatus = Integer.parseInt(statusVal);
        }
        String customKeywords = config.getInitParameter("crawler.customUaKeywords");
        if (customKeywords != null && !customKeywords.isEmpty()) {
            for (String keyword : customKeywords.split(",")) {
                String trimmed = keyword.trim();
                if (!trimmed.isEmpty()) {
                    this.uaKeywords.add(trimmed.toLowerCase());
                }
            }
        }
        String periodEnabledVal = config.getInitParameter("crawler.periodEnabled");
        if (periodEnabledVal != null && !periodEnabledVal.isEmpty()) {
            this.periodEnabled = Boolean.parseBoolean(periodEnabledVal);
        }
        String minTimesVal = config.getInitParameter("crawler.periodMinTimes");
        if (minTimesVal != null && !minTimesVal.isEmpty()) {
            this.periodMinTimes = Integer.parseInt(minTimesVal);
        }
        String windowVal = config.getInitParameter("crawler.periodWindowSeconds");
        if (windowVal != null && !windowVal.isEmpty()) {
            this.periodWindowSeconds = Integer.parseInt(windowVal);
        }
        String ratioVal = config.getInitParameter("crawler.periodMaxIntervalRatio");
        if (ratioVal != null && !ratioVal.isEmpty()) {
            this.periodMaxIntervalRatio = Double.parseDouble(ratioVal);
        }
        String cleanupVal = config.getInitParameter("crawler.cleanupIntervalSeconds");
        if (cleanupVal != null && !cleanupVal.isEmpty()) {
            this.cleanupIntervalSeconds = Long.parseLong(cleanupVal);
        }
        startCleanup();
    }

    @Override
    /** 销毁 */
    public void destroy() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdownNow();
            cleanupExecutor = null;
        }
        periodRecords.clear();
    }

    @Override
    /** 执行过滤 */
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);
        String userAgent = request.getHeader("User-Agent");

        if (userAgent != null && !userAgent.isEmpty() && isCrawler(userAgent)) {
            if (blockEnabled) {
                response.end(blockStatus,
                        "{\"error\":\"Forbidden\",\"message\":\"crawler request blocked\"}");
                return;
            }
            log.warn("[Crawler] User-Agent 命中爬虫特征: client={}, uri={}, ua={}",
                    clientIp, request.getUri(), userAgent);
        }

        if (periodEnabled) {
            detectPeriodicRequest(request, clientIp);
        }

        chain.doFilter(request, response);
    }

    @Override
    /** 获取订单 */
    public int getOrder() {
        return 30;
    }

    @Override
    /** 获取过滤标识 */
    public String getFilterId() {
        return "CrawlerServerFilter";
    }

    /**
    * 判断 用户-智能体 是否命中爬虫特征关键词。
    *
    * @param userAgent 用户-智能体 请求头值
    * @return true 表示命中爬虫特征
     */
    private boolean isCrawler(String userAgent) {
        String ua = userAgent.toLowerCase();
        for (String keyword : uaKeywords) {
            if (ua.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
    * 检测同一个客户端是否周期性请求相同地址，命中时仅记录日志。
    * @param request 请求
    * @param clientIp 客户端ip
     */
    private void detectPeriodicRequest(ServerRequest request, String clientIp) {
        String uri = request.getUri();
        if (uri == null || uri.isEmpty()) {
            return;
        }
        String method = request.getMethod() != null ? request.getMethod().name() : "UNKNOWN";
        String key = clientIp + "|" + method + "|" + uri;
        long now = System.currentTimeMillis();
        long windowMillis = periodWindowSeconds * 1000L;

        Deque<Long> times = periodRecords.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            times.addLast(now);
            while (!times.isEmpty() && now - times.peekFirst() > windowMillis) {
                times.pollFirst();
            }
            if (times.size() >= periodMinTimes && isPeriodic(times)) {
                log.warn("[Crawler] 检测到周期性重复请求: client={}, method={}, uri={}, 窗口内次数={}, 窗口={}s, 间隔范围={}~{}ms",
                        clientIp, method, uri, times.size(), periodWindowSeconds,
                        minInterval(times), maxInterval(times));
                times.clear();
            }
        }
    }

    /**
    * 判断时间戳队列的相邻请求间隔是否呈现规律周期（最大间隔 / 最小间隔 ≤ 阈值）。
    * @param times 时间
    * @return 是否periodic的结果
     */
    private boolean isPeriodic(Deque<Long> times) {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        Long prev = null;
        for (Long time : times) {
            if (prev != null) {
                long interval = time - prev;
                if (interval <= 0) {
                    return false;
                }
                min = Math.min(min, interval);
                max = Math.max(max, interval);
            }
            prev = time;
        }
        return max > 0 && min > 0 && (double) max / min <= periodMaxIntervalRatio;
    }

    /**
    * 最小值间隔
    *
    * @param times 时间
    * @return 最小间隔的结果
     */
    private long minInterval(Deque<Long> times) {
        long min = Long.MAX_VALUE;
        Long prev = null;
        for (Long time : times) {
            if (prev != null) {
                min = Math.min(min, time - prev);
            }
            prev = time;
        }
        return min == Long.MAX_VALUE ? 0 : min;
    }

    /**
    * 最大值间隔
    *
    * @param times 时间
    * @return 最大间隔的结果
     */
    private long maxInterval(Deque<Long> times) {
        long max = Long.MIN_VALUE;
        Long prev = null;
        for (Long time : times) {
            if (prev != null) {
                max = Math.max(max, time - prev);
            }
            prev = time;
        }
        return max == Long.MIN_VALUE ? 0 : max;
    }

    /**
    * 启动过期记录清理任务，防止内存无界增长。
     */
    private void startCleanup() {
        cleanupExecutor = ThreadUtils.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "crawler-filter-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        cleanupExecutor.scheduleWithFixedDelay(this::cleanupExpired,
                cleanupIntervalSeconds, cleanupIntervalSeconds, TimeUnit.SECONDS);
    }

    /**
    * 清理最后一次访问已超出检测窗口的周期请求记录。
     */
    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        long windowMillis = periodWindowSeconds * 1000L;
        periodRecords.entrySet().removeIf(entry -> {
            Deque<Long> times = entry.getValue();
            synchronized (times) {
                return times.isEmpty() || now - times.peekLast() > windowMillis;
            }
        });
    }

    /**
    * 解析客户端真实 IP，优先从 X-远期-For 头获取。
    * @param request 请求
    * @return resolve客户端ip的结果
     */
    private String resolveClientIp(ServerRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            int idx = forwarded.indexOf(',');
            return idx > 0 ? forwarded.substring(0, idx).trim() : forwarded.trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp;
        }
        return request.getRemoteAddress();
    }
}

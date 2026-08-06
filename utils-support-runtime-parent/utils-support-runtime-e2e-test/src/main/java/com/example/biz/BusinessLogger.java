package com.example.biz;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 业务日志类 — 测试 LogHandler 拦截 SLF4J。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class BusinessLogger {

    /**
     * 日志记录器
     */
    private static final Logger LOG = LoggerFactory.getLogger(BusinessLogger.class);

    /**
     * 输出业务日志。
     */
    public static void logBusiness(String msg) {
        LOG.info("business: {}", msg);
    }
}

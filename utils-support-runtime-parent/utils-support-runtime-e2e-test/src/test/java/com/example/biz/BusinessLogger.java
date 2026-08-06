package com.example.biz;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 业务日志门面 — 测试用 fixture，模拟用户业务代码中常见的 SLF4J 调用。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class BusinessLogger {

    private static final Logger LOG = LoggerFactory.getLogger(BusinessLogger.class);

    /**
     * 记录普通业务日志。
     *
     * @param message 日志内容
     */
    public void info(String message) {
        LOG.info("业务日志: {}", message);
    }

    /**
     * 记录异常日志。
     *
     * @param message   日志内容
     * @param throwable 异常对象
     */
    public void error(String message, Throwable throwable) {
        LOG.error("业务异常: {}", message, throwable);
    }
}

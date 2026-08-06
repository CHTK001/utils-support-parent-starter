package com.example.biz;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试用业务服务 — 模拟"创建订单"流程，用于验证 RuntimeSpy / TraceHandler 跨方法追踪。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OrderService {

    private static final Logger LOG = LoggerFactory.getLogger(OrderService.class);

    private static final AtomicLong COUNTER = new AtomicLong(0L);

    private final BusinessLogger logger = new BusinessLogger();

    /**
     * 创建订单 — 业务入口。
     *
     * @param amount 金额
     * @return 订单 ID
     */
    public String createOrder(long amount) {
        long orderId = COUNTER.incrementAndGet();
        LOG.info("创建订单 amount={} orderId={}", amount, orderId);
        validateAmount(amount);
        saveOrder(orderId, amount);
        logger.info("订单创建完成");
        return "ORDER-" + orderId;
    }

    /**
     * 校验金额。
     *
     * @param amount 金额
     */
    public void validateAmount(long amount) {
        if (amount <= 0L) {
            throw new IllegalArgumentException("金额必须大于 0");
        }
        LOG.debug("金额校验通过: {}", amount);
    }

    /**
     * 保存订单。
     *
     * @param orderId 订单 ID
     * @param amount  金额
     */
    public void saveOrder(long orderId, long amount) {
        LOG.info("保存订单 orderId={} amount={}", orderId, amount);
    }
}

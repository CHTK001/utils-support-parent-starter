package com.example.biz;

/**
 * 业务类 — 用于演示 ASM 字节码插桩。
 *
 * <p>放在 com/example/* 包以避开 SpyTransformer 的 isSkipClass("com/chua/runtime/")。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OrderService {

    /**
     * 创建订单 — 入口/出口插桩的目标方法。
     *
     * @param id 订单 ID
     * @return 订单 ID
     */
    public String createOrder(String id) {
        validate(id);
        return "ORDER-" + id;
    }

    /**
     * 校验订单 — 嵌套调用，验证 traceId/spanId 父子关系。
     *
     * @param id 订单 ID
     */
    public void validate(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("订单 ID 不能为空");
        }
    }

    /**
     * 失败订单 — 验证异常插桩。
     */
    public String failedOrder() {
        try {
            throw new RuntimeException("业务异常");
        } catch (RuntimeException e) {
            return "FAILED";
        }
    }
}

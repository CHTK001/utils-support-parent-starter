package com.chua.common.support.lang.json;

import com.chua.common.support.lang.json.annotation.JsonBeanMapper;
import com.chua.common.support.lang.json.annotation.JsonFormat;
import com.chua.common.support.lang.json.annotation.JsonIgnore;
import com.chua.common.support.lang.json.annotation.JsonName;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一门户注解测试：验证 {@link JsonName} / {@link JsonIgnore} / {@link JsonFormat}
 * 在默认 Jackson 实现（{@link CommonJsonAnnotationIntrospector}）与
 * {@link JsonBeanMapper} 桥接器中的一致行为。
 *
 * @author CH
 * @since 4.0.0.42
 */
class JsonUnifiedAnnotationTest {

    /**
     * 门户注解测试实体。
     */
    static class Order implements Serializable {
        @JsonName("order_id")
        /** 排序ID */
        private String orderId;

        @JsonIgnore
        /** 内部 */
        private String internal;

        @JsonFormat("yyyy-MM-dd HH:mm:ss")
        /** Create时间 */
        private LocalDateTime createTime;

        /** Amount */
        private double amount;

        public Order() {
        }

        public Order(String orderId, String internal, LocalDateTime createTime, double amount) {
            this.orderId = orderId;
            this.internal = internal;
            this.createTime = createTime;
            this.amount = amount;
        }

        public String getOrderId() {
            return orderId;
        }

        public void setOrderId(String orderId) {
            this.orderId = orderId;
        }

        public String getInternal() {
            return internal;
        }

        public void setInternal(String internal) {
            this.internal = internal;
        }

        public LocalDateTime getCreateTime() {
            return createTime;
        }

        public void setCreateTime(LocalDateTime createTime) {
            this.createTime = createTime;
        }

        public double getAmount() {
            return amount;
        }

        public void setAmount(double amount) {
            this.amount = amount;
        }
    }

    /**
     * 验证默认 Jackson 实现识别统一门户注解。
     */
    @Test
    void testJacksonAdaptsUnifiedAnnotations() {
        LocalDateTime time = LocalDateTime.of(2026, 8, 15, 12, 30, 0);
        String json = Json.toJson(new Order("A001", "secret", time, 99.5));

        assertTrue(json.contains("\"order_id\":\"A001\""), "应使用 @JsonName 指定的字段名: " + json);
        assertFalse(json.contains("internal"), "被 @JsonIgnore 标记的字段不应序列化");
        assertTrue(json.contains("\"createTime\":\"2026-08-15 12:30:00\""), "应使用 @JsonFormat 格式: " + json);

        Order order = Json.fromJson(
                "{\"order_id\":\"A001\",\"internal\":\"x\",\"createTime\":\"2026-08-15 12:30:00\",\"amount\":99.5}",
                Order.class);
        assertEquals("A001", order.getOrderId());
        assertEquals(null, order.getInternal());
        assertEquals(time, order.getCreateTime());
        assertEquals(99.5, order.getAmount(), 0.001);
    }

    /**
     * 验证 JsonBeanMapper 桥接器行为（供 fory 等无法原生识别注解的实现使用）。
     */
    @Test
    void testJsonBeanMapperBridge() {
        LocalDateTime time = LocalDateTime.of(2026, 8, 15, 12, 30, 0);
        Order order = new Order("A002", "secret", time, 10.0);

        Object mapped = JsonBeanMapper.toMap(order);
        assertTrue(mapped instanceof Map);
        Map<String, Object> map = (Map<String, Object>) mapped;
        assertEquals("A002", map.get("order_id"));
        assertFalse(map.containsKey("internal"), "@JsonIgnore 字段不应出现在 Map 中");
        assertEquals("2026-08-15 12:30:00", map.get("createTime"));

        // 反向填充
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("order_id", "A003");
        source.put("internal", "secret");
        source.put("createTime", "2026-08-15 12:30:00");
        source.put("amount", 20.0);
        Order restored = JsonBeanMapper.fromMap(source, Order.class);
        assertEquals("A003", restored.getOrderId());
        assertEquals(null, restored.getInternal());
        assertEquals(LocalDateTime.parse("2026-08-15 12:30:00", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                restored.getCreateTime());
        assertEquals(20.0, restored.getAmount(), 0.001);
    }
}

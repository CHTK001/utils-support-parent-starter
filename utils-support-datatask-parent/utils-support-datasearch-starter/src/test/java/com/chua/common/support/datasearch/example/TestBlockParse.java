package com.chua.common.support.datasearch.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;

/**
 * 直接验证 blocked-providers.json 解析。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TestBlockParse {

    /** 防止工具类实例化 */
    private TestBlockParse() {
    }

    /**
     * 测试入口。
     *
     * @param args 无
     */
    public static void main(String[] args) throws Exception {
        byte[] bytes = TestBlockParse.class.getClassLoader()
                .getResourceAsStream("blocked-providers.json").readAllBytes();
        System.out.println("json len=" + bytes.length);

        ObjectMapper mapper = new ObjectMapper();
        com.chua.common.support.datasearch.video.spi.VideoProviderRegistry.Root root =
                mapper.readValue(bytes, com.chua.common.support.datasearch.video.spi.VideoProviderRegistry.Root.class);
        System.out.println("root=" + (root == null ? "null" : "ok"));
        if (root != null) {
            System.out.println("blocked list = " + (root.getBlocked() == null ? "null" : root.getBlocked().size()));
        }

        // 同时验证静态表
        Class<?> c = com.chua.common.support.datasearch.video.spi.VideoProviderRegistry.class;
        c.getDeclaredMethod("initBlockedResources").invoke(null);
        Field f = c.getDeclaredField("BLOCKED_PROVIDERS");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, ?> map = (java.util.Map<String, ?>) f.get(null);
        System.out.println("BLOCKED size=" + map.size() + " keys=" + map.keySet());
    }
}

package com.chua.common.support.datasearch.example;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 定位 blocked-providers.json 加载失败原因。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TestBlockLoad {

    /** 防止工具类实例化 */
    private TestBlockLoad() {
    }

    /**
     * 测试入口。
     *
     * @param args 无
     */
    public static void main(String[] args) throws Exception {
        String[] cpEntries = System.getProperty("java.class.path").split(";");
        System.out.println("== classpath ==");
        for (String e : cpEntries) {
            System.out.println("  " + e);
        }

        System.out.println("== getResourceAsStream ==");
        InputStream is = TestBlockLoad.class.getClassLoader().getResourceAsStream("blocked-providers.json");
        System.out.println("is = " + (is == null ? "NULL" : "OK"));
        if (is != null) {
            byte[] b = is.readAllBytes();
            System.out.println("len=" + b.length + " head=" + new String(b, 0, Math.min(50, b.length)));
        }

        // 逐 entry 查找资源
        System.out.println("== per-entry lookup ==");
        for (String entry : cpEntries) {
            String p = entry + "\\blocked-providers.json";
            java.io.File f = new java.io.File(p);
            System.out.println("  " + entry + " -> " + f.exists());
        }

        Class<?> c = Class.forName("com.chua.common.support.datasearch.video.spi.VideoProviderRegistry");
        Method init = c.getMethod("initBlockedResources");
        init.invoke(null);
        Field f = c.getDeclaredField("BLOCKED_PROVIDERS");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, ?> map = (java.util.Map<String, ?>) f.get(null);
        System.out.println("BLOCKED size=" + map.size() + " keys=" + map.keySet());
    }
}

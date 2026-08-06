package com.chua.runtime.e2e;

import com.chua.runtime.plugin.InterceptPoint;
import com.chua.runtime.spy.SpyTransformer;
import com.example.biz.OrderService;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;

/**
 * 调试用：直接调用 SpyTransformer 看为什么返回 null。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DebugTransformerTest {

    private static final String ORDER_SERVICE = "com/example/biz/OrderService";

    @Test
    public void debugTransform() throws Exception {
        SpyTransformer transformer = new SpyTransformer(null, Collections.emptyList(), Collections.emptyList());
        transformer.registerMethod(ORDER_SERVICE, "createOrder", InterceptPoint.ENTRY);
        transformer.registerMethod(ORDER_SERVICE, "createOrder", InterceptPoint.EXIT);

        Class<?> orderClazz = OrderService.class;
        String resource = orderClazz.getName().replace('.', '/') + ".class";
        byte[] originalBytes;
        try (java.io.InputStream is = orderClazz.getClassLoader().getResourceAsStream(resource)) {
            assert is != null;
            originalBytes = is.readAllBytes();
        }

        // 通过反射调 transform 看真实返回
        Method m = SpyTransformer.class.getDeclaredMethod("transform",
                ClassLoader.class, String.class, Class.class,
                java.security.ProtectionDomain.class, byte[].class);
        m.setAccessible(true);

        // 在 transform 之前插入 spy，捕获 ASM 异常
        try {
            byte[] result = (byte[]) m.invoke(transformer,
                    orderClazz.getClassLoader(),
                    ORDER_SERVICE,
                    orderClazz,
                    null,
                    originalBytes
            );
            System.out.println("Result: " + (result == null ? "NULL" : "len=" + result.length));
            if (result != null) {
                System.out.println("Original: " + originalBytes.length + " bytes");
                System.out.println("Transformed: " + result.length + " bytes (diff=" + (result.length - originalBytes.length) + ")");
            }
        } catch (Exception e) {
            System.out.println("Invocation exception: " + e.getMessage());
            e.printStackTrace();
        }

        // 打印 transformer 内部状态
        Field trClass = SpyTransformer.class.getDeclaredField("transformedClasses");
        trClass.setAccessible(true);
        System.out.println("Transformed classes: " + trClass.get(transformer));

        Field methodRules = SpyTransformer.class.getDeclaredField("methodRules");
        methodRules.setAccessible(true);
        System.out.println("Method rules: " + methodRules.get(transformer));
    }
}

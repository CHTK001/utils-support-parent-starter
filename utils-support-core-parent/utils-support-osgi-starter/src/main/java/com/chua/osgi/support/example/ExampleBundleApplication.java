package com.chua.osgi.support.example;

import com.chua.common.support.osgi.BundleContext;
import com.chua.common.support.osgi.BundleApplication;
import com.chua.common.support.spi.annotations.Spi;

/**
 * OSGI Bundle 应用示例，演示如何在 Bundle 中注册服务。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("example-bundle")
public class ExampleBundleApplication implements BundleApplication {

    /** 服务名称 */
    private static final String serviceName = "example-service";

    @Override
    /** OnBundle开始 */
    public void onBundleStart(BundleContext context) {
        context.registerService(ExampleService.class, new ExampleServiceImpl());
        System.out.println("[ExampleBundle] Bundle started, registered service: " + serviceName);
    }

    @Override
    /** OnBundle停止 */
    public void onBundleStop(BundleContext context) {
        context.unregisterService(ExampleService.class, new ExampleServiceImpl());
        System.out.println("[ExampleBundle] Bundle stopped");
    }

    public interface ExampleService {
        String getName();
        String getMessage(String input);
    }

    public static class ExampleServiceImpl implements ExampleService {
        @Override
        /** 获取Name */
        public String getName() {
            return serviceName;
        }

        @Override
        /** 获取Message */
        public String getMessage(String input) {
            return "Hello from OSGi example: " + input;
        }
    }
}
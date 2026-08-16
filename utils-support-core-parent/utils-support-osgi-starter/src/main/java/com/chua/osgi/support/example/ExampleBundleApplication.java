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

    private static final String serviceName = "example-service";

    @Override
    public void onBundleStart(BundleContext context) {
        context.registerService(ExampleService.class, new ExampleServiceImpl());
        System.out.println("[ExampleBundle] Bundle started, registered service: " + serviceName);
    }

    @Override
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
        public String getName() {
            return serviceName;
        }

        @Override
        public String getMessage(String input) {
            return "Hello from OSGi example: " + input;
        }
    }
}
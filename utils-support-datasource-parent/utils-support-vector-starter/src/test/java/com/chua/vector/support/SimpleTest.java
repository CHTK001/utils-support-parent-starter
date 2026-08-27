package com.chua.vector.support;

public class SimpleTest {
    public static void main(String[] args) {
        System.out.println("step1: start");
        System.out.flush();
        try { Thread.sleep(100); } catch (Exception e) {}

        System.out.println("step2: check cuvs detector");
        System.out.flush();
        try {
            Class<?> cls = Class.forName("com.chua.common.support.vector.CuvsRuntimeDetector");
            Object det = cls.getDeclaredConstructor().newInstance();
            java.lang.reflect.Method m = cls.getMethod("isAvailable");
            Boolean available = (Boolean) m.invoke(det);
            System.out.println("step2 cuvs available=" + available);
        } catch (Exception e) {
            System.out.println("step2 error: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("step2 done");
        System.out.flush();
        try { Thread.sleep(100); } catch (Exception e) {}

        System.out.println("step3: check spi collect");
        System.out.flush();
        try {
            java.lang.reflect.Method of = Class.forName("com.chua.common.support.spi.ServiceProvider")
                    .getMethod("of", Class.class);
            Object provider = of.invoke(null, Class.forName("com.chua.common.support.vector.RuntimeDetector"));
            java.lang.reflect.Method collect = provider.getClass().getMethod("collect");
            java.util.List<?> list = (java.util.List<?>) collect.invoke(provider);
            System.out.println("step3 detected " + list.size() + " detectors");
            for (Object d : list) {
                System.out.println("  - " + d.getClass().getSimpleName());
                java.lang.reflect.Method name = d.getClass().getMethod("name");
                java.lang.reflect.Method prio = d.getClass().getMethod("priority");
                java.lang.reflect.Method avail = d.getClass().getMethod("isAvailable");
                System.out.println("    name=" + name.invoke(d) + " prio=" + prio.invoke(d) + " avail=" + avail.invoke(d));
            }
        } catch (Exception e) {
            System.out.println("step3 error: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("step3 done");
        System.out.flush();
        try { Thread.sleep(100); } catch (Exception e) {}

        System.out.println("step4: factory");
        System.out.flush();
        try {
            Class<?> factoryClass = Class.forName("com.chua.vector.support.spi.VectorStorageProviderFactory");
            Object factory = factoryClass.getDeclaredConstructor().newInstance();
            java.lang.reflect.Method name = factoryClass.getMethod("name");
            System.out.println("step4 factory name=" + name.invoke(factory));
        } catch (Exception e) {
            System.out.println("step4 error: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("step4 done");
        System.out.flush();

        System.out.println("all steps completed");
        System.out.println("exit 0");
        System.out.flush();
        System.exit(0);
    }
}

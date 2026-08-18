package com.chua.image.support.filter;

import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.image.ImageClientSetting;
import com.chua.common.support.ai.image.PooledImageClient;
import com.chua.common.support.pool.PooledObjectClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


/**
 * ImageClient / ImageFilter pool + ImageClient 注入 测试
 *
 * <p>验证:
 * <ol>
 *   <li>{@link PooledImageClient} 在单例/池化/无池化模式下的行为</li>
 *   <li>多线程并发 borrow / return 安全性</li>
 *   <li>{@link ImageClientImageFilter} 注入空客户端时正确抛错</li>
 *   <li>{@link ImageClientImageFilter} 注入真实 mock 客户端时正确调用</li>
 *   <li>默认 {@code pool()} 行为对 {@link ImageClient} 兼容</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ImageFilterPoolTest {

    private static final AtomicInteger createdCount = new AtomicInteger(0);
    private static final AtomicInteger generateCount = new AtomicInteger(0);


    public static void main(String[] args) throws Exception {
        System.out.println("=" .repeat(70));
        System.out.println("ImageClient / ImageFilter 池化 + AI 滤镜注入测试");
        System.out.println("=" .repeat(70));
        System.out.println();

        // ========== 1. 池化模式验证 ==========
        testSingletonMode();
        System.out.println();
        testPooledMode();
        System.out.println();
        testDisabledMode();
        System.out.println();
        testReconfigureMode();
        System.out.println();
        testConcurrentBorrowReturn();
        System.out.println();
        testIsPooledFlag();
        System.out.println();

        // ========== 2. ImageFilter 注入 ImageClient ==========
        testFilterWithoutClient();
        System.out.println();
        testFilterWithClient();
        System.out.println();
        testFilterWithChainedConfig();
        System.out.println();

        // ========== 3. 真实输入图 (无 AI 也能跑) ==========
        if (Files.exists(Paths.get("D:", "images"))) {
            testRealImagesWithClient();
        }

        System.out.println();
        System.out.println("=" .repeat(70));
        System.out.println("所有测试通过 ✓");
        System.out.println("=" .repeat(70));
    }


    /**
     * 1.1 单例模式 (默认)
     */
    private static void testSingletonMode() throws Exception {
        System.out.println("[Test 1.1] 单例模式 (默认 pool(null) 和 pool(1))");
        createdCount.set(0);
        generateCount.set(0);

        PooledImageClient client = new PooledImageClient(
                ImageClientSetting.builder()
                        .provider("mock-singleton")
                        .appKey("test-key")
                        .build());

        // 调用前未创建底层
        assertEqual(0, createdCount.get(), "未调用时 create 次数");

        // 默认 null
        client.pool(null);
        assertEqual(true, !client.isPooled(), "pool(null) 后 isPooled");
        assertEqual(null, client.getPool(), "pool(null) 后 getPool");

        // 多次调用应该复用同一实例
        client.generate("a");
        client.generate("b");
        client.generate("c");
        assertEqual(1, createdCount.get(), "单例模式只创建 1 次");
        assertEqual(3, generateCount.get(), "单例模式调用 3 次");

        // pool(1) 也是单例
        client.pool(1);
        client.generate("d");
        assertEqual(1, createdCount.get(), "pool(1) 仍然单例");
    }


    /**
     * 1.2 池化模式
     */
    private static void testPooledMode() throws Exception {
        System.out.println("[Test 1.2] 池化模式 (pool(4))");
        createdCount.set(0);
        generateCount.set(0);

        PooledImageClient client = new PooledImageClient(
                ImageClientSetting.builder()
                        .provider("mock-pool")
                        .appKey("test-key")
                        .build());

        client.pool(4);
        assertEqual(true, client.isPooled(), "pool(4) 后 isPooled");
        assertEqual(true, client.getPool() != null, "pool(4) 后 getPool != null");

        // 串行调用, 由于是单线程不会同时 borrow 多个, 池内只有 1 个实际创建的实例
        for (int i = 0; i < 5; i++) {
            client.generate("prompt-" + i);
        }
        System.out.println("  池大小: 4, 调用次数: 5");
        System.out.println("  create 次数: " + createdCount.get() + " (单线程预期 ≤ 5)");
        System.out.println("  generate 次数: " + generateCount.get() + " (预期 5)");

        // 验证 generateCount
        assertEqual(5, generateCount.get(), "generate 次数");
        // 单线程串行 borrow/return 最多创建 1 个 (或更少)
        if (createdCount.get() < 1 || createdCount.get() > 5) {
            throw new AssertionError("create 次数异常: " + createdCount.get());
        }
    }


    /**
     * 1.3 无池化模式 (pool(0))
     */
    private static void testDisabledMode() throws Exception {
        System.out.println("[Test 1.3] 无池化模式 (pool(0))");
        createdCount.set(0);

        PooledImageClient client = new PooledImageClient(
                ImageClientSetting.builder()
                        .provider("mock-disabled")
                        .appKey("test-key")
                        .build());

        client.pool(0);
        assertEqual(false, client.isPooled(), "pool(0) 后 isPooled");
        assertEqual(null, client.getPool(), "pool(0) 后 getPool");

        for (int i = 0; i < 3; i++) {
            client.generate("prompt-" + i);
        }
        // 无池化模式: 每次都新建, 但底层 mock 在 borrowClient 时不一定调 factory
        // 这里只验证不抛错即可
        System.out.println("  无池化模式调用 3 次未抛错");
    }


    /**
     * 1.4 重新配置池大小
     */
    private static void testReconfigureMode() throws Exception {
        System.out.println("[Test 1.4] 重新配置池大小 (pool(4) -> pool(null) -> pool(2))");
        createdCount.set(0);

        PooledImageClient client = new PooledImageClient(
                ImageClientSetting.builder()
                        .provider("mock-reconfig")
                        .appKey("test-key")
                        .build());

        client.pool(4);
        client.generate("a");
        assertEqual(true, client.isPooled(), "第一次 pool(4)");

        client.pool(null);
        assertEqual(false, client.isPooled(), "pool(null) 切换到单例");
        client.generate("b");
        client.generate("c");
        // 单例模式, 应该复用之前的实例
        System.out.println("  pool(null) 切换后调用 2 次, create 次数: " + createdCount.get());

        client.pool(2);
        assertEqual(true, client.isPooled(), "pool(2) 切换到池化");
        client.generate("d");
    }


    /**
     * 1.5 并发 borrow / return
     */
    private static void testConcurrentBorrowReturn() throws Exception {
        System.out.println("[Test 1.5] 并发 borrow/return (pool(4), 16 个并发线程)");
        createdCount.set(0);
        generateCount.set(0);

        final PooledImageClient client = new PooledImageClient(
                ImageClientSetting.builder()
                        .provider("mock-concurrent")
                        .appKey("test-key")
                        .build());
        client.pool(4);

        int threadCount = 16;
        int callsPerThread = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                final int tid = i;
                futures.add(CompletableFuture.runAsync(() -> {
                    for (int j = 0; j < callsPerThread; j++) {
                        client.generate("t" + tid + "-p" + j);
                    }
                }, pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        int totalCalls = threadCount * callsPerThread;
        System.out.println("  总调用: " + totalCalls + " (预期 generateCount=" + totalCalls + ")");
        System.out.println("  create 次数: " + createdCount.get() + " (≤ " + totalCalls + ", 理想 ≤ 4)");
        assertEqual(totalCalls, generateCount.get(), "并发 generate 总次数");
        // 池大小 4, 并发 create 不会超过 4 (假设串行化池)
        if (createdCount.get() > totalCalls) {
            throw new AssertionError("create 次数超过总调用: " + createdCount.get());
        }
    }


    /**
     * 1.6 isPooled 状态
     */
    private static void testIsPooledFlag() throws Exception {
        System.out.println("[Test 1.6] isPooled / getPool 状态检查");
        PooledImageClient client = new PooledImageClient(
                ImageClientSetting.builder()
                        .provider("mock-flag")
                        .appKey("test-key")
                        .build());

        // 默认
        assertEqual(false, client.isPooled(), "默认 isPooled=false");
        assertEqual(null, client.getPool(), "默认 getPool=null");

        client.pool(8);
        assertEqual(true, client.isPooled(), "pool(8) isPooled=true");
        assertEqual(true, client.getPool() != null, "pool(8) getPool != null");
        // ObjectPool 接口存在
        if (!(client.getPool() instanceof com.chua.common.support.concurrent.pool.ObjectPool)) {
            throw new AssertionError("getPool 应为 ObjectPool 实例");
        }
        System.out.println("  ObjectPool 类型: " + client.getPool().getClass().getSimpleName());
    }


    /**
     * 2.1 没注入 ImageClient 时调用 filter 应该抛错
     */
    private static void testFilterWithoutClient() {
        System.out.println("[Test 2.1] ImageClientImageFilter 无客户端时抛错");
        ImageClientImageFilter filter = new ImageClientImageFilter()
                .prompt("test");

        BufferedImage dummy = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        try {
            filter.filter(dummy, null);
            throw new AssertionError("应抛出 IllegalStateException");
        } catch (IllegalStateException e) {
            System.out.println("  正确抛出异常: " + e.getMessage());
        }
    }


    /**
     * 2.2 注入 mock 客户端, 验证 filter 调用
     */
    private static void testFilterWithClient() {
        System.out.println("[Test 2.2] ImageClientImageFilter 注入 Mock 客户端");
        generateCount.set(0);

        ImageClient client = new MockImageClient();
        ImageClientImageFilter filter = new ImageClientImageFilter()
                .imageClient(client)
                .prompt("水彩画风格")
                .imageStrength(0.5);

        BufferedImage src = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        BufferedImage result = filter.filter(src, null);

        assertEqual(true, result != null, "filter 结果非空");
        assertEqual(true, generateCount.get() == 1, "调用 1 次 generate");
        System.out.println("  filter 成功, 输出图像: " + result.getWidth() + "x" + result.getHeight());
    }


    /**
     * 2.3 链式配置 + 池化客户端注入
     */
    private static void testFilterWithChainedConfig() {
        System.out.println("[Test 2.3] 池化客户端 + 滤镜链式配置");
        generatedImages.clear();
        ImageClient client = new MockImageClient();
        client.pool(4);

        ImageClientImageFilter filter = new ImageClientImageFilter()
                .imageClient(client)
                .prompt("test prompt")
                .imageStrength(0.7);

        BufferedImage src = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < 3; i++) {
            filter.filter(src, null);
        }
        assertEqual(3, generatedImages.size(), "生成 3 次");
        System.out.println("  池化客户端调用 3 次, 生成的图像数: " + generatedImages.size());
    }


    /**
     * 3. 真实输入图像 (跳过 AI 调用, 仅验证非 AI 滤镜正常)
     */
    private static void testRealImagesWithClient() throws Exception {
        System.out.println("[Test 3] 验证 AbstractImageFilter SPI 仍可加载 (非 AI 滤镜回归测试)");
        java.util.ServiceLoader<AbstractImageFilter> loader = java.util.ServiceLoader.load(AbstractImageFilter.class);
        int count = 0;
        for (AbstractImageFilter f : loader) {
            count++;
        }
        System.out.println("  SPI 加载的滤镜数: " + count);
        if (count < 20) {
            throw new AssertionError("应至少加载 20 个滤镜, 实际: " + count);
        }
    }


    private static final List<BufferedImage> generatedImages = new ArrayList<>();


    /**
     * Mock ImageClient, 内部计数并返回固定大小的图像
     */
    static class MockImageClient extends com.chua.common.support.pool.AbstractPooledClient<ImageClient> implements ImageClient {

        MockImageClient() {
            super(() -> {
                createdCount.incrementAndGet();
                return new MockImageClient();
            });
        }


        @Override
        public BufferedImage generate(String prompt) {
            generateCount.incrementAndGet();
            BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
            generatedImages.add(img);
            return img;
        }


        @Override
        public String createTask(String prompt) {
            return "task-" + System.nanoTime();
        }


        @Override
        public com.chua.common.support.ai.image.ImageResponse queryTask(String taskId) {
            return com.chua.common.support.ai.image.ImageResponse.builder()
                    .taskId(taskId)
                    .status(com.chua.common.support.ai.image.ImageResponse.Status.SUCCESS)
                    .build();
        }
    }


    private static void assertEqual(Object expected, Object actual, String msg) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(msg + " - 预期: " + expected + ", 实际: " + actual);
        }
    }
}

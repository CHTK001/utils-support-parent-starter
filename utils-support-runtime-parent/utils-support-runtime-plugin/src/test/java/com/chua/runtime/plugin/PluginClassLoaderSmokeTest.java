package com.chua.runtime.plugin;

import com.chua.runtime.plugin.loader.PluginClassLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 插件类加载器冒烟测试。
 *
 * <p>遵循项目约定使用 {@code main} 方法直接运行（本模块无 JUnit 依赖）：</p>
 * <pre>
 * 运行方式：{@code java com.chua.runtime.plugin.PluginClassLoaderSmokeTest}
 * 任一校验失败输出 FAIL 并以退出码 1 结束，全部通过输出 PASS。
 * </pre>
 *
 * <p>重点是委派环的回归保护：旧实现的 {@code findClass} 在非 {@code com.chua.runtime.plugin}
 * 前缀上回手调用 {@code super.loadClass(name)}，而 {@code ClassLoader.loadClass} 最终又回调
 * {@code findClass}，父加载器也没有该类时必然 StackOverflowError。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PluginClassLoaderSmokeTest {

    /**
     * 失败计数
     */
    private static int failureCount = 0;
    /**
     * 成功计数
     */
    private static int passCount = 0;

    /**
     * main。
     * @param args 参数
     */
    public static void main(String[] args) throws Exception {
        // 用本测试类所在的输出目录充当插件根，避免运行期再编译样品类
        Path classesRoot = locateClassesRoot();
        PluginClassLoader loader = new PluginClassLoader("smoke", classesRoot,
                PluginClassLoaderSmokeTest.class.getClassLoader());

        try {
            Class<?> bootstrap = loader.loadClass("java.util.List");
            check(bootstrap.getClassLoader() == null, "bootstrap 类仍由启动加载器承担");
        } catch (Throwable t) {
            check(false, "bootstrap 类加载异常: " + t);
        }

        try {
            Class<?> fromParent = loader.loadClass("com.chua.common.support.utils.StringUtils");
            check(fromParent.getClassLoader() != loader, "插件目录没有的类委派给父加载器");
        } catch (Throwable t) {
            check(false, "父委派异常: " + t);
        }

        try {
            Class<?> own = loader.loadClass(PluginClassLoaderSmokeTest.class.getName());
            check(own.getClassLoader() == loader, "插件目录里的类由本加载器优先加载（child-first）");
        } catch (Throwable t) {
            check(false, "child-first 异常: " + t);
        }

        try {
            loader.loadClass("no.such.ClassXyz");
            check(false, "缺失类应抛 ClassNotFoundException");
        } catch (ClassNotFoundException e) {
            check(true, "缺失类抛 ClassNotFoundException");
        } catch (StackOverflowError e) {
            check(false, "缺失类触发委派环 StackOverflowError");
        } catch (Throwable t) {
            check(false, "缺失类抛出非预期类型: " + t.getClass().getName());
        }

        check(loader.getLoadedClasses().contains(PluginClassLoaderSmokeTest.class.getName()),
                "已加载类集合有记录");

        System.out.println("========================================");
        System.out.println("PluginClassLoaderSmokeTest 结果: PASS=" + passCount + ", FAIL=" + failureCount);
        if (failureCount > 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    /**
     * 定位本测试类所在的 classes 根目录
     *
     * @return 可作为插件根的目录
     */
    private static Path locateClassesRoot() throws Exception {
        Path location = Paths.get(PluginClassLoaderSmokeTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        if (Files.isDirectory(location)) {
            return location;
        }
        String absolute = location.toAbsolutePath().toString().replace('\\', '/');
        int idx = absolute.lastIndexOf("/com/chua/");
        if (idx < 0) {
            throw new IllegalStateException("无法定位 classes 根目录: " + absolute);
        }
        return Paths.get(absolute.substring(0, idx));
    }

    /**
     * 校验并计数
     *
     * @param condition 条件
     * @param message 消息
     */
    private static void check(boolean condition, String message) {
        if (condition) {
            passCount++;
            System.out.println("[PASS] " + message);
        } else {
            failureCount++;
            System.out.println("[FAIL] " + message);
        }
    }
}

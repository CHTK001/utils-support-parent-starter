package com.chua.common.support.datasearch.usage.spi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 解析器测试用的假主目录。
 *
 * <p>各 {@link UsageParser} 实现把数据根目录写成
 * {@code private static final Path = Path.of(System.getProperty("user.home"), ...)}，
 * 即类加载时就锁定路径，测试期改属性对已加载的解析器无效。因此本工具在<b>首次被引用时</b>
 * （早于任何解析器类加载）把 {@code user.home} 指向一个空临时目录，让解析器只看到测试自己
 * 铺下的夹具，读不到本机真实会话数据。</p>
 *
 * <p>用法：夹具测试在触碰任何解析器类之前先取一次 {@link #path()} 或 {@link #write}，
 * 顺序颠到会静默读到真实主目录。</p>
 *
 * @author CH
 * @since 4.0.0.44
 */
public final class UsageTestHome {

    /**
     * 假主目录；创建即清空，保证多次运行互不残留
     */
    private static final Path HOME = createHome();

    private UsageTestHome() {
    }

    /**
     * 假主目录，等价于解析器眼中的 {@code user.home}。
     *
     * @return 已写回系统属性的临时目录
     */
    public static Path path() {
        return HOME;
    }

    /**
     * 在假主目录下写一个文本夹具，父目录自动创建。
     *
     * @param relative 相对假主目录的路径，如 {@code .qoder/projects/demo/s.jsonl}
     * @param content  文件内容
     * @return 写入后的绝对路径
     */
    public static Path write(String relative, String content) {
        try {
            Path file = HOME.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("写入夹具失败: " + relative, e);
        }
    }

    /**
     * 创建（并清空）假主目录，同时把它登记为 {@code user.home}。
     *
     * @return 假主目录
     */
    private static Path createHome() {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "chua-usage-parser-home");
        try {
            deleteRecursively(dir);
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("初始化假主目录失败: " + dir, e);
        }
        System.setProperty("user.home", dir.toString());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteRecursively(dir)));
        return dir;
    }

    /**
     * 递归删除目录，失败时忽略（进程退出钩子里无法恢复）。
     *
     * @param dir 待删除目录
     */
    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 退出钩子里删不掉就交给系统清理临时目录
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}

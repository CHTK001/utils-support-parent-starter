package com.chua.deeplearning.support.sherpa;

import com.k2fsa.sherpa.onnx.LibraryLoader;
import com.k2fsa.sherpa.onnx.LibraryUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * sherpa-onnx 原生库加载器。
 *
 * <p>sherpa-onnx 的 Java API 由「JVM 声明类 + JNI 动态库」两部分组成：本模块通过 Maven
 * 依赖引入 {@code sherpa-onnx-jvm}，并按当前操作系统自动引入对应的
 * {@code sherpa-onnx-native-lib-*} 产物。原生库首次使用前必须完成加载，加载失败会抛
 * {@link UnsatisfiedLinkError}（错误信息对使用者并不友好）。</p>
 *
 * <p>本类负责把加载过程收敛到一次，并把失败原因翻译成可操作的提示。原生库查找顺序由
 * sherpa-onnx 官方 {@link LibraryUtils} 决定：</p>
 * <ol>
 *   <li>系统属性 {@code sherpa_onnx.native.path} 指定的目录（同时需含 onnxruntime）；</li>
 *   <li>jar 内 {@code sherpa-onnx/native/<os-arch>/} 资源（本模块按平台激活的 native 依赖即此形态）；</li>
 *   <li>{@code -Djava.library.path}。</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class SherpaNativeLoader {

    /**
     * sherpa-onnx 官方解压原生库时使用的临时目录前缀。
     */
    private static final String SHERPA_TEMP_PREFIX = "sherpa-onnx-java";

    /**
     * 是否已完成加载尝试。
     */
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    /**
     * 上一次加载失败的原因（成功后清空）。
     */
    private static volatile String failure;

    /**
     * 工具类禁止实例化。
     */
    private SherpaNativeLoader() {
    }

    /**
     * 确保 sherpa-onnx 原生库已加载；重复调用只会尝试一次。
     *
     * @throws SherpaException 原生库不可用，消息中包含平台信息与修复建议
     */
    public static void ensureLoaded() {
        if (INITIALIZED.get()) {
            if (failure != null) {
                throw new SherpaException(failure);
            }
            return;
        }
        synchronized (SherpaNativeLoader.class) {
            if (INITIALIZED.get()) {
                if (failure != null) {
                    throw new SherpaException(failure);
                }
                return;
            }
            try {
                loadThroughOfficialPath();
                failure = null;
            } catch (Throwable e) {
                failure = explain(e);
                throw new SherpaException(failure, e);
            } finally {
                INITIALIZED.set(true);
            }
        }
    }

    /**
     * 通过官方通道加载原生库，并关闭其后续自动加载。
     *
     * <p>官方 {@code LibraryLoader.maybeLoad()} 在每个识别器构造时都会执行：若 autoLoad 仍
     * 开启，本类提前加载之后它还会把同一个 native 库从 jar 再解压到新的临时目录并
     * {@code System.load} 一次（Windows 上等于同时映射两份 DLL）。因此这里先把 autoLoad
     * 关掉，再调用一次 {@link LibraryUtils#load()}，让加载动作本进程内只发生一次、且失败可捕获。</p>
     *
     * <p><b>顺带修复资源泄漏</b>：官方 {@code LibraryUtils.loadFromResourceInJar()} 每次调用都会
     * 把 {@code sherpa-onnx-jni} + {@code onnxruntime} 解压到一个新的
     * {@code sherpa-onnx-java<n>} 临时目录，且只依赖 {@code deleteOnExit}（进程退出才清）。
     * 因此进程内多次触发加载会持续堆积（实测累积 37 份、约 791MB，直到 C 盘写满）。
     * 这里在加载完成后立即删除「本次新产生的」临时目录：动态库一旦被 {@code System.load}
     * 映射进地址空间，删除源文件不影响已加载的映像（Windows 允许删除已映射的 DLL，
     * 只是目录项延迟释放），因此安全。</p>
     *
     * @throws Throwable 加载失败
     */
    private static void loadThroughOfficialPath() throws Throwable {
        Set<Path> before = listSherpaTempDirs();
        try {
            LibraryLoader.setAutoLoadEnabled(false);
            LibraryUtils.load();
        } finally {
            cleanupNewTempDirs(before);
        }
    }

    /**
     * 列出当前系统中已存在的 sherpa 解压临时目录。
     *
     * @return 目录集合
     */
    private static Set<Path> listSherpaTempDirs() {
        Set<Path> dirs = new HashSet<>();
        Path tmp = tempRoot();
        if (tmp == null) {
            return dirs;
        }
        try (Stream<Path> stream = Files.list(tmp)) {
            stream.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(SHERPA_TEMP_PREFIX))
                    .forEach(dirs::add);
        } catch (Exception e) {
            log.debug("[sherpa] 枚举临时目录失败: {}", e.getMessage());
        }
        return dirs;
    }

    /**
     * 删除本次加载新产生的临时目录（加载前已存在的不动，避免误删其它实例的）。
     *
     * @param before 加载前的目录集合
     */
    private static void cleanupNewTempDirs(Set<Path> before) {
        Path tmp = tempRoot();
        if (tmp == null) {
            return;
        }
        try (Stream<Path> stream = Files.list(tmp)) {
            stream.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(SHERPA_TEMP_PREFIX))
                    .filter(path -> !before.contains(path))
                    .forEach(SherpaNativeLoader::deleteRecursively);
        } catch (Exception e) {
            log.debug("[sherpa] 清理临时目录失败: {}", e.getMessage());
        }
    }

    /**
     * 获取临时目录根路径。
     *
     * @return 临时目录；不可用时返回 {@code null}
     */
    private static Path tempRoot() {
        try {
            return Path.of(System.getProperty("java.io.tmpdir", ""));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 递归删除目录（失败仅记录 debug，不影响主流程）。
     *
     * @param dir 目录
     */
    private static void deleteRecursively(Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 已映射的 DLL / 被占用的文件删不掉属正常，交给 deleteOnExit 兜底
                }
            });
        } catch (Exception ignored) {
            // 同上
        }
    }

    /**
     * 原生库是否可用（不抛异常，供能力探测使用）。
     *
     * @return true 表示已成功加载
     */
    public static boolean isAvailable() {
        try {
            ensureLoaded();
            return failure == null;
        } catch (SherpaException e) {
            return false;
        }
    }

    /**
     * 获取上一次加载失败的原因。
     *
     * @return 失败原因；已成功加载时返回 {@code null}
     */
    public static String failureReason() {
        return failure;
    }

    /**
     * 当前平台标识（与官方 native 产物目录一致，如 {@code win-x64}）。
     *
     * @return 平台标识
     */
    public static String platform() {
        String os = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "unknown").toLowerCase(Locale.ROOT);
        String osPart;
        if (os.contains("win")) {
            osPart = "win";
        } else if (os.contains("mac") || os.contains("darwin")) {
            osPart = "osx";
        } else if (os.contains("nux")) {
            osPart = "linux";
        } else {
            osPart = os;
        }
        String archPart;
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            archPart = "win".equals(osPart) ? "arm64" : "aarch64";
        } else if (arch.contains("64")) {
            archPart = "x64";
        } else {
            archPart = arch;
        }
        return osPart + "-" + archPart;
    }

    /**
     * 把底层异常翻译成带修复建议的说明文本。
     *
     * @param error 底层异常
     * @return 说明文本
     */
    private static String explain(Throwable error) {
        String platform = platform();
        StringBuilder sb = new StringBuilder();
        sb.append("sherpa-onnx 原生库加载失败（平台 ").append(platform).append("）: ")
                .append(error.getClass().getSimpleName());
        if (error.getMessage() != null) {
            sb.append(" - ").append(error.getMessage());
        }
        sb.append("。请确认：1) 已引入 sherpa-onnx-native-lib-").append(platform)
                .append(" 依赖（本模块按 os/arch 自动激活，跨平台打包请用 -Psherpa-native-all）；")
                .append("2) 或通过 -Dsherpa_onnx.native.path=<目录> 指定同时含 sherpa-onnx-jni 与 onnxruntime 的目录；")
                .append("3) 或通过 -Djava.library.path=<目录> 指定同一目录。");
        return sb.toString();
    }
}

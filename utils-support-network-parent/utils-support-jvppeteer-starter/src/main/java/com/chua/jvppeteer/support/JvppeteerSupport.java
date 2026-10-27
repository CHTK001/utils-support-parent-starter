package com.chua.jvppeteer.support;

import com.chua.common.support.utils.StringUtils;
import com.ruiyun.jvppeteer.api.core.Browser;
import com.ruiyun.jvppeteer.cdp.core.Puppeteer;
import com.ruiyun.jvppeteer.cdp.entities.LaunchOptions;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Jvppeteer 浏览器启动支持
 *
 * <p>统一处理三件事：</p>
 * <ul>
 *   <li>定位可用的 Chromium 内核浏览器（本机 Chrome / Edge / Playwright 缓存 / jvppeteer 缓存）；</li>
 *   <li>按统一的隐身参数启动浏览器，避免 {@code navigator.webdriver} 等自动化特征；</li>
 *   <li>静默关闭浏览器，忽略关闭阶段的异常。</li>
 * </ul>
 *
 * <p>查找顺序：显式路径 → 系统属性 {@code jvppeteer.executablePath} →
 * 环境变量 {@code CHROME_PATH} / {@code CHROME_BIN} / {@code INCEPTION_CHROME} →
 * 常见安装路径 → Playwright 缓存 → jvppeteer 缓存。
 * 全部找不到时返回 {@code null}，由 jvppeteer 自行下载（需要网络）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class JvppeteerSupport {

    /**
     * 系统属性：浏览器路径
     */
    public static final String PROPERTY_EXECUTABLE = "jvppeteer.executablePath";

    /**
     * 隐身启动参数
     */
    private static final String[] STEALTH_ARGS = {
            "--disable-blink-features=AutomationControlled",
            "--disable-dev-shm-usage",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-extensions",
            "--disable-features=IsolateOrigins,site-per-process,Translate,OptimizationHints",
            "--disable-site-isolation-trials",
            "--window-size=1280,900",
            "--lang=zh-CN"
    };

    /**
     * 工具类，禁止实例化
     */
    private JvppeteerSupport() {
    }

    /**
     * 以无头模式启动浏览器
     *
     * @return 浏览器实例
     */
    public static Browser launch() {
        return launch(true);
    }

    /**
     * 启动浏览器
     *
     * @param headless 是否无头
     * @return 浏览器实例
     */
    public static Browser launch(boolean headless) {
        return launch(headless, null);
    }

    /**
     * 启动浏览器
     *
     * @param headless       是否无头
     * @param executablePath 指定浏览器路径，可为空
     * @return 浏览器实例
     */
    public static Browser launch(boolean headless, String executablePath) {
        return launch(headless, executablePath, null, null);
    }

    /**
     * 启动浏览器（可追加参数与减速）
     *
     * @param headless       是否无头
     * @param executablePath 指定浏览器路径，可为空
     * @param extraArgs      追加启动参数，可为空
     * @param slowMo         每步操作减速毫秒，可为空
     * @return 浏览器实例
     */
    public static Browser launch(boolean headless, String executablePath,
                                 List<String> extraArgs, Integer slowMo) {
        String path = StringUtils.isNotBlank(executablePath) ? executablePath : locateExecutable();
        List<String> args = new ArrayList<>(List.of(STEALTH_ARGS));
        if (extraArgs != null) {
            args.addAll(extraArgs);
        }
        LaunchOptions.Builder builder = LaunchOptions.builder()
                .headless(headless)
                .args(args);
        if (StringUtils.isNotBlank(path)) {
            builder.executablePath(path);
            log.debug("[jvppeteer] 使用浏览器: {}", path);
        } else {
            log.warn("[jvppeteer] 未找到本机浏览器，将由 jvppeteer 自动下载（需要网络）");
        }
        if (slowMo != null && slowMo > 0) {
            builder.slowMo(slowMo);
        }
        try {
            return Puppeteer.launch(builder.build());
        } catch (IOException e) {
            throw new IllegalStateException("启动 jvppeteer 浏览器失败: " + e.getMessage(), e);
        }
    }

    /**
     * 关闭浏览器（忽略异常）
     *
     * @param browser 浏览器实例
     */
    public static void closeQuietly(Browser browser) {
        if (browser == null) {
            return;
        }
        try {
            browser.close();
        } catch (Exception e) {
            log.debug("[jvppeteer] 关闭浏览器失败: {}", e.getMessage());
        }
    }

    /**
     * 定位浏览器可执行文件
     *
     * @return 可执行文件路径，找不到返回 null
     */
    public static String locateExecutable() {
        String property = System.getProperty(PROPERTY_EXECUTABLE);
        if (isExecutable(property)) {
            return property;
        }
        for (String env : new String[]{"JVPPETEER_EXECUTABLE_PATH", "CHROME_PATH", "CHROME_BIN", "INCEPTION_CHROME"}) {
            String value = System.getenv(env);
            if (isExecutable(value)) {
                return value;
            }
        }
        for (String candidate : candidates()) {
            if (isExecutable(candidate)) {
                return candidate;
            }
        }
        String playwright = locatePlaywrightChromium();
        if (playwright != null) {
            return playwright;
        }
        return locateJvppeteerChromium();
    }

    /**
     * 判断路径是否可执行
     *
     * @param path 路径
     * @return true 表示可用
     */
    private static boolean isExecutable(String path) {
        return StringUtils.isNotBlank(path) && Files.isExecutable(Path.of(path));
    }

    /**
     * 常见安装路径
     *
     * @return 候选路径
     */
    private static List<String> candidates() {
        List<String> list = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase();
        String localAppData = System.getenv("LOCALAPPDATA");
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        String home = System.getProperty("user.home");

        if (os.contains("win")) {
            add(list, localAppData, "Google/Chrome/Application/chrome.exe");
            add(list, programFiles, "Google/Chrome/Application/chrome.exe");
            add(list, programFilesX86, "Google/Chrome/Application/chrome.exe");
            add(list, localAppData, "Chromium/Application/chrome.exe");
            add(list, localAppData, "Microsoft/Edge/Application/msedge.exe");
            add(list, programFiles, "Microsoft/Edge/Application/msedge.exe");
            add(list, programFilesX86, "Microsoft/Edge/Application/msedge.exe");
            add(list, home, ".cache/jvppeteer/chrome/win64-*/chrome-win64/chrome.exe");
        } else if (os.contains("mac")) {
            list.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            list.add("/Applications/Chromium.app/Contents/MacOS/Chromium");
            list.add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
        } else {
            list.add("/usr/bin/google-chrome");
            list.add("/usr/bin/google-chrome-stable");
            list.add("/usr/bin/chromium");
            list.add("/usr/bin/chromium-browser");
            list.add("/usr/bin/microsoft-edge");
        }
        return list;
    }

    /**
     * 追加候选路径（支持通配符段）
     *
     * @param list 候选列表
     * @param base 基础目录
     * @param rest 相对路径
     */
    private static void add(List<String> list, String base, String rest) {
        if (StringUtils.isBlank(base)) {
            return;
        }
        String combined = base + File.separator + rest.replace('/', File.separatorChar);
        if (!combined.contains("*")) {
            list.add(combined);
            return;
        }
        int star = combined.indexOf('*');
        int slash = combined.lastIndexOf(File.separatorChar, star);
        Path root = Path.of(combined.substring(0, slash));
        if (!Files.isDirectory(root)) {
            return;
        }
        String suffix = combined.substring(slash + 1).replace("*", "");
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                    .map(dir -> {
                        String name = dir.getFileName().toString();
                        return suffix.isEmpty() ? dir.toString() : dir.resolve(suffix).toString();
                    })
                    .sorted(Comparator.reverseOrder())
                    .forEach(list::add);
        } catch (IOException e) {
            log.debug("[jvppeteer] 列举目录失败 {}: {}", root, e.getMessage());
        }
    }

    /**
     * 从 Playwright 缓存定位 Chromium
     *
     * @return 可执行文件路径，找不到返回 null
     */
    private static String locatePlaywrightChromium() {
        String localAppData = System.getenv("LOCALAPPDATA");
        String home = System.getProperty("user.home");
        List<Path> roots = new ArrayList<>();
        if (StringUtils.isNotBlank(localAppData)) {
            roots.add(Path.of(localAppData, "ms-playwright"));
        }
        if (StringUtils.isNotBlank(home)) {
            roots.add(Path.of(home, ".cache", "ms-playwright"));
            roots.add(Path.of(home, "Library", "Caches", "ms-playwright"));
        }
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            List<Path> dirs;
            try (Stream<Path> stream = Files.list(root)) {
                dirs = stream.filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().startsWith("chromium-"))
                        .sorted(Comparator.comparingLong(JvppeteerSupport::revisionOf).reversed())
                        .toList();
            } catch (IOException e) {
                continue;
            }
            for (Path dir : dirs) {
                for (String relative : new String[]{"chrome-win64/chrome.exe", "chrome-win/chrome.exe",
                        "chrome-linux/chrome", "chrome-mac/Chromium.app/Contents/MacOS/Chromium"}) {
                    Path executable = dir.resolve(relative.replace('/', File.separatorChar));
                    if (Files.isExecutable(executable)) {
                        return executable.toString();
                    }
                }
            }
        }
        return null;
    }

    /**
     * 从 jvppeteer 自身缓存定位 Chromium
     *
     * @return 可执行文件路径，找不到返回 null
     */
    private static String locateJvppeteerChromium() {
        String home = System.getProperty("user.home");
        if (StringUtils.isBlank(home)) {
            return null;
        }
        Path root = Path.of(home, ".cache", "jvppeteer");
        if (!Files.isDirectory(root)) {
            return null;
        }
        List<Path> found = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, 6)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return "chrome.exe".equals(name) || "chrome".equals(name) || "Chromium".equals(name);
                    })
                    .forEach(path -> found.add(path));
        } catch (IOException e) {
            return null;
        }
        return found.isEmpty() ? null : found.getFirst().toString();
    }

    /**
     * 解析目录名里的版本号
     *
     * @param dir 目录
     * @return 版本号，解析失败返回 0
     */
    private static long revisionOf(Path dir) {
        String name = dir.getFileName().toString();
        int index = name.lastIndexOf('-');
        if (index < 0) {
            return 0L;
        }
        try {
            return Long.parseLong(name.substring(index + 1));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}

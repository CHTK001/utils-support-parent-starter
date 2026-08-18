package com.chua.filesearch.support.example;

import com.chua.common.support.utils.CommandLine;
import com.chua.filesearch.support.bridge.RustFileSearchBridge;
import com.chua.filesearch.support.model.FileInfo;
import com.chua.filesearch.support.model.FileSearchCriteria;
import com.chua.filesearch.support.service.FileSearchService;

import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文件搜索三类输出示例。
 *
 * <p>用法: 运行 main 方法，通过命令行参数配置扫描行为。</p>
 *
 * <h2>命令行选项</h2>
 * <ul>
 *   <li><code>--dir, -d &lt;path&gt;</code> — 扫描根目录（默认: 当前目录）</li>
 *   <li><code>--max, -m &lt;n&gt;</code> — 最大结果数，0 表示无限制（默认: 0）</li>
 *   <li><code>--top, -t &lt;n&gt;</code> — 列表与扩展名统计中显示的 Top N 数（默认: 50）</li>
 *   <li><code>--depth &lt;n&gt;</code> — 目录树显示深度（默认: 5）</li>
 *   <li><code>--help, -h</code> — 显示帮助信息</li>
 * </ul>
 *
 * <h2>三种输出模式</h2>
 * <ul>
 *   <li><b>WizTree 表</b> — 按扩展名分组统计，类似 WizTree 的扩展名视图</li>
 *   <li><b>树形缩进</b> — 递归展示目录树，子目录缩进显示</li>
 *   <li><b>简单列表</b> — 扁平文件名 + 大小列表</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FileSearchExample {

    /** 默认扫描根目录 */
    /** Default_root_dir */
    private static final String DEFAULT_ROOT_DIR = ".";

    /** 默认最大结果数（0 表示无限制） */
    /** Default_max_results */
    private static final int DEFAULT_MAX_RESULTS = 0;

    /** 默认 Top N 显示数 */
    /** Default_top_n */
    private static final int DEFAULT_TOP_N = 50;

    /** 默认目录树显示深度 */
    /** Default_tree_depth */
    private static final int DEFAULT_TREE_DEPTH = 5;

    /** 时间格式化 */
    /** Date_fmt */
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private FileSearchExample() {
    }

    public static void main(String[] args) {
        CommandLine cli = CommandLine.parse(args)
                .program("FileSearchExample")
                .register("dir", "d", "扫描根目录", DEFAULT_ROOT_DIR)
                .register("max", "m", "最大结果数（0 表示无限制）", String.valueOf(DEFAULT_MAX_RESULTS))
                .register("top", "t", "列表与扩展名统计的 Top N 数", String.valueOf(DEFAULT_TOP_N))
                .register("depth", "目录树显示深度", String.valueOf(DEFAULT_TREE_DEPTH))
                .register("help", "h", "显示帮助信息");

        if (cli.isHelp()) {
            cli.help();
            return;
        }

        String rootDir = cli.get("dir", DEFAULT_ROOT_DIR);
        int maxResults = cli.getInt("max", DEFAULT_MAX_RESULTS);
        int topN = cli.getInt("top", DEFAULT_TOP_N);
        int treeDepth = cli.getInt("depth", DEFAULT_TREE_DEPTH);

        System.out.println("=== Rust 文件搜索示例 ===\n");
        System.out.println("动态库版本: " + RustFileSearchBridge.getVersion());
        System.out.println("扫描目录: " + rootDir);
        System.out.println("最大结果数: " + (maxResults == 0 ? "无限制" : maxResults));
        System.out.println();

        long start = System.currentTimeMillis();

        FileSearchService service = FileSearchService.getInstance();
        if (!service.isAvailable()) {
            System.err.println("文件搜索服务不可用，退出。");
            return;
        }

        List<FileInfo> results = service.search(FileSearchCriteria.builder()
                .rootPath(rootDir)
                .maxResults(maxResults)
                .build());

        long elapsed = System.currentTimeMillis() - start;
        long fileCount = results.stream().filter(f -> !f.isDirectory()).count();
        long dirCount = results.stream().filter(FileInfo::isDirectory).count();

        System.out.printf("扫描完成: %d 文件, %d 目录, 用时 %d ms\n\n", fileCount, dirCount, elapsed);

        printWizTreeTable(results, topN);
        System.out.println();
        printTreeIndented(results, treeDepth);
        System.out.println();
        printSimpleList(results, topN);
    }

    /**
     * 输出 WizTree 风格扩展名统计表。
     *
     * @param results 文件信息列表
     * @param topN    显示的 Top N 扩展名数
     */
    static void printWizTreeTable(List<FileInfo> results, int topN) {
        System.out.println("=== WizTree 扩展名统计 ===");
        System.out.printf("%-12s %12s %12s %s\n", "扩展名", "文件数", "总大小", "占比");

        List<FileInfo> files = results.stream()
                .filter(f -> !f.isDirectory())
                .toList();
        long totalSize = files.stream().mapToLong(FileInfo::size).sum();
        long totalFiles = files.size();

        if (totalFiles == 0) {
            System.out.println("  (无文件)");
            return;
        }

        Map<String, List<FileInfo>> byExt = files.stream()
                .collect(Collectors.groupingBy(
                        f -> f.extension() != null && !f.extension().isEmpty()
                                ? f.extension() : "(无扩展名)"));

        byExt.entrySet().stream()
                .sorted((a, b) -> {
                    long sa = a.getValue().stream().mapToLong(FileInfo::size).sum();
                    long sb = b.getValue().stream().mapToLong(FileInfo::size).sum();
                    return Long.compare(sb, sa);
                })
                .limit(topN)
                .forEach(e -> {
                    String ext = e.getKey();
                    List<FileInfo> list = e.getValue();
                    long extSize = list.stream().mapToLong(FileInfo::size).sum();
                    long extCount = list.size();
                    double pct = totalSize > 0 ? extSize * 100.0 / totalSize : 0;
                    System.out.printf("%-12s %,12d %,12d  %5.1f%%\n",
                            ext, extCount, extSize, pct);
                });

        System.out.printf("%-12s %,12d %,12d\n", "(合计)", totalFiles, totalSize);
    }

    /**
     * 输出树形缩进目录结构（仅目录，前 N 层）。
     *
     * @param results 文件信息列表
     * @param depth   最大显示深度
     */
    static void printTreeIndented(List<FileInfo> results, int depth) {
        System.out.println("=== 目录树缩进（前 " + depth + " 层）===");

        List<FileInfo> dirs = results.stream()
                .filter(FileInfo::isDirectory)
                .sorted(Comparator.comparing(FileInfo::path))
                .toList();

        if (dirs.isEmpty()) {
            System.out.println("  (无目录)");
            return;
        }

        for (FileInfo dir : dirs) {
            String path = dir.path();
            int dirDepth = countSeparators(path);
            if (dirDepth > depth) {
                continue;
            }

            String indent = "  ".repeat(dirDepth);
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (name.isEmpty()) {
                name = path;
            }

            String sizeInfo = dir.fileCount() > 0 || dir.dirCount() > 0
                    ? String.format(" (%d 文件, %d 目录)",
                    dir.fileCount(), dir.dirCount())
                    : "";
            System.out.printf("%s📁 %s%s\n", indent, name, sizeInfo);
        }
    }

    /**
     * 输出简单扁平列表（Top N 个文件，按大小降序）。
     *
     * @param results 文件信息列表
     * @param topN    显示数量上限
     */
    static void printSimpleList(List<FileInfo> results, int topN) {
        System.out.println("=== 文件列表（前 " + topN + "，按大小降序）===");
        System.out.printf("%-12s %-60s %s\n", "大小", "路径", "修改时间");

        List<FileInfo> sorted = results.stream()
                .filter(f -> !f.isDirectory())
                .sorted((a, b) -> Long.compare(b.size(), a.size()))
                .limit(topN)
                .toList();

        if (sorted.isEmpty()) {
            System.out.println("  (无文件)");
            return;
        }

        for (FileInfo f : sorted) {
            String path = f.path().length() > 57
                    ? "..." + f.path().substring(f.path().length() - 57)
                    : f.path();
            String mtime = DATE_FMT.format(new Date(f.lastModified()));
            System.out.printf("%-12s %-60s %s\n",
                    formatSize(f.size()), path, mtime);
        }
    }

    private static int countSeparators(String path) {
        int count = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                count++;
            }
        }
        return count;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        if (bytes < 1024L * 1024 * 1024 * 1024) {
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }
        return String.format("%.1f TB", bytes / (1024.0 * 1024 * 1024 * 1024));
    }
}

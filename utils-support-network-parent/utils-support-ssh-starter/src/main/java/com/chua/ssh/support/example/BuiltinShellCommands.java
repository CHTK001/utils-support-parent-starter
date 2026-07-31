package com.chua.ssh.support.example;

import com.chua.ssh.support.annotations.ShellMethod;
import com.chua.ssh.support.server.SshCommandResponse;
import com.chua.ssh.support.server.SshMultiProgress;
import com.chua.ssh.support.server.SshProgress;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 内置 Shell 命令实现，包含常用的 Unix-like 命令。
 *
 * <p>该类通过 {@link ShellMethod} 注解声明命令名称和描述，
 * 由 {@link com.chua.ssh.support.server.SshServer} 自动扫描注册。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see com.chua.ssh.support.annotations.ShellMethod
 * @see com.chua.ssh.support.server.SshServer
 */
@ShellMethod("/builtin")
public class BuiltinShellCommands {

    /** 当前工作目录，每个线程独立的上下文 */
    private final ThreadLocal<Path> workingDirectory = ThreadLocal.withInitial(() ->
            Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize());

    // ==================== 文件系统命令 ====================

    /**
     * 打印当前工作目录。
     *
     * @param args 参数（不使用）
     * @return 当前工作目录的绝对路径
     */
    @ShellMethod(value = "pwd", description = "打印当前工作目录")
    @Nonnull
    public String pwd(@Nonnull String[] args) {
        return workingDirectory.get().toString();
    }

    /**
     * 列出目录内容。
     *
     * <p>支持选项：
     * <ul>
     *   <li>{@code -a} 显示隐藏文件</li>
     *   <li>{@code -l} 长格式显示</li>
     * </ul>
     *
     * @param args 参数，第一个为目录路径（可选），其余为选项
     * @return 目录内容列表
     */
    @ShellMethod(value = "ls", description = "列出目录内容")
    @Nonnull
    public String ls(@Nonnull String[] args) {
        boolean showAll = Arrays.stream(args).anyMatch("-a"::equals);
        boolean longFormat = Arrays.stream(args).anyMatch("-l"::equals);

        String[] pathArgs = Arrays.stream(args)
                .filter(a -> !"-a".equals(a) && !"-l".equals(a) && !"-la".equals(a) && !"-al".equals(a))
                .toArray(String[]::new);

        String target = pathArgs.length > 0 ? pathArgs[0] : ".";
        Path dir;
        if (Paths.get(target).isAbsolute()) {
            dir = Paths.get(target);
        } else {
            dir = workingDirectory.get().resolve(target).normalize();
        }

        File[] files = dir.toFile().listFiles();
        if (files == null) {
            return "ls: 无法访问目录: " + dir;
        }

        if (longFormat) {
            return Arrays.stream(files)
                    .filter(f -> showAll || !f.isHidden())
                    .map(f -> {
                        String perms = f.isDirectory() ? "d" : "-";
                        perms += f.canRead() ? "r" : "-";
                        perms += f.canWrite() ? "w" : "-";
                        perms += f.canExecute() ? "x" : "-";
                        return String.format("%s %8d %s", perms, f.length(), f.getName());
                    })
                    .collect(Collectors.joining(System.lineSeparator()));
        } else {
            return Arrays.stream(files)
                    .filter(f -> showAll || !f.isHidden())
                    .map(File::getName)
                    .collect(Collectors.joining("  ")) + System.lineSeparator();
        }
    }

    /**
     * 切换当前工作目录。
     *
     * <p>支持绝对路径和相对路径，支持 {@code ..} 和 {@code .} 跳转。</p>
     *
     * @param args 参数，第一个为目标目录
     * @return 空字符串（切换成功后无输出）
     */
    @ShellMethod(value = "cd", description = "切换当前工作目录")
    @Nonnull
    public String cd(@Nonnull String[] args) {
        if (args.length == 0) {
            return "";
        }

        String target = args[0];
        Path newDir;

        if (Paths.get(target).isAbsolute()) {
            newDir = Paths.get(target);
        } else {
            newDir = workingDirectory.get().resolve(target).normalize();
        }

        File dirFile = newDir.toFile();
        if (!dirFile.exists()) {
            return "cd: " + target + ": 没有那个文件或目录";
        }

        if (!dirFile.isDirectory()) {
            return "cd: " + target + ": 不是一个目录";
        }

        workingDirectory.set(newDir);
        return "";
    }

    /**
     * 读取文件内容并输出到标准输出。
     *
     * @param args 参数，第一个为文件路径
     * @return 文件内容
     */
    @ShellMethod(value = "cat", description = "读取文件内容")
    @Nonnull
    public String cat(@Nonnull String[] args) {
        if (args.length == 0) {
            return "cat: 缺少文件参数";
        }

        String target = args[0];
        Path file;
        if (Paths.get(target).isAbsolute()) {
            file = Paths.get(target);
        } else {
            file = workingDirectory.get().resolve(target).normalize();
        }

        if (!Files.exists(file)) {
            return "cat: " + target + ": 没有那个文件或目录";
        }

        if (!Files.isRegularFile(file)) {
            return "cat: " + target + ": 不是一个普通文件";
        }

        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "cat: " + target + ": 读取失败 - " + e.getMessage();
        }
    }

    /**
     * 创建目录。
     *
     * @param args 参数，一个或多个目录名
     * @return 创建结果
     */
    @ShellMethod(value = "mkdir", description = "创建目录")
    @Nonnull
    public String mkdir(@Nonnull String[] args) {
        if (args.length == 0) {
            return "mkdir: 缺少目录名";
        }

        StringBuilder result = new StringBuilder();
        for (String dirName : args) {
            Path newDir;
            if (Paths.get(dirName).isAbsolute()) {
                newDir = Paths.get(dirName);
            } else {
                newDir = workingDirectory.get().resolve(dirName).normalize();
            }

            try {
                Files.createDirectories(newDir);
                result.append("创建目录: ").append(newDir).append(System.lineSeparator());
            } catch (IOException e) {
                result.append("mkdir: 无法创建目录 '").append(dirName).append("': ")
                        .append(e.getMessage()).append(System.lineSeparator());
            }
        }
        return result.toString();
    }

    /**
     * 删除文件或目录。
     *
     * <p>递归删除目录及其所有内容。</p>
     *
     * @param args 参数，一个或多个路径
     * @return 删除结果
     */
    @ShellMethod(value = "rm", description = "删除文件或目录")
    @Nonnull
    public String rm(@Nonnull String[] args) {
        if (args.length == 0) {
            return "rm: 缺少目标";
        }

        StringBuilder result = new StringBuilder();
        for (String target : args) {
            Path file;
            if (Paths.get(target).isAbsolute()) {
                file = Paths.get(target);
            } else {
                file = workingDirectory.get().resolve(target).normalize();
            }

            try {
                Files.walk(file)
                        .sorted((a, b) -> -a.compareTo(b))
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException ignored) {
                            }
                        });
                result.append("已删除: ").append(target).append(System.lineSeparator());
            } catch (IOException e) {
                result.append("rm: ").append(target).append(": ").append(e.getMessage())
                        .append(System.lineSeparator());
            }
        }
        return result.toString();
    }

    /**
     * 复制文件或目录。
     *
     * @param args 参数，第一个为源文件，第二个为目标文件
     * @return 复制结果
     */
    @ShellMethod(value = "cp", description = "复制文件或目录")
    @Nonnull
    public String cp(@Nonnull String[] args) {
        if (args.length < 2) {
            return "cp: 用法: cp <源文件> <目标文件>";
        }

        String src = args[0];
        String dst = args[1];

        Path srcPath;
        Path dstPath;

        if (Paths.get(src).isAbsolute()) {
            srcPath = Paths.get(src);
        } else {
            srcPath = workingDirectory.get().resolve(src).normalize();
        }

        if (Paths.get(dst).isAbsolute()) {
            dstPath = Paths.get(dst);
        } else {
            dstPath = workingDirectory.get().resolve(dst).normalize();
        }

        if (!Files.exists(srcPath)) {
            return "cp: " + src + ": 没有那个文件或目录";
        }

        try {
            if (Files.isDirectory(srcPath)) {
                copyRecursive(srcPath, dstPath);
            } else {
                Files.copy(srcPath, dstPath);
            }
            return "已复制: " + src + " -> " + dst;
        } catch (IOException e) {
            return "cp: " + e.getMessage();
        }
    }

    private static void copyRecursive(Path source, Path target) throws IOException {
        Files.walk(source).forEach(src -> {
            try {
                Path dst = target.resolve(source.relativize(src)).normalize();
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.copy(src, dst);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 移动或重命名文件/目录。
     *
     * @param args 参数，第一个为源文件，第二个为目标文件
     * @return 移动结果
     */
    @ShellMethod(value = "mv", description = "移动或重命名文件/目录")
    @Nonnull
    public String mv(@Nonnull String[] args) {
        if (args.length < 2) {
            return "mv: 用法: mv <源文件> <目标文件>";
        }

        String src = args[0];
        String dst = args[1];

        Path srcPath;
        Path dstPath;

        if (Paths.get(src).isAbsolute()) {
            srcPath = Paths.get(src);
        } else {
            srcPath = workingDirectory.get().resolve(src).normalize();
        }

        if (Paths.get(dst).isAbsolute()) {
            dstPath = Paths.get(dst);
        } else {
            dstPath = workingDirectory.get().resolve(dst).normalize();
        }

        if (!Files.exists(srcPath)) {
            return "mv: " + src + ": 没有那个文件或目录";
        }

        try {
            Files.move(srcPath, dstPath);
            return "已移动: " + src + " -> " + dst;
        } catch (IOException e) {
            return "mv: " + e.getMessage();
        }
    }

    // ==================== 进度演示 ===================

    /**
     * 简单进度条演示（使用 {@link SshProgress} 工具类）。
     *
     * @param args 参数（不使用）
     * @param res  SSH 响应，用于流式写入
     * @throws InterruptedException 线程中断
     */
    @ShellMethod(value = "progress", description = "演示行内进度条")
    public void progress(@Nonnull String[] args, @Nonnull SshCommandResponse res) throws InterruptedException {
        try (SshProgress bar = new SshProgress(res, "下载", 100)) {
            for (int i = 0; i <= 100; i++) {
                bar.step();
                Thread.sleep(60);
            }
        }
    }

    /**
     * 多行进度演示（使用 {@link SshMultiProgress} 工具类）。
     *
     * @param args 参数（不使用）
     * @param res  SSH 响应，用于流式写入
     * @throws InterruptedException 线程中断
     */
    @ShellMethod(value = "multi-progress", description = "演示并排多任务进度条")
    public void multiProgress(@Nonnull String[] args, @Nonnull SshCommandResponse res) throws InterruptedException {
        SshMultiProgress mp = new SshMultiProgress(res, 50);
        mp.add("任务A", 100);
        mp.add("任务B", 200);
        mp.add("任务C", 50);

        for (int i = 0; i <= 100; i++) {
            mp.stepBy(0, 1);
            if (i % 2 == 0) {
                mp.stepBy(1, 1);
            }
            if (i % 5 == 0) {
                mp.stepBy(2, 1);
            }
            Thread.sleep(50);
        }
        mp.close();
    }

    // ==================== 系统命令 ====================

    /**
     * 输出文本到标准输出。
     *
     * @param args 参数，文本内容
     * @return 文本内容
     */
    @ShellMethod(value = "echo", description = "输出文本到标准输出")
    @Nonnull
    public String echo(@Nonnull String[] args) {
        return String.join(" ", args);
    }

    /**
     * 显示当前日期和时间。
     *
     * @param args 参数（不使用）
     * @return 当前日期时间字符串
     */
    @ShellMethod(value = "date", description = "显示当前日期和时间")
    @Nonnull
    public String date(@Nonnull String[] args) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
        return LocalDateTime.now().format(fmt);
    }

    /**
     * 显示当前用户。
     *
     * @param args 参数（不使用）
     * @return 当前用户名
     */
    @ShellMethod(value = "whoami", description = "显示当前用户")
    @Nonnull
    public String whoami(@Nonnull String[] args) {
        return System.getProperty("user.name");
    }

    /**
     * 显示主机名。
     *
     * @param args 参数（不使用）
     * @return 主机名
     */
    @ShellMethod(value = "hostname", description = "显示主机名")
    @Nonnull
    public String hostname(@Nonnull String[] args) {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }

    /**
     * 显示当前工作目录的绝对路径（同 pwd）。
     *
     * @param args 参数（不使用）
     * @return 当前工作目录的绝对路径
     */
    @ShellMethod(value = "realpath", description = "显示绝对路径")
    @Nonnull
    public String realpath(@Nonnull String[] args) {
        return pwd(args);
    }
}

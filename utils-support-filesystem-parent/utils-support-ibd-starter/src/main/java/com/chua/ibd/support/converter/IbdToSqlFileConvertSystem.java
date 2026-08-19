package com.chua.ibd.support.converter;

import com.chua.common.support.file.converter.ConvertSetting;
import com.chua.common.support.file.converter.FileConvertSystem;
import com.chua.common.support.file.converter.FileSource;
import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * IBD 到 SQL 转换器。
 *
 * <p>调用 Python ibd2sql 工具解析 MySQL InnoDB 数据文件 (.ibd) 并生成 SQL 脚本。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("ibd2sql")
public class IbdToSqlFileConvertSystem implements FileConvertSystem {

    /**
     * 源文件格式
     */
    private static final String SOURCE_TYPE = "ibd";

    /**
     * 目标文件格式
     */
    private static final String TARGET_TYPE = "sql";

    /**
     * 命令执行超时时间（秒）
     */
    private static final long COMMAND_TIMEOUT_SECONDS = 300L;

    @Override
    /** 是否Supported */
    public boolean isSupported(String sourceType, String targetType) {
        if (!SOURCE_TYPE.equals(sourceType)) {
            return false;
        }
        return TARGET_TYPE.equals(targetType);
    }

    @Override
    /** 转换 */
    public void convert(FileSource source, FileSource target, ConvertSetting setting) {
        try {
            File ibdFile = toFile(source);
            String sql = executeIbd2Sql(ibdFile);
            if (target.isPath()) {
                Files.writeString(Path.of(target.getPath()), sql);
            } else {
                target.getOutputStream().write(sql.getBytes());
            }
        } catch (Exception e) {
            throw new RuntimeException("IBD 转 SQL 失败", e);
        }
    }

    /**
     * 执行外部 Python ibd2sql 命令以生成 SQL 内容
     *
     * @param ibdFile 待转换的 IBD 文件对象
     * @return 生成的 SQL 字符串内容
     */
    private String executeIbd2Sql(File ibdFile) throws Exception {
        String python = findPython();
        String command = python + " -m ibd2sql \"" + ibdFile.getAbsolutePath() + "\" --ddl --sql";
        CmdResult result = CmdExecutors.execute(command, COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!result.isSuccess()) {
            throw new IOException("ibd2sql 执行失败, exit=" + result.getExitCode()
                    + ", error: " + result.getStderr());
        }
        return result.getStdout();
    }

    /**
     * 将 FileSource 转换为本地 File 对象
     *
     * <p>如果源是路径则直接返回，如果是输入流则先复制到临时文件再返回。</p>
     *
     * @param src 源文件源
     * @return 对应的 File 对象
     */
    private File toFile(FileSource src) throws IOException {
        if (src.isPath()) {
            return new File(src.getPath());
        }
        Path tmp = Files.createTempFile("ibd_", "." + SOURCE_TYPE);
        Files.copy(src.getInputStream(), tmp);
        return tmp.toFile();
    }

    /**
     * 在系统中查找可用的 Python 可执行文件
     *
     * <p>根据操作系统类型尝试不同的命令名称（python、python3、py）。</p>
     *
     * @return 找到的 Python 命令名称
     */
    private static String findPython() {
        String osName = System.getProperty("os.name").toLowerCase();
        String[] candidates = osName.contains("win")
                ? new String[]{"python", "python3", "py"}
                : new String[]{"python3", "python"};

        for (String cmd : candidates) {
            try {
                CmdResult result = CmdExecutors.execute(cmd + " --version", 5, TimeUnit.SECONDS);
                if (result.isSuccess()) {
                    return cmd;
                }
            } catch (Exception e) {
                log.warn("尝试 Python 命令失败: {}", cmd, e);
            }
        }
        return "python";
    }
}

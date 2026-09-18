package com.chua.wechat.support.restore;

import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.task.restore.DataRestoreConfig;
import com.chua.common.support.task.restore.DataRestoreResult;
import com.chua.common.support.task.restore.ExportFormat;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 微信数据还原的 Python 工具编排路径。
 *
 * <p>编排 <a href="https://github.com/minglin2012/Wechat-Export">Wechat-Export</a>
 * 开源工具（Python + Node.js + WCDB.dll）完成密钥捕获、WCDB 解密与消息导出，
 * 作为 FFM 原生路径不可用时（如 wcdb_init 环境校验拒绝）的兜底方案：</p>
 * <ul>
 *   <li>CSV / EXCEL — Wechat-Export 原生 csv / xlsx 导出</li>
 *   <li>SQL — 先导出 JSON 中间格式，再由 {@link WechatExportUtils#buildSqlScript} 转换为 SQL 脚本</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class WechatToolExporter {

    /**
    * 命令执行超时时间（秒），聊天记录导出可能耗时较长
    */
    private static final long COMMAND_TIMEOUT_SECONDS = 1800L;

    /**
    * SQL 导出默认表名
    */
    private static final String DEFAULT_TABLE_NAME = "wechat_message";

    /**
    * Wechat-Export 导出格式名：CSV
    */
    private static final String EXPORT_FORMAT_CSV = "csv";

    /**
    * Wechat-Export 导出格式名：XLSX
    */
    private static final String EXPORT_FORMAT_XLSX = "xlsx";

    /**
    * Wechat-Export 导出格式名：JSON（SQL 转换的中间格式）
    */
    private static final String EXPORT_FORMAT_JSON = "json";

    /**
    * 工具类禁止实例化。
    */
    private WechatToolExporter() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
    * 执行 Python 工具编排导出。
    *
    * @param source 源文件（用于定位数据目录与默认输出位置）
    * @param config 还原配置
    * @return 还原结果
    * @throws Exception 执行异常
    */
    public static DataRestoreResult export(File source, DataRestoreConfig config) throws Exception {
        ExportFormat format = config.getFormat();
        if (format == null) {
            format = ExportFormat.CSV;
        }

        // 解析 Wechat-Export 工具脚本与微信数据目录
        File toolScript = resolveToolScript(config);
        File toolDir = toolScript.getParentFile();
        File dataDir = resolveDataDir(source, config);
        File outputDir = config.getOutputDir() != null ? config.getOutputDir() : source.getParentFile();

        // 可选：自动捕获数据库密钥（需微信进程运行且管理员权限）
        if (Boolean.parseBoolean(String.valueOf(
                config.getOptions().getOrDefault(WechatDataRestore.OPTION_AUTO_KEY, Boolean.FALSE)))) {
            captureDatabaseKey(toolScript, toolDir);
        }

        switch (format) {
            case CSV:
                return doRestoreByExport(config, toolScript, toolDir, dataDir, outputDir, EXPORT_FORMAT_CSV);
            case EXCEL:
                return doRestoreByExport(config, toolScript, toolDir, dataDir, outputDir, EXPORT_FORMAT_XLSX);
            case SQL:
                return doRestoreSql(config, toolScript, toolDir, dataDir, outputDir);
            default:
                throw new UnsupportedOperationException("微信还原器不支持的输出格式: " + format);
        }
    }

    /**
    * 编排 Wechat-Export 原生导出（CSV / XLSX），收集生成的数据文件。
    *
    * @param config       还原配置
    * @param toolScript   export.py 脚本文件
    * @param toolDir      工具工作目录
    * @param dataDir      微信数据目录
    * @param outputDir    输出目录
    * @param exportFormat Wechat-Export 导出格式（csv / xlsx）
    * @return 还原结果
    * @throws Exception 执行异常
    */
    private static DataRestoreResult doRestoreByExport(DataRestoreConfig config, File toolScript, File toolDir,
                                                       File dataDir, File outputDir,
                                                       String exportFormat) throws Exception {
        long startStamp = System.currentTimeMillis();

        // 执行导出命令
        String[] command = buildExportCommand(toolScript, dataDir, outputDir, exportFormat, config);
        CmdResult result = CmdExecutors.execute(command, COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS, toolDir, null, null);
        if (!result.isSuccess()) {
            throw new RuntimeException("Wechat-Export 导出失败, exit=" + result.getExitCode()
                    + ", error: " + result.getStderr());
        }

        // 收集本次生成的数据文件
        List<File> outputFiles = WechatExportUtils.collectGeneratedFiles(outputDir, startStamp);
        if (outputFiles.isEmpty()) {
            return DataRestoreResult.failure("导出完成但未生成数据文件，请检查微信数据目录与会话过滤配置: "
                    + dataDir.getAbsolutePath());
        }

        long totalSize = outputFiles.stream().mapToLong(File::length).sum();
        log.info("微信数据还原为 {} 完成: {} 个文件", exportFormat, outputFiles.size());
        return DataRestoreResult.success(outputFiles, totalSize, 0);
    }

    /**
    * 还原为 SQL 脚本：先导出 JSON，再将消息转换为 CREATE TABLE + INSERT 语句。
    *
    * @param config     还原配置
    * @param toolScript export.py 脚本文件
    * @param toolDir    工具工作目录
    * @param dataDir    微信数据目录
    * @param outputDir  输出目录
    * @return 还原结果
    * @throws Exception 执行异常
    */
    private static DataRestoreResult doRestoreSql(DataRestoreConfig config, File toolScript, File toolDir,
                                                  File dataDir, File outputDir) throws Exception {
        long startStamp = System.currentTimeMillis();

        // 先导出 JSON 中间格式
        String[] command = buildExportCommand(toolScript, dataDir, outputDir, EXPORT_FORMAT_JSON, config);
        CmdResult result = CmdExecutors.execute(command, COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS, toolDir, null, null);
        if (!result.isSuccess()) {
            throw new RuntimeException("Wechat-Export 导出 JSON 失败, exit=" + result.getExitCode()
                    + ", error: " + result.getStderr());
        }

        // 收集 JSON 文件并解析为行数据
        List<File> jsonFiles = WechatExportUtils.collectGeneratedFiles(outputDir, startStamp);
        if (jsonFiles.isEmpty()) {
            return DataRestoreResult.failure("导出完成但未生成 JSON 文件，请检查微信数据目录与会话过滤配置: "
                    + dataDir.getAbsolutePath());
        }
        List<Map<String, Object>> rows = WechatExportUtils.parseJsonToRows(jsonFiles);
        if (rows.isEmpty()) {
            return DataRestoreResult.failure("JSON 文件解析后无消息数据: " + jsonFiles);
        }

        // 生成 SQL 脚本
        String tableName = config.getTargetTable();
        if (tableName == null || tableName.isBlank()) {
            tableName = DEFAULT_TABLE_NAME;
        }
        File sqlFile = new File(outputDir, tableName + ".sql");
        String sql = WechatExportUtils.buildSqlScript(rows, tableName,
                config.getTargetSchema(), config.isIncludeStructure());
        Files.writeString(sqlFile.toPath(), sql, Charset.forName(config.getCharset()));

        log.info("微信数据还原为 SQL 完成: {} ({} 行)", sqlFile.getName(), rows.size());
        return DataRestoreResult.success(List.of(sqlFile), sqlFile.length(), 0);
    }

    /**
    * 可选前置步骤：执行 Wechat-Export key 命令捕获数据库密钥。
    *
    * <p>密钥捕获通过 DLL 注入微信进程实现，需要管理员权限且微信处于登录状态。</p>
    *
    * @param toolScript export.py 脚本文件
    * @param toolDir    工具工作目录
    * @throws Exception 执行异常
    */
    private static void captureDatabaseKey(File toolScript, File toolDir) throws Exception {
        String[] command = {findPython(), toolScript.getAbsolutePath(), "key"};
        CmdResult result = CmdExecutors.execute(command, COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS, toolDir, null, null);
        if (!result.isSuccess()) {
            throw new RuntimeException("微信数据库密钥捕获失败（需管理员权限且微信已登录）, exit=" + result.getExitCode()
                    + ", error: " + result.getStderr());
        }
        log.info("微信数据库密钥捕获完成");
    }

    /**
    * 构建 Wechat-Export 导出命令（数组形式，避免引号转义问题）。
    *
    * @param toolScript   export.py 脚本文件
    * @param dataDir      微信数据目录
    * @param outputDir    输出目录
    * @param exportFormat 导出格式
    * @param config       还原配置
    * @return 命令参数数组
    */
    private static String[] buildExportCommand(File toolScript, File dataDir, File outputDir,
                                               String exportFormat, DataRestoreConfig config) {
        List<String> cmd = new ArrayList<>(16);
        cmd.add(findPython());
        cmd.add(toolScript.getAbsolutePath());
        cmd.add("export");
        cmd.add("--data-dir");
        cmd.add(dataDir.getAbsolutePath());
        cmd.add("-f");
        cmd.add(exportFormat);
        cmd.add("-o");
        cmd.add(outputDir.getAbsolutePath());

        // 可选参数：会话条数限制与黑白名单过滤
        Map<String, Object> options = config.getOptions();
        long limit = parseLongOption(options.get(WechatDataRestore.OPTION_LIMIT), 0L);
        if (limit > 0) {
            cmd.add("--limit");
            cmd.add(String.valueOf(limit));
        }
        String whitelist = parseStringOption(options.get(WechatDataRestore.OPTION_WHITELIST));
        if (!whitelist.isBlank()) {
            cmd.add("--whitelist");
            cmd.add(whitelist);
        }
        String blacklist = parseStringOption(options.get(WechatDataRestore.OPTION_BLACKLIST));
        if (!blacklist.isBlank()) {
            cmd.add("--blacklist");
            cmd.add(blacklist);
        }
        return cmd.toArray(new String[0]);
    }

    /**
    * 解析 Wechat-Export 工具脚本路径。
    *
    * <p>options 中的 {@code tool.path} 可以指向 export.py 文件本身，
    * 也可以指向工具根目录（自动查找其下的 export.py）。</p>
    *
    * @param config 还原配置
    * @return export.py 脚本文件
    */
    private static File resolveToolScript(DataRestoreConfig config) {
        Object toolPath = config.getOptions().get(WechatDataRestore.OPTION_TOOL_PATH);
        if (toolPath == null || String.valueOf(toolPath).isBlank()) {
            throw new IllegalArgumentException("缺少配置项 options['" + WechatDataRestore.OPTION_TOOL_PATH + "']，"
                    + "请指定 Wechat-Export 的 export.py 路径（参考 https://github.com/minglin2012/Wechat-Export），"
                    + "或改用 mode=native 的 FFM 原生路径（仅需配置 runtime.dir）");
        }
        File tool = new File(String.valueOf(toolPath));
        if (tool.isDirectory()) {
            tool = new File(tool, "export.py");
        }
        if (!tool.exists() || !tool.isFile()) {
            throw new IllegalArgumentException("Wechat-Export 脚本不存在: " + tool.getAbsolutePath());
        }
        return tool;
    }

    /**
    * 解析微信数据目录。
    *
    * <p>options 中的 {@code data.dir} 优先；缺省时源是目录就取它本身，
    * 源是文件才退到它所在目录。</p>
    *
    * @param source 源文件或源目录
    * @param config 还原配置
    * @return 微信数据目录
    */
    private static File resolveDataDir(File source, DataRestoreConfig config) {
        Object dataDirOption = config.getOptions().get(WechatDataRestore.OPTION_DATA_DIR);
        if (dataDirOption != null && !String.valueOf(dataDirOption).isBlank()) {
            File dataDir = new File(String.valueOf(dataDirOption));
            if (!dataDir.exists() || !dataDir.isDirectory()) {
                throw new IllegalArgumentException("微信数据目录不存在或不是目录: " + dataDir.getAbsolutePath());
            }
            return dataDir;
        }
        if (source.isDirectory()) {
            return source;
        }
        File parent = source.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new IllegalArgumentException("无法定位微信数据目录，请通过 options['"
                    + WechatDataRestore.OPTION_DATA_DIR + "'] 指定");
        }
        return parent;
    }

    /**
    * 解析 long 类型 options 值。
    *
    * @param value        原始值
    * @param defaultValue 解析失败时的默认值
    * @return long 值
    */
    private static long parseLongOption(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException e) {
                log.warn("options 数值解析失败，使用默认值 {}: {}", defaultValue, value);
            }
        }
        return defaultValue;
    }

    /**
    * 解析字符串类型 options 值。
    *
    * @param value 原始值
    * @return 字符串值，null 转为空串
    */
    private static String parseStringOption(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
    * 在系统中查找可用的 Python 可执行文件。
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
                log.warn("尝试 Python 命令失败: {}", cmd);
            }
        }
        return "python";
    }
}

package com.chua.ueba.support.training;

import com.chua.ueba.support.config.UebaConfig;
import com.chua.ueba.support.config.UebaConfigSerializer;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 训练管线。
 * <p>
 * 职责：将训练契约（同一份 {@code ueba-config.yaml} + 超参数）落盘，生成并（可选）
 * 执行调用 {@code train_ueba.py} 的 Python 命令。Python 脚本训练 auto编码器 与
 * LSTM/GRU+Attention，导出 ONNX 模型，并将归一化参数与类别词表回写到训练配置，
 * 随模型一起输出到 输出dir。调用方将回写后的配置复制回 {@code ueba-config.yaml}
 * 即可让推理端（{@code UebaEngine}）与训练端完全兼容。</p>
 *
 * <p>由 {@code Ueba.training().build()} 构造，勿直接 new。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class UebaTrainer {

    /** 内置 Python 训练脚本在 jar 内的资源路径 */
    private static final String TRAINING_SCRIPT_RESOURCE = "python/train_ueba.py";

    /** 超时时间上限（毫秒） */
    private static final long EXEC_TIMEOUT_MILLIS = TimeUnit.HOURS.toMillis(1L);

    /** 派生配置输出文件名 */
    private static final String DERIVED_CONFIG_FILE = "ueba-config-derived.yaml";

    /** 训练参数记录文件名 */
    private static final String TRAINING_PARAMS_FILE = "training-params.yaml";

    /** UEBA 配置 */
    private final UebaConfig config;

    /** 配置源文件（配置(路径) 时非 空） */
    private final Path configSource;

    /** 训练数据 CSV 路径 */
    private final Path dataCsv;

    /** 输出目录 */
    private final Path outputDir;

    /** 训练轮数 */
    private final int epochs;

    /** 批大小 */
    private final int batchSize;

    /** 学习率 */
    private final double learningRate;

    /** Python 解释器命令 */
    private final String pythonCommand;

    /** 续训来源目录（已有模型或 checkpoint），空 表示从零训练 */
    private final Path resumeDir;

    /**
     * 构造训练管线。
     *
     * @param config         UEBA 配置，不能为 空
     * @param configSource   配置源文件路径，允许为 空
     * @param dataCsv        训练数据 CSV 路径，不能为 空
     * @param outputDir      输出目录，不能为 空
     * @param epochs         训练轮数，必须大于 0
     * @param batchSize      批大小，必须大于 0
     * @param learningRate   学习率，必须大于 0
     * @param pythonCommand  Python 解释器，不能为 空 或空白
     * @param resumeDir      续训来源目录（已有模型/checkpoint），允许为 空（空 表示从零训练）
     * @throws IllegalArgumentException 当任一参数不合法时
     */
    public UebaTrainer(UebaConfig config, Path configSource, Path dataCsv, Path outputDir,
                       int epochs, int batchSize, double learningRate, String pythonCommand,
                       Path resumeDir) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(dataCsv, "dataCsv must not be null");
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        if (epochs <= 0) {
            throw new IllegalArgumentException("epochs 必须大于 0, 实际: " + epochs);
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 必须大于 0, 实际: " + batchSize);
        }
        if (learningRate <= 0.0d) {
            throw new IllegalArgumentException("learningRate 必须大于 0, 实际: " + learningRate);
        }
        if (pythonCommand == null || pythonCommand.isBlank()) {
            throw new IllegalArgumentException("pythonCommand 不能为 null 或空白");
        }
        this.config = config;
        this.configSource = configSource;
        this.dataCsv = dataCsv;
        this.outputDir = outputDir;
        this.epochs = epochs;
        this.batchSize = batchSize;
        this.learningRate = learningRate;
        this.pythonCommand = pythonCommand;
        this.resumeDir = resumeDir;
    }

    /**
     * 准备训练产物并返回可执行命令（不执行 Python）。
     *
     * @return 训练结果
     * @throws UncheckedIOException 当产物落盘失败时
     */
    public TrainingResult prepare() {
        try {
            Files.createDirectories(outputDir);
            Path configFile = materializeConfig();
            Path paramsFile = outputDir.resolve(TRAINING_PARAMS_FILE);
            writeParams(paramsFile);
            Path scriptFile = copyScript();
            String command = buildCommand(scriptFile, configFile);
            log.info("[UEBA-Trainer] 训练命令就绪: {}", command);
            return new TrainingResult(configFile, outputDir,
                    config.getAutoEncoder().getModelFile(),
                    config.getLstm().getModelFile(),
                    command);
        } catch (IOException e) {
            throw new UncheckedIOException("准备训练产物失败", e);
        }
    }

    /**
     * 执行训练（阻塞直至完成或超时）。
     *
     * @return 训练结果，包含已执行的命令
     * @throws IllegalStateException 当 Python 执行失败或超时时
     */
    public TrainingResult execute() {
        TrainingResult prepared = prepare();
        try {
            Process process = new ProcessBuilder(parseCommand(prepared.command()))
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder(1024);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                    log.info("[UEBA-Trainer] {}", line);
                }
            }
            if (!process.waitFor(EXEC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("训练超时（>" + EXEC_TIMEOUT_MILLIS + "ms）");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("训练失败 (exit=" + process.exitValue() + "):\n" + output);
            }
            log.info("[UEBA-Trainer] 训练完成, exit=0");
            return prepared;
        } catch (IOException e) {
            throw new IllegalStateException("启动训练进程失败，请确认 Python 与依赖已安装: "
                    + pythonCommand, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("训练被中断", e);
        }
    }

    /**
     * 将同一份配置（原样复制或序列化）写入输出目录。
     *
     * @return 落盘后的配置路径
     * @throws IOException 当写入失败时
     */
    private Path materializeConfig() throws IOException {
        Path target = outputDir.resolve(DERIVED_CONFIG_FILE);
        if (configSource != null && Files.isRegularFile(configSource)) {
            Files.copy(configSource, target, StandardCopyOption.REPLACE_EXISTING);
        } else {
            UebaConfigSerializer.write(target, config);
        }
        return target;
    }

    /**
     * 写入训练参数记录文件。
     *
     * @param paramsFile 目标文件
     * @throws IOException 当写入失败时
     */
    private void writeParams(Path paramsFile) throws IOException {
        String content = String.format(Locale.ROOT,
                "epochs: %d%nbatchSize: %d%nlearningRate: %s%ndataCsv: %s%n",
                epochs, batchSize, BigDecimal.valueOf(learningRate).toPlainString(), dataCsv);
        Files.writeString(paramsFile, content, StandardCharsets.UTF_8);
    }

    /**
     * 将内置训练脚本复制到输出目录。
     *
     * @return 脚本文件路径
     * @throws IOException 当复制失败时
     */
    private Path copyScript() throws IOException {
        Path scriptFile = outputDir.resolve("train_ueba.py");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(TRAINING_SCRIPT_RESOURCE)) {
            if (in == null) {
                throw new IOException("内置训练脚本缺失: " + TRAINING_SCRIPT_RESOURCE);
            }
            Files.copy(in, scriptFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return scriptFile;
    }

    /**
     * 组装 Python 训练命令。
     *
     * @param scriptFile 训练脚本路径
     * @param configFile 配置路径
     * @return 完整命令字符串
     */
    private String buildCommand(Path scriptFile, Path configFile) {
        List<String> parts = new ArrayList<>(12);
        parts.add(quote(pythonCommand));
        parts.add(quote(scriptFile.toString()));
        parts.add("--config=" + quote(configFile.toString()));
        parts.add("--data=" + quote(dataCsv.toString()));
        parts.add("--output=" + quote(outputDir.toString()));
        if (resumeDir != null) {
            parts.add("--resume=" + quote(resumeDir.toString()));
        }
        parts.add("--epochs=" + epochs);
        parts.add("--batch-size=" + batchSize);
        parts.add("--learning-rate=" + BigDecimal.valueOf(learningRate).toPlainString());
        return String.join(" ", parts);
    }

    /**
     * 将命令字符串按空白拆分为参数列表（简单处理，路径含空格需引号）。
     *
     * @param command 命令字符串
     * @return 参数列表
     */
    private static List<String> parseCommand(String command) {
        List<String> result = new ArrayList<>(8);
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == ' ' && !inQuote) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    /**
     * 若路径含空白则加双引号包裹。
     *
     * @param value 原始值
     * @return 包裹后的值
     */
    private static String quote(String value) {
        if (value.indexOf(' ') >= 0 || value.indexOf('\t') >= 0) {
            return "\"" + value + "\"";
        }
        return value;
    }
}

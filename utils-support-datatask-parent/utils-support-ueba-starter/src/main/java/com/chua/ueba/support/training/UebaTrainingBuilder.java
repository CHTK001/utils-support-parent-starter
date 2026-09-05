package com.chua.ueba.support.training;

import com.chua.ueba.support.config.UebaConfig;
import com.chua.ueba.support.dto.TrafficEvent;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

/**
 * 训练管线链式构建器。
 * <p>
 * 通过统一门面 {@code Ueba.training()} 获取，链式配置训练数据、输出目录、超参数与
 * Python 解释器，最终 {@link #build()} 产出 {@link UebaTrainer}。调用方可使用
 * {@code UebaTrainer#prepare()} 获取可执行的 Python 命令（dry-run），或
 * {@link UebaTrainer#execute()} 直接执行训练。示例：</p>
 * <pre>
 * TrainingResult result = Ueba.training()
 *         .configResource("ueba-config.yaml")
 *         .data("D:/data/access.csv")
 *         .outputDir("D:/models/ueba")
 *         .epochs(60)
 *         .build()
 *         .execute();
 * </pre>
 * <p>训练产物（ONNX 模型与回写后的配置）统一输出到 outputDir，用户可将其中的
 * 配置复制回 {@code ueba-config.yaml}，实现训练与识别完全兼容。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class UebaTrainingBuilder {

    /** classpath 默认配置文件 */
    private static final String DEFAULT_CONFIG_RESOURCE = "ueba-config.yaml";

    /** 默认输出目录（相对当前工作目录） */
    private static final Path DEFAULT_OUTPUT_DIR = Paths.get("target", "ueba-models");

    /** 默认训练轮数 */
    private static final int DEFAULT_EPOCHS = 50;

    /** 默认批大小 */
    private static final int DEFAULT_BATCH_SIZE = 64;

    /** 默认学习率 */
    private static final double DEFAULT_LEARNING_RATE = 1e-3;

    /** 默认 Python 解释器 */
    private static final String DEFAULT_PYTHON = "python";

    /** 已解析的配置 */
    private UebaConfig config;

    /** 配置来源文件路径（config(Path)/configFile 时非 null，复制时保留原始注释） */
    private Path configSource;

    /** 训练数据 CSV 路径 */
    private Path dataCsv;

    /** 输出目录 */
    private Path outputDir;

    /** 训练轮数 */
    private int epochs = DEFAULT_EPOCHS;

    /** 批大小 */
    private int batchSize = DEFAULT_BATCH_SIZE;

    /** 学习率 */
    private double learningRate = DEFAULT_LEARNING_RATE;

    /** Python 解释器命令 */
    private String pythonCommand = DEFAULT_PYTHON;

    /**
     * 以内存配置对象设置配置。
     *
     * @param config UEBA 配置，不能为 null
     * @return 当前构建器
     * @throws IllegalArgumentException 当 config 为 null 时
     */
    public UebaTrainingBuilder config(UebaConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        this.config = config;
        this.configSource = null;
        return this;
    }

    /**
     * 以文件路径设置配置（训练产物将复制该文件并保留注释）。
     *
     * @param path 配置文件路径，不能为 null
     * @return 当前构建器
     * @throws IllegalArgumentException 当 path 为 null 时
     * @throws UncheckedIOException     当配置文件读取失败时
     */
    public UebaTrainingBuilder config(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        this.config = UebaConfig.load(path);
        this.configSource = path;
        return this;
    }

    /**
     * 以文件路径字符串设置配置。
     *
     * @param file 配置文件路径，不能为 null 或空白
     * @return 当前构建器
     * @throws IllegalArgumentException 当 file 为 null 或空白时
     */
    public UebaTrainingBuilder configFile(String file) {
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("file 不能为 null 或空白");
        }
        return config(Paths.get(file));
    }

    /**
     * 以 classpath 资源设置配置。
     *
     * @param resource classpath 资源路径，不能为 null 或空白
     * @return 当前构建器
     * @throws IllegalArgumentException 当 resource 为 null/空白或资源不存在时
     */
    public UebaTrainingBuilder configResource(String resource) {
        if (resource == null || resource.isBlank()) {
            throw new IllegalArgumentException("resource 不能为 null 或空白");
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("classpath 资源不存在: " + resource);
            }
            this.config = UebaConfig.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("加载配置资源失败: " + resource, e);
        }
        this.configSource = null;
        return this;
    }

    /**
     * 设置训练数据 CSV 路径（列：timestamp,ip,path,method,status,user_agent,response_time,response_size）。
     *
     * @param dataCsv 训练数据路径，不能为 null
     * @return 当前构建器
     * @throws IllegalArgumentException 当 dataCsv 为 null 或文件不存在时
     */
    public UebaTrainingBuilder data(Path dataCsv) {
        Objects.requireNonNull(dataCsv, "dataCsv must not be null");
        if (!Files.isRegularFile(dataCsv)) {
            throw new IllegalArgumentException("训练数据文件不存在: " + dataCsv);
        }
        this.dataCsv = dataCsv;
        return this;
    }

    /**
     * 设置训练数据 CSV 路径。
     *
     * @param dataFile 训练数据文件路径，不能为 null 或空白
     * @return 当前构建器
     * @throws IllegalArgumentException 当 dataFile 为 null 或空白时
     */
    public UebaTrainingBuilder data(String dataFile) {
        if (dataFile == null || dataFile.isBlank()) {
            throw new IllegalArgumentException("dataFile 不能为 null 或空白");
        }
        return data(Paths.get(dataFile));
    }

    /**
     * 以内存事件列表设置训练数据，序列化为 CSV 后训练。
     *
     * @param events 流量事件列表，允许为空
     * @return 当前构建器
     */
    public UebaTrainingBuilder events(List<TrafficEvent> events) {
        List<TrafficEvent> safeEvents = events == null ? List.of() : events;
        this.dataCsv = serializeEvents(safeEvents);
        return this;
    }

    /**
     * 设置输出目录（模型与回写配置的落盘位置）。
     *
     * @param outputDir 输出目录，允许为 null（使用默认目录）
     * @return 当前构建器
     */
    public UebaTrainingBuilder outputDir(Path outputDir) {
        this.outputDir = outputDir;
        return this;
    }

    /**
     * 设置输出目录字符串。
     *
     * @param outputDir 输出目录，允许为 null 或空白
     * @return 当前构建器
     */
    public UebaTrainingBuilder outputDir(String outputDir) {
        if (outputDir == null || outputDir.isBlank()) {
            this.outputDir = null;
            return this;
        }
        return outputDir(Paths.get(outputDir));
    }

    /**
     * 设置训练轮数。
     *
     * @param epochs 训练轮数，必须大于 0
     * @return 当前构建器
     * @throws IllegalArgumentException 当 epochs 小于等于 0 时
     */
    public UebaTrainingBuilder epochs(int epochs) {
        if (epochs <= 0) {
            throw new IllegalArgumentException("epochs 必须大于 0, 实际: " + epochs);
        }
        this.epochs = epochs;
        return this;
    }

    /**
     * 设置批大小。
     *
     * @param batchSize 批大小，必须大于 0
     * @return 当前构建器
     * @throws IllegalArgumentException 当 batchSize 小于等于 0 时
     */
    public UebaTrainingBuilder batchSize(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 必须大于 0, 实际: " + batchSize);
        }
        this.batchSize = batchSize;
        return this;
    }

    /**
     * 设置学习率。
     *
     * @param learningRate 学习率，必须大于 0
     * @return 当前构建器
     * @throws IllegalArgumentException 当 learningRate 小于等于 0 时
     */
    public UebaTrainingBuilder learningRate(double learningRate) {
        if (learningRate <= 0.0d) {
            throw new IllegalArgumentException("learningRate 必须大于 0, 实际: " + learningRate);
        }
        this.learningRate = learningRate;
        return this;
    }

    /**
     * 设置 Python 解释器命令。
     *
     * @param pythonCommand Python 解释器，不能为 null 或空白
     * @return 当前构建器
     * @throws IllegalArgumentException 当 pythonCommand 为 null 或空白时
     */
    public UebaTrainingBuilder python(String pythonCommand) {
        if (pythonCommand == null || pythonCommand.isBlank()) {
            throw new IllegalArgumentException("pythonCommand 不能为 null 或空白");
        }
        this.pythonCommand = pythonCommand;
        return this;
    }

    /**
     * 构建训练管线。
     *
     * @return 训练管线，绝不为 null
     * @throws IllegalArgumentException 当配置未设置或训练数据未设置时
     */
    public UebaTrainer build() {
        if (config == null) {
            configResource(DEFAULT_CONFIG_RESOURCE);
        }
        if (dataCsv == null) {
            throw new IllegalArgumentException("必须先设置训练数据 data/events");
        }
        Path out = outputDir != null ? outputDir : DEFAULT_OUTPUT_DIR;
        return new UebaTrainer(config, configSource, dataCsv, out, epochs, batchSize, learningRate, pythonCommand);
    }

    /**
     * 将事件列表序列化为临时 CSV 文件。
     *
     * @param events 事件列表
     * @return CSV 文件路径
     */
    private static Path serializeEvents(List<TrafficEvent> events) {
        try {
            Path tmp = Files.createTempFile("ueba-train-", ".csv");
            tmp.toFile().deleteOnExit();
            StringBuilder sb = new StringBuilder(256);
            sb.append("timestamp,ip,path,method,status,user_agent,response_time,response_size").append('\n');
            for (TrafficEvent e : events) {
                sb.append(e.getTimestamp()).append(',');
                sb.append(csv(e.getIp())).append(',');
                sb.append(csv(e.getPath())).append(',');
                sb.append(csv(e.getMethod())).append(',');
                sb.append(e.getStatusCode()).append(',');
                sb.append(csv(e.getUserAgent())).append(',');
                sb.append(e.getResponseTimeMs()).append(',');
                sb.append(e.getResponseSizeBytes()).append('\n');
            }
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            return tmp;
        } catch (IOException e) {
            throw new UncheckedIOException("序列化训练数据失败", e);
        }
    }

    /**
     * CSV 字段转义（逗号/引号/换行）。
     *
     * @param value 字段值，允许为 null
     * @return 转义后的字段值
     */
    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
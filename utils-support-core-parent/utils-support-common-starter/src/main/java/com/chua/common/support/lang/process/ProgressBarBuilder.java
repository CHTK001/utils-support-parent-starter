package com.chua.common.support.lang.process;

import java.text.DecimalFormat;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* 进度条构建器，用于构建 {@link ProgressBar} 实例。
* <p>
* 提供流式 API 方便配置进度条的各项参数。
*
* @author CH
* @since 2024-01-01
* @version 1.0.0
 */
public class ProgressBarBuilder {

    /**
    * 任务名称
    */
    private String task = "";

    /**
    * 初始最大值，-1 表示未知
    */
    private long initialMax = -1;

    /**
    * 更新间隔（毫秒）
    */
    private int updateIntervalMillis = 1000;

    /**
    * 是否持续更新
    */
    private boolean continuousUpdate = false;

    /**
    * 进度条样式
    */
    private ProgressBarStyle style = ProgressBarStyle.COLORFUL_UNICODE_BLOCK;

    /**
    * 进度单位
    */
    private ProgressUnit unit = ProgressUnitType.ORIGINAL;

    /**
    * 进度条消费者
    */
    private ProgressBarConsumer consumer = null;

    /**
    * 完成后是否清除显示
    */
    private boolean clearDisplayOnFinish = false;

    /**
    * 单位名称
    */
    private String unitName = "B";

    /**
    * 单位大小
    */
    private long unitSize = 1;

    /**
    * 是否显示速度
    */
    private boolean showSpeed = true;

    /**
    * 是否隐藏预计剩余时间
    */
    private boolean hideEta = false;

    /**
    * 预计剩余时间计算函数
    */
    private Function<ProgressState, Optional<Duration>> eta = Util::linearEta;

    /**
    * 速度格式
    */
    private DecimalFormat speedFormat;

    /**
    * 速度单位
    */
    private ChronoUnit speedUnit = ChronoUnit.SECONDS;

    /**
    * 已处理数量
    */
    private long processed = 0;

    /**
    * 已消耗时间
    */
    private Duration elapsed = Duration.ZERO;

    /**
    * 最大渲染长度
    */
    private int maxRenderedLength = -1;

    /**
    * 进度条渲染器
    */
    private ProgressBarRenderer renderer = null;

    /**
    * 创建一个进度条构建器
    */
    public ProgressBarBuilder() {
    }

    /**
    * 创建一个进度条构建器
    *
    * @return 进度条构建器实例
    */
    public static ProgressBarBuilder builder() {
        return new ProgressBarBuilder();
    }

    /**
    * 创建一个进度条构建器
    *
    * @return 进度条构建器实例
    */
    public static ProgressBarBuilder newBuilder() {
        return new ProgressBarBuilder();
    }

    /**
    * 设置任务名称
    *
    * @param task 任务名称
    * @return 当前构建器实例
    */
    public ProgressBarBuilder setTaskName(String task) {
        this.task = task;
        return this;
    }

    /**
    * 设置进度单位
    *
    * @param unit 进度单位
    * @return 当前构建器实例
    */
    public ProgressBarBuilder setUnit(ProgressUnit unit) {
        this.unit = unit;
        return this;
    }

    /**
    * 检查是否已设置初始最大值
    *
    * @return true 表示已设置，false 表示未设置
    */
    boolean initialMaxIsSet() {
        return this.initialMax != -1;
    }

    /**
    * 设置初始最大值
    *
    * @param initialMax 最大进度值（-1 表示未知）
    * @return 当前构建器实例
    */
    public ProgressBarBuilder setInitialMax(long initialMax) {
        this.initialMax = initialMax;
        return this;
    }

    /**
    * 设置进度条样式
    *
    * @param style 进度条样式
    * @return 当前构建器实例
    */
    public ProgressBarBuilder setStyle(ProgressBarStyle style) {
        this.style = style;
        return this;
    }
    
    /**
    * 设置为 Python 下载风格
    * 使用 Python 风格的下载进度条渲染
    * 内部使用 PythonDownloadProgressBarRenderer
    */
    public ProgressBarBuilder setPythonDownloadStyle() {
        this.style = ProgressBarStyle.PYTHON_DOWNLOAD;
        this.renderer = PythonDownloadProgressBarRenderer.create(
                this.unit,
                this.unitName,
                this.unitSize,
                this.showSpeed,
                this.speedFormat,
                this.speedUnit,
                !this.hideEta,
                this.eta
        );
        return this;
    }
    
    /**
    * 设置为彩虹风格
    */
    public ProgressBarBuilder setRainbowStyle() {
        this.style = ProgressBarStyle.RAINBOW;
        this.renderer = GradientProgressBarRenderer.createRainbowStyle(
                unit, unitName, unitSize, showSpeed, speedFormat, speedUnit, !hideEta, eta
        );
        return this;
    }
    
    /**
    * 设置渐变色风格
    *
    * @param gradientType 渐变色类型
    */
    public ProgressBarBuilder setGradientStyle(GradientProgressBarRenderer.GradientType gradientType) {
        ProgressBarStyle gradientStyle;
        switch (gradientType) {
            case GRAY_TO_ORANGE:
                gradientStyle = ProgressBarStyle.PYTHON_DOWNLOAD;
                break;
            case GRAY_TO_GREEN:
                gradientStyle = ProgressBarStyle.GRAY_TO_GREEN;
                break;
            case BLUE_TO_CYAN:
                gradientStyle = ProgressBarStyle.BLUE_TO_CYAN;
                break;
            case RED_TO_YELLOW:
                gradientStyle = ProgressBarStyle.RED_TO_YELLOW;
                break;
            case RAINBOW:
                gradientStyle = ProgressBarStyle.RAINBOW;
                break;
            case MATRIX:
                gradientStyle = ProgressBarStyle.MATRIX;
                break;
            case FIRE:
                gradientStyle = ProgressBarStyle.FIRE;
                break;
            case OCEAN:
                gradientStyle = ProgressBarStyle.OCEAN;
                break;
            case NEON:
                gradientStyle = ProgressBarStyle.NEON;
                break;
            case SUNSET:
                gradientStyle = ProgressBarStyle.SUNSET;
                break;
            default:
                gradientStyle = ProgressBarStyle.PYTHON_DOWNLOAD;
        }
        
        this.style = gradientStyle;
        this.renderer = new GradientProgressBarRenderer(
                gradientStyle, unit, unitName, unitSize, showSpeed, speedFormat, speedUnit, !hideEta, eta, gradientType
        );
        return this;
    }
    
    /**
    * 设置为矩阵风格
    */
    public ProgressBarBuilder setMatrixStyle() {
        return setGradientStyle(GradientProgressBarRenderer.GradientType.MATRIX);
    }
    
    /**
    * 设置为火焰风格
    */
    public ProgressBarBuilder setFireStyle() {
        return setGradientStyle(GradientProgressBarRenderer.GradientType.FIRE);
    }
    
    /**
    * 设置为海洋风格
    */
    public ProgressBarBuilder setOceanStyle() {
        return setGradientStyle(GradientProgressBarRenderer.GradientType.OCEAN);
    }
    
    /**
    * 设置为霓虹风格
    */
    public ProgressBarBuilder setNeonStyle() {
        return setGradientStyle(GradientProgressBarRenderer.GradientType.NEON);
    }
    
    /**
    * 设置为日落风格
    */
    public ProgressBarBuilder setSunsetStyle() {
        return setGradientStyle(GradientProgressBarRenderer.GradientType.SUNSET);
    }

    /**
    * 设置为 Python 加载风格
    * 类似 Python alive_progress 库的风格
    * 使用 PythonLoadingProgressBarRenderer
    */
    public ProgressBarBuilder setPythonLoadingStyle() {
        this.style = ProgressBarStyle.PYTHON_LOADING;
        this.renderer = PythonLoadingProgressBarRenderer.create(
                this.unit,
                this.unitName,
                this.unitSize,
                this.showSpeed,
                this.speedFormat,
                this.speedUnit,
                !this.hideEta,
                this.eta
        );
        return this;
    }

    /**
    * 设置更新间隔（毫秒）
    *
    * @param updateIntervalMillis 更新间隔（毫秒）
    * @return 当前构建器实例
    */
    public ProgressBarBuilder setUpdateIntervalMillis(int updateIntervalMillis) {
        this.updateIntervalMillis = updateIntervalMillis;
        return this;
    }

    /**
    * 启用持续更新模式
    *
    * @return 当前构建器实例
    */
    public ProgressBarBuilder continuousUpdate() {
        this.continuousUpdate = true;
        return this;
    }

    /**
    * 设置进度条消费者
    *
    * @param consumer 进度条消费者
    * @return 当前构建器实例
    */
    public ProgressBarBuilder setConsumer(ProgressBarConsumer consumer) {
        this.consumer = consumer;
        return this;
    }

    /**
    * 完成后清除显示
    *
    * @return 当前构建器实例
    */
    public ProgressBarBuilder clearDisplayOnFinish() {
        this.clearDisplayOnFinish = true;
        return this;
    }

    /**
    * 设置单位名称和大小
    *
    * @param unitName 单位名称
    * @param unitSize 单位大小
    * @return 当前构建器实例
    */
    public ProgressBarBuilder setUnit(String unitName, long unitSize) {
        this.unitName = unitName;
        this.unitSize = unitSize;
        return this;
    }

    /**
    * 设置最大渲染长度
    *
    * @param maxRenderedLength 最大渲染长度
    * @return 当前构建器实例
    */
    public ProgressBarBuilder setMaxRenderedLength(int maxRenderedLength) {
        this.maxRenderedLength = maxRenderedLength;
        return this;
    }

    /**
    * 设置进度条渲染器
    *
    * @param renderer 进度条渲染器
    * @return 当前构建器实例
    */
    public ProgressBarBuilder setRenderer(ProgressBarRenderer renderer) {
        this.renderer = renderer;
        return this;
    }

    /**
    * 显示速度
    *
    * @return 当前构建器实例
    */
    public ProgressBarBuilder showSpeed() {
        return showSpeed(new DecimalFormat("#.0"));
    }

    /**
    * 显示速度并指定格式
    *
    * @param speedFormat 速度格式
    * @return 当前构建器实例
    */
    public ProgressBarBuilder showSpeed(DecimalFormat speedFormat) {
        this.showSpeed = true;
        this.speedFormat = speedFormat;
        return this;
    }

    /**
    * 隐藏预计剩余时间
    *
    * @return 当前构建器实例
    */
    public ProgressBarBuilder hideEta() {
        this.hideEta = true;
        return this;
    }

    /**
    * 设置预计剩余时间计算函数
    *
    * @param eta 预计剩余时间计算函数
    * @return 当前构建器实例
    */
    public ProgressBarBuilder setEtaFunction(Function<ProgressState, Optional<Duration>> eta) {
        this.hideEta = false;
        this.eta = eta;
        return this;
    }

    /**
    * 设置速度单位
    *
    * @param speedUnit 速度单位
    * @return 当前构建器实例
    */
    public ProgressBarBuilder setSpeedUnit(ChronoUnit speedUnit) {
        this.speedUnit = speedUnit;
        return this;
    }

    /**
    * 设置起始进度值
    * <p>
    * 从指定进度值和已消耗时间开始继续计数
    *
    * @param processed 已处理数量
    * @param elapsed 已消耗时间
    * @return 当前构建器实例
    */
    public ProgressBarBuilder startsFrom(long processed, Duration elapsed) {
        this.processed = processed;
        this.elapsed = elapsed;
        return this;
    }

    /**
    * 构建进度条实例
    *
    * @return 构建完成的 ProgressBar 实例
    */
    public ProgressBar build() {
        return new ProgressBar(
                task,
                initialMax,
                updateIntervalMillis,
                continuousUpdate,
                clearDisplayOnFinish,
                processed,
                elapsed,
                (renderer == null
                        ? new DefaultProgressBarRenderer(
                                style, unit, unitName, unitSize,
                        showSpeed, speedFormat, speedUnit,
                        !hideEta, eta)
                        : renderer
                ),
                (consumer == null
                        ? Util.createConsoleConsumer(maxRenderedLength)
                        : consumer
                )
        );
    }
}

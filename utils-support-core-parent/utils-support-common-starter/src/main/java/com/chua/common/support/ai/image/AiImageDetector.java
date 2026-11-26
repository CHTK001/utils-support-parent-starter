package com.chua.common.support.ai.image;

import com.chua.common.support.spi.ServiceProvider;

/**
 * 图片 AI 生成/编辑检测契约（门户）。
 *
 * <p>本接口是「判断一张图是否 AI 参与」的唯一入口，实现按 SPI 名称注册：</p>
 * <ul>
 *   <li>{@code heuristic}（默认，{@link HeuristicAiImageDetector}）：元数据 + 像素统计，
 *       纯 JDK，永远可用；</li>
 *   <li>模型实现（如 onnx 分类模型）：由对应引擎 starter 注册，模型未就绪时
 *       {@link #available()} 返回 {@code false}，{@link #detect(byte[])} 返回
 *       {@link AiImageDetectResult#unavailable} 而不是抛异常。</li>
 * </ul>
 *
 * <p><b>选实现的硬约束</b>：默认必须是启发式。模型实现不得标 {@code @SpiDefault}，
 * 也不得靠 SPI 优先级顶掉默认实现——否则宿主只要引入 onnx 依赖，判定口径就会
 * 在无人察觉的情况下从「确定性证据 + 统计」切到某个具体模型。</p>
 *
 * <p>上层只依赖本接口与 {@link AiImageDetectResult}，不感知引擎类型；要换引擎只改
 * SPI 名称，不改调用代码。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public interface AiImageDetector {

    /**
     * 默认实现名。
     */
    String DEFAULT_NAME = "heuristic";

    /**
     * 取默认实现（约定为启发式，永远可用）。
     *
     * <p>SPI 未扫描到任何实现时，直接实例化启发式实现兜底：检测能力不能因为
     * 索引缺失或类加载顺序而整体失效。</p>
     *
     * @return 默认检测实现，永不为 {@code null}
     */
    static AiImageDetector create() {
        AiImageDetector detector = ServiceProvider.of(AiImageDetector.class).getExtension("");
        return detector == null ? new HeuristicAiImageDetector() : detector;
    }

    /**
     * 按名称取实现。
     *
     * <p>名称为空或未注册时回落默认实现（SPI 侧已按此语义回落），
     * 实际生效的实现名以 {@link #name()} 为准。</p>
     *
     * @param name 实现名，如 {@code heuristic} / {@code onnx}
     * @return 检测实现，永不为 {@code null}
     */
    static AiImageDetector create(String name) {
        if (name == null || name.isBlank()) {
            return create();
        }
        AiImageDetector detector = ServiceProvider.of(AiImageDetector.class).getExtension(name);
        return detector == null ? create() : detector;
    }

    /**
     * 实现名。
     *
     * @return 实现名，如 heuristic / onnx
     */
    String name();

    /**
     * 当前实现是否可用。
     *
     * <p>模型实现需覆盖：模型文件缺失、标签文件缺失、运行时初始化失败时返回
     * {@code false}，供上层决定是否回落默认实现。</p>
     *
     * @return true 表示可用于判定
     */
    default boolean available() {
        return true;
    }

    /**
     * 检测图片是否 AI 生成/编辑。
     *
     * @param data 图片原始字节（JPEG / PNG / WebP）
     * @return 统一结论，永不为 {@code null}
     */
    AiImageDetectResult detect(byte[] data);
}

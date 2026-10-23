package com.chua.deeplearning.support.paper;

import com.chua.deeplearning.support.ocr.OcrResult;

import java.util.List;
import java.util.Map;

/**
 * 试卷识别客户端 —— 对外能力契约。
 *
 * <p>其它模块（eduai 等）只依赖本接口，不依赖任何具体实现：实现类由
 * spring-support-ai-starter 装配成 Spring Bean（从 {@code paper} 分组配置构建
 * {@link PaperPipeline}），运行期按类型注入。这样业务模块在编译期只认
 * utils-xx，替换或新增实现（本地引擎 / 远端服务）无需改动业务代码。</p>
 *
 * <p>契约中的全部类型均来自 utils-xx，保证接口可独立于任何框架使用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface PaperClient {

    /**
     * 试卷识别管线是否已装配可用。
     *
     * @return true 表示可以执行识别
     */
    boolean available();

    /**
     * 当前生效的识别引擎。
     *
     * @return {@code ocr} 或 {@code ocr+llm}
     */
    String engine();

    /**
     * 纯 OCR 识别：返回带坐标的文字块（实现需保证阅读顺序稳定）。
     *
     * @param imageData 图片字节
     * @return 文字块列表
     */
    List<OcrResult> recognizeDetail(byte[] imageData);

    /**
     * 试卷识别：按当前引擎返回结构化结果。
     *
     * <p>纯 OCR 引擎返回 {@code engine} + {@code blocks}；OCR+LLM 引擎返回
     * {@code engine} + {@code questions}，失败时可能附带 {@code blocks} 与 {@code fallback}。</p>
     *
     * @param imageData 图片字节
     * @return 识别结果
     */
    Map<String, Object> recognize(byte[] imageData);

    /**
     * 版面检测：返回按标签分组的检测框（标题/正文/表格等语义类别 + 坐标）。
     *
     * <p>供上层做整版叠放与区域计数；版面不可用时返回空 Map，不抛异常。</p>
     *
     * @param imageData 图片字节
     * @return 标签 → 检测框列表；不可用时为空 Map（不为 null）
     */
    Map<String, List<com.chua.deeplearning.support.model.PredictRectangle>> layoutRegions(byte[] imageData);
}

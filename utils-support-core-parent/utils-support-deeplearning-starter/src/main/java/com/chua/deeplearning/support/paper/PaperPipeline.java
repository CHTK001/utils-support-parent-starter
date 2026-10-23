package com.chua.deeplearning.support.paper;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.utils.StringUtils;
import com.chua.deeplearning.support.layout.LayoutDetector;
import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.ocr.OcrPipeline;
import com.chua.deeplearning.support.ocr.OcrResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 试卷识别管线 —— 统一能力门面，对一个扫描卷提供两条识别路径：
 *
 * <ul>
 *   <li><b>纯 OCR</b>（{@code engine=ocr}）：版面分析优先、带召回保护，输出逐题文字块；</li>
 *   <li><b>OCR + LLM 结构化</b>（{@code engine=ocr+llm}）：先 OCR 出文字块，再让大模型
 *       提取「题号 / 题干 / 学生作答 / 分值」的 JSON 数组。</li>
 * </ul>
 *
 * <p>组合复用已有的 {@link OcrPipeline}（检测→裁剪→矫正→修复→识别）与
 * {@link LayoutDetector}（文档版面），LLM 结构化经统一的 {@link ChatClient} 接口调用——
 * 与聊天链路同源，不在此处绑定任何厂商。</p>
 *
 * <p>纯能力层：不读任何配置、不依赖 Spring；引擎选择、模型实例、LLM 客户端由上方装配层
 * （spring-support-ai-starter 的 PaperClientManager）按 {@code paper} 分组配置注入。</p>
 *
 * <pre>{@code
 * PaperPipeline paper = PaperPipeline.builder()
 *         .layout(LayoutDetector.create("pp-doc-layout"))
 *         .ocr(OcrPipeline.builder().detector("paddleocrv6-det").recognizer("paddleocrv6-rec").build())
 *         .llm(chatClient)          // 可选：engine=ocr+llm 时用
 *         .engine("ocr")            // ocr / ocr+llm
 *         .build();
 *
 * List<OcrResult> blocks = paper.recognizeDetail(imageBytes);
 * Map<String, Object> result = paper.recognize(imageBytes);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PaperPipeline {

    /**
     * 引擎：纯 OCR
     */
    public static final String ENGINE_OCR = "ocr";

    /**
     * 引擎：OCR + LLM 结构化
     */
    public static final String ENGINE_OCR_LLM = "ocr+llm";

    /**
     * JSON 提取正则：从 LLM 输出里抽出最外层 JSON 数组/对象。
     */
    private static final Pattern JSON_BLOCK = Pattern.compile(
            "(\\{[\\s\\S]*\\}|\\[[\\s\\S]*\\])", Pattern.DOTALL);

    /**
     * 版面检测器（可空：缺失时退化为整图 OCR）
     */
    private final LayoutDetector layout;

    /**
     * OCR 管线（必填）
     */
    private final OcrPipeline ocr;

    /**
     * LLM 客户端（engine=ocr+llm 且结构化时才用；可空）
     */
    private final ChatClient llm;

    /**
     * 当前引擎
     */
    private final String engine;

    /**
     * 是否按版面合并题干与作答（按文本区坐标排序，影响纯 OCR 引擎的块顺序）
     */
    private final boolean mergeAnswer;

    /**
     * JSON 解析器
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 构造试卷识别管线。
     *
     * @param layout      版面检测器，可空
     * @param ocr         OCR 管线，不允许为 null
     * @param llm         LLM 客户端，可空（仅 ocr+llm 引擎使用）
     * @param engine      引擎（ocr / ocr+llm）
     * @param mergeAnswer 是否按版面合并题干与作答
     */
    public PaperPipeline(LayoutDetector layout, OcrPipeline ocr, ChatClient llm,
                         String engine, boolean mergeAnswer) {
        this.layout = layout;
        this.ocr = Objects.requireNonNull(ocr, "ocr");
        this.llm = llm;
        this.engine = StringUtils.isBlank(engine) ? ENGINE_OCR : engine;
        this.mergeAnswer = mergeAnswer;
    }

    /**
     * 链式构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 当前生效的引擎。
     *
     * @return ocr / ocr+llm
     */
    public String engine() {
        return engine;
    }

    /**
     * 版面检测器（可能为 null：缺失时退化为整图 OCR）。
     *
     * <p>供上层需要「按区域分组的版面框」（如前端原图叠放、区域计数）时取用；
     * 纯识别路径走 {@link #recognizeDetail(byte[])} 即可，无需自取。</p>
     *
     * @return LayoutDetector 实例；未装配时返回 null
     */
    public LayoutDetector layoutDetector() {
        return layout;
    }

    /**
     * OCR 管线（装配层注入的部件）。
     *
     * @return OcrPipeline 实例，不允许为 null
     */
    public OcrPipeline ocr() {
        return ocr;
    }

    /**
     * 试卷识别是否已配置可用（OCR 可用即可）。
     *
     * @return true 表示可用
     */
    public boolean available() {
        return ocr != null;
    }

    /**
     * 纯 OCR 识别：版面分析后按文本区逐区识别，返回带坐标的文字块。
     *
     * <p>带「版面召回保护」：版面模型的文本区召回直接决定最终能识别多少字。
     * 实测 rtdetr-layout 在扫描卷上可能只框出「姓名/学号/班级」3 个区域，
     * 按区域识别后标题、题干、页码全丢（10 块 → 3 块）。因此区域结果字符数
     * 明显少于整图时，回退整图识别，宁可慢一点也不能漏内容。</p>
     *
     * @param imageData 图片字节
     * @return 识别结果列表（按阅读顺序排序）
     */
    public List<OcrResult> recognizeDetail(byte[] imageData) {
        List<PredictRectangle> textRegions = textRegions(imageData);
        List<OcrResult> whole = ocr.recognizeDetail(imageData);
        if (textRegions.isEmpty()) {
            return sorted(whole);
        }
        List<OcrResult> byRegion = recognizeRegions(imageData, textRegions);
        long regionChars = chars(byRegion);
        long wholeChars = chars(whole);
        List<OcrResult> results;
        if (wholeChars > regionChars) {
            results = whole;
        } else {
            results = byRegion;
        }
        return sorted(results);
    }

    /**
     * 试卷识别：按当前引擎管线执行。
     *
     * <p>OCR：返回逐题文字块（{@code engine} + {@code blocks} 数组）。
     * OCR+LLM：返回大模型结构化的题目数组（{@code engine} + {@code questions}，
     * 失败时附 {@code blocks} 与 {@code fallback}）。</p>
     *
     * @param imageData 图片字节
     * @return 识别结果
     */
    public Map<String, Object> recognize(byte[] imageData) {
        if (ENGINE_OCR_LLM.equals(engine)) {
            return structureWithLlm(imageData);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", ENGINE_OCR);
        out.put("blocks", recognizeDetail(imageData));
        return out;
    }

    /**
     * 按版面文本区逐区识别（不包含召回保护）。
     *
     * @param imageData 图片字节
     * @param regions   版面文本区
     * @return 各区域识别结果
     */
    public List<OcrResult> recognizeRegions(byte[] imageData, List<PredictRectangle> regions) {
        if (regions == null || regions.isEmpty()) {
            return List.of();
        }
        List<OcrResult> out = new ArrayList<>();
        for (PredictRectangle region : regions) {
            byte[] crop = crop(imageData, region);
            if (crop == null) {
                continue;
            }
            try {
                out.addAll(ocr.recognizeDetail(crop));
            } catch (Exception e) {
                // 单区域识别失败不阻断其余区域
            }
        }
        return out;
    }

    /**
     * 提取需要 OCR 的文本类版面区域。
     *
     * @param imageData 图片字节
     * @return 文本区域列表；版面模型缺失或失败时返回空
     */
    public List<PredictRectangle> textRegions(byte[] imageData) {
        Map<String, List<PredictRectangle>> grouped = layoutRegions(imageData);
        if (grouped.isEmpty()) {
            return new ArrayList<>();
        }
        List<PredictRectangle> regions = new ArrayList<>();
        for (List<PredictRectangle> list : grouped.values()) {
            if (list != null) {
                regions.addAll(list);
            }
        }
        if (regions.isEmpty()) {
            return regions;
        }
        if (mergeAnswer) {
            regions.sort(Comparator.comparingDouble(PredictRectangle::y)
                    .thenComparingDouble(PredictRectangle::x));
        }
        return regions;
    }

    /**
     * 版面检测：返回<b>按标签分组</b>的检测框（标题/正文/表格/图片等语义类别 + 坐标）。
     *
     * <p>与 {@link #textRegions(byte[])} 的区别：本方法保留标签分组，供上层做
     * 「整版缩略图叠放」「各语义区域计数」等展示；{@code textRegions} 只做 OCR 输入，
     * 合并平铺并按阅读顺序排序。两者共用同一次推理结果语义，不重复绑定引擎。</p>
     *
     * <p>版面是增强能力：未装配版面模型或推理失败时返回空 Map，调用方无需捕获异常。</p>
     *
     * @param imageData 图片字节
     * @return 标签 → 检测框列表；不可用时为空 Map（不为 null）
     */
    public Map<String, List<PredictRectangle>> layoutRegions(byte[] imageData) {
        if (layout == null) {
            return new LinkedHashMap<>();
        }
        Map<String, List<PredictRectangle>> grouped;
        try {
            grouped = layout.detect(imageData);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
        if (grouped == null) {
            return new LinkedHashMap<>();
        }
        Map<String, List<PredictRectangle>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<PredictRectangle>> entry : grouped.entrySet()) {
            List<PredictRectangle> list = entry.getValue();
            copy.put(entry.getKey(), list == null ? List.of() : List.copyOf(list));
        }
        return copy;
    }

    /**
     * 获取试卷结构化的默认提示词。
     *
     * @return 提示词模板（{@code %s} 为文字块文本）
     */
    public String defaultPrompt() {
        return """
                你是一名试卷结构解析器。下面是扫描识别的文字块，请按题目出现顺序，
                把每一题的结构化信息提取出来，只输出 JSON 数组，禁止解释。

                每个元素字段：
                {"no":"题号，如 1.","stem":"题干（含选项 A./B./C./D.）","answer":"学生作答","maxScore":"分值(数字,没有给5)"}

                注意：
                1. 学生作答只取学生手写/填写的部分；题干、选项、分值标注、页眉水印（如"第N页"）不要混入 answer。
                2. 填空题空位用______表示；答案在题干后面时，答案与题干要分开。
                3. 判断题/选择题把括号里的符号或字母作为 answer（如 √ / C）。
                4. 简答/翻译题 answer 取学生实际书写内容，识别不出就留空字符串。

                文字块：
                %s
                """;
    }

    /**
     * OCR + LLM 结构化识别。
     *
     * <p>大模型不可用 / 返回空 / 返回非 JSON 时<b>不返回空结果</b>：
     * OCR 已经跑过一遍，把文字块一并带回（{@code blocks} + {@code fallback} 标记），
     * 调用方至少能拿到可判分的文本。</p>
     *
     * @param imageData 图片字节
     * @return 识别结果（engine + questions，必要时附 blocks 与 fallback）
     */
    private Map<String, Object> structureWithLlm(byte[] imageData) {
        List<OcrResult> blocks = recognizeDetail(imageData);
        String text = blocks.stream()
                .map(OcrResult::text)
                .filter(StringUtils::isNotBlank)
                .collect(java.util.stream.Collectors.joining("\n"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", ENGINE_OCR_LLM);
        if (StringUtils.isBlank(text)) {
            out.put("questions", List.of());
            out.put("fallback", "ocr-empty");
            return out;
        }
        String prompt = defaultPrompt().formatted(text);
        List<Map<String, Object>> questions = callLlm(prompt);
        out.put("questions", questions);
        if (questions.isEmpty()) {
            out.put("blocks", blocks);
            out.put("fallback", "llm-empty");
        }
        return out;
    }

    /**
     * 调用大模型做结构化解析。
     *
     * @param prompt 用户提示词
     * @return 结构化题目数组；解析失败返回空列表
     */
    private List<Map<String, Object>> callLlm(String prompt) {
        if (llm == null) {
            return List.of();
        }
        try {
            String raw = llm.chatSync(prompt);
            Matcher matcher = JSON_BLOCK.matcher(raw == null ? "" : raw);
            if (!matcher.find()) {
                return List.of();
            }
            JsonNode node = MAPPER.readTree(matcher.group(1));
            if (!node.isArray()) {
                node = node.path("questions");
            }
            List<Map<String, Object>> questions = new ArrayList<>();
            if (node.isArray()) {
                for (JsonNode item : node) {
                    Map<String, Object> q = new LinkedHashMap<>();
                    q.put("no", item.path("no").asText(""));
                    q.put("stem", item.path("stem").asText(""));
                    q.put("answer", item.path("answer").asText(""));
                    q.put("maxScore", item.path("maxScore").asInt(5));
                    questions.add(q);
                }
            }
            return questions;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 按阅读顺序排序（y 再 x）。
     *
     * @param results 原始结果
     * @return 排序后的不可变列表
     */
    private static List<OcrResult> sorted(List<OcrResult> results) {
        if (results == null || results.isEmpty()) {
            return results == null ? List.of() : results;
        }
        return results.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble((OcrResult o) ->
                                o.boundingBox() == null ? 0d : o.boundingBox().y())
                        .thenComparingDouble(o -> o.boundingBox() == null ? 0d : o.boundingBox().x()))
                .toList();
    }

    /**
     * 统计识别结果的有效字符数（忽略空白块），用于比较两条识别路径的完整度。
     *
     * @param results 识别结果
     * @return 有效字符数
     */
    private static long chars(List<OcrResult> results) {
        if (results == null || results.isEmpty()) {
            return 0L;
        }
        return results.stream()
                .filter(Objects::nonNull)
                .map(OcrResult::text)
                .filter(StringUtils::isNotBlank)
                .mapToLong(t -> t.replaceAll("\\s", "").length())
                .sum();
    }

    /**
     * 按区域裁剪图片。
     *
     * @param imageData 原图字节
     * @param rect      区域
     * @return 裁剪后的 PNG 字节；失败返回 null
     */
    private static byte[] crop(byte[] imageData, PredictRectangle rect) {
        try {
            return com.chua.deeplearning.support.utils.ImageCropUtils.crop(imageData,
                    (int) rect.x(), (int) rect.y(), (int) rect.width(), (int) rect.height());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 链式构建器。
     */
    public static final class Builder {

        /**
         * 版面检测器
         */
        private LayoutDetector layout;

        /**
         * OCR 管线
         */
        private OcrPipeline ocr;

        /**
         * LLM 客户端
         */
        private ChatClient llm;

        /**
         * 引擎
         */
        private String engine = ENGINE_OCR;

        /**
         * 是否按版面合并
         */
        private boolean mergeAnswer = true;

        /**
         * 设置版面检测器。
         *
         * @param layout 版面检测器，可空
         * @return this
         */
        public Builder layout(LayoutDetector layout) {
            this.layout = layout;
            return this;
        }

        /**
         * 设置 OCR 管线。
         *
         * @param ocr OCR 管线
         * @return this
         */
        public Builder ocr(OcrPipeline ocr) {
            this.ocr = ocr;
            return this;
        }

        /**
         * 设置 LLM 客户端（结构化引擎用）。
         *
         * @param llm LLM 客户端，可空
         * @return this
         */
        public Builder llm(ChatClient llm) {
            this.llm = llm;
            return this;
        }

        /**
         * 设置引擎。
         *
         * @param engine ocr / ocr+llm
         * @return this
         */
        public Builder engine(String engine) {
            this.engine = engine;
            return this;
        }

        /**
         * 设置是否按版面合并题干与作答。
         *
         * @param mergeAnswer true 合并
         * @return this
         */
        public Builder mergeAnswer(boolean mergeAnswer) {
            this.mergeAnswer = mergeAnswer;
            return this;
        }

        /**
         * 构建。
         *
         * @return PaperPipeline
         */
        public PaperPipeline build() {
            return new PaperPipeline(layout, ocr, llm, engine, mergeAnswer);
        }
    }
}
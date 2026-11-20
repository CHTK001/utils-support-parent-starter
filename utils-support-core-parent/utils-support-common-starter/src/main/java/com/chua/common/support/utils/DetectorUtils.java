package com.chua.common.support.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 内容检测工具类（纯统计启发式，不依赖 AI 模型）。
 *
 * <p>参考 MiniWebtool「AI内容检测器」的检测思路实现：先把文本拆分为句子，
 * 再从两个维度独立分析——</p>
 * <ol>
 *   <li><b>逐句特征标注</b>：统计 AI 常见套话（如「值得注意的是」「综上所述」）、
 *       套路化表达（如「发挥着至关重要的作用」）、整齐的多项并列、
 *       「不仅…而且…」式递进、「一方面…另一方面」式对仗、缺少具体细节的泛泛而谈、
 *       破折号偏好等 AI 迹象；以及第一人称、具体数字、口语化表达、语气词、
 *       英语缩略形式等人工写作迹象，每处标注均附判断依据；</li>
 *   <li><b>整篇写作指纹</b>：句子长度的变化幅度（人类写作长短交错，AI 文本往往较为平均，
 *       即「突发性」偏低）、AI 常见措辞密度、句首开头重复、连接词密度、AI 免责/提示语、
 *       第一人称表达密度等。</li>
 * </ol>
 *
 * <p>特征词库综合整理自公开资料：LLM 高频用词研究（如 delve / tapestry / pivotal 等
 * 在 ChatGPT 普及后的使用激增）、「not just X, but Y」式对比重构句式、中文 AI 高频词
 * 与互联网黑话（赋能 / 抓手 / 闭环等）、AI 助手常见的免责声明句式，以及机器生成文本
 * 检测综述中常用的困惑度代理与突发性特征。单个特征不定罪（真人也会用破折号和排比），
 * 评分按特征加权聚簇。</p>
 *
 * <p>最终输出 0–100 的 AI 生成可能性评分（按句子长度加权）、判定结果、置信度、
 * 逐句标注与正反两方面的依据。结果<b>仅供参考</b>，不能单独作为认定
 * 「内容由 AI 生成」的证据；对短文本、正式/学术写作、非母语写作以及
 * 经过大量编辑的 AI 文本，把握较小。</p>
 *
 * <h2>能力矩阵</h2>
 * <table border="1">
 *   <tr><th>能力</th><th>方法</th><th>说明</th></tr>
 *   <tr><td>完整检测报告</td><td>{@link #detect(String)}</td><td>评分 + 判定 + 置信度 + 逐句标注 + 依据 + 写作指纹</td></tr>
 *   <tr><td>仅取 AI 评分</td><td>{@link #detectScore(String)}</td><td>0–100，按句子长度加权</td></tr>
 *   <tr><td>仅取判定结果</td><td>{@link #detectVerdict(String)}</td><td>人工撰写 / 大部分为人工撰写 / 混合 / 可能为 AI 生成 / AI 生成</td></tr>
 *   <tr><td>快速判断</td><td>{@link #isLikelyAiGenerated(String)}</td><td>评分 ≥ 60 视为可能由 AI 生成</td></tr>
 *   <tr><td>评分 → 判定</td><td>{@link #verdictOf(int)}</td><td>五档判定口径</td></tr>
 *   <tr><td>句子评分 → 标注</td><td>{@link #labelOf(int)}</td><td>AI 生成 / 可能有 AI 参与 / 人工撰写</td></tr>
 * </table>
 *
 * <h2>判定分档（与参考工具一致）</h2>
 * <table border="1">
 *   <tr><th>AI 生成可能性</th><th>判定结果</th><th>通常代表什么</th></tr>
 *   <tr><td>0–19%</td><td>人工撰写</td><td>几乎没有明显的 AI 写作迹象</td></tr>
 *   <tr><td>20–39%</td><td>大部分为人工撰写</td><td>主要由人撰写，部分段落可能带 AI 特征或经 AI 润色</td></tr>
 *   <tr><td>40–59%</td><td>混合：人工与 AI</td><td>部分内容可能由 AI 生成或改写，请查看具体标注</td></tr>
 *   <tr><td>60–79%</td><td>可能为 AI 生成</td><td>文本大部分读起来像 AI 输出，也可能经过少量编辑</td></tr>
 *   <tr><td>80–100%</td><td>AI 生成</td><td>文本几乎完全像未经编辑的 AI 输出</td></tr>
 * </table>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * DetectionResult result = DetectorUtils.detect("值得注意的是……综上所述……");
 * System.out.println(result.aiProbability() + "% " + result.verdict() + "（置信度：" + result.confidence() + "）");
 * result.sentences().forEach(s -> System.out.println(s.score() + "\t" + s.label() + "\t" + s.text()));
 * }</pre>
 *
 * <h2>线程安全</h2>
 * <p>所有方法均为静态方法且无共享可变状态，线程安全。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public final class DetectorUtils {

    /**
     * 结果可靠所需的最低字符数（低于该值置信度为「低」）
     */
    public static final int MIN_RELIABLE_CHARS = 70;

    /**
     * 结果通常更可靠所需的字符数（达到该值前置信度最高为「中」）
     */
    public static final int MORE_RELIABLE_CHARS = 260;

    /**
     * 参考工具单次检测的字符上限，调用方可参考它预先截断超长文本
     */
    public static final int SUGGESTED_MAX_CHARS = 4200;

    /**
     * 「可能由 AI 生成」的评分下限
     */
    public static final int LIKELY_AI_THRESHOLD = 60;

    /**
     * 高置信度所需的最低句子数
     */
    private static final int SENTENCES_FOR_HIGH_CONFIDENCE = 5;

    /**
     * 判定分档边界
     */
    private static final int BAND_HUMAN_MAX = 19;
    private static final int BAND_MOSTLY_HUMAN_MAX = 39;
    private static final int BAND_MIXED_MAX = 59;
    private static final int BAND_LIKELY_AI_MAX = 79;

    /**
     * 句子级标注阈值：≥ 65 标记为 AI 生成，40–64 为可能有 AI 参与，其余为人工撰写
     */
    private static final int SENTENCE_AI_LABEL = 65;
    private static final int SENTENCE_MAYBE_LABEL = 40;

    /**
     * 句子评分基准与各特征权重
     */
    private static final int BASE_SCORE = 30;
    private static final int WEIGHT_CLICHE = 16;
    private static final int WEIGHT_FORMULAIC = 14;
    private static final int WEIGHT_PARALLELISM = 10;
    private static final int WEIGHT_BALANCED = 8;
    private static final int WEIGHT_VAGUE = 8;
    private static final int WEIGHT_DASH = 4;
    private static final int WEIGHT_BUZZWORD = 8;
    private static final int WEIGHT_DISCLAIMER = 10;
    private static final int REDUCTION_FIRST_PERSON = 14;
    private static final int REDUCTION_CONTRACTION = 12;
    private static final int REDUCTION_COLLOQUIAL = 10;
    private static final int REDUCTION_INTERJECTION = 15;
    private static final int REDUCTION_NUMBER = 10;

    /**
     * 单句加分/减分上限，避免单个特征把评分拉爆
     */
    private static final int MAX_AI_BONUS = 55;
    private static final int MAX_HUMAN_REDUCTION = 45;
    private static final int SENTENCE_SCORE_FLOOR = 5;
    private static final int SENTENCE_SCORE_CEILING = 98;

    /**
     * 整篇修正的阈值
     */
    private static final int MIN_SENTENCES_FOR_CV_ADJUST = 4;
    private static final double CV_STRONG_UNIFORM = 0.20;
    private static final double CV_MILD_UNIFORM = 0.30;
    private static final double CV_STRONG_VARIED = 0.55;
    private static final int ADJUST_STRONG_UNIFORM = 8;
    private static final int ADJUST_MILD_UNIFORM = 4;
    private static final int ADJUST_STRONG_VARIED = -8;
    private static final double AI_PHRASE_DENSITY_STRONG = 8.0;
    private static final double AI_PHRASE_DENSITY_MILD = 4.0;
    private static final double FIRST_PERSON_DENSITY = 3.0;

    /**
     * 连接词密度阈值（每千字），密度过高是 AI 文本的「低困惑度」代理特征
     */
    private static final double CONNECTIVE_DENSITY_STRONG = 16.0;
    private static final double CONNECTIVE_DENSITY_MILD = 10.0;

    /**
     * AI 免责/提示语与句首重复的整篇修正幅度
     */
    private static final int ADJUST_DISCLAIMER_ONE = 6;
    private static final int ADJUST_DISCLAIMER_MANY = 9;
    private static final int ADJUST_OPENER_STRONG = 5;
    private static final int ADJUST_OPENER_MILD = 2;
    private static final double OPENER_SHARE_STRONG = 0.40;
    private static final double OPENER_SHARE_MILD = 0.25;
    private static final int MIN_SENTENCES_FOR_OPENER_ADJUST = 6;

    /**
     * 句子切分参数
     */
    private static final int MIN_LINE_SENTENCE_CHARS = 20;

    /**
     * 泛泛而谈判定的长句下限（字符数）
     */
    private static final int VAGUE_SENTENCE_CHARS = 60;

    /**
     * 文档级依据列表的最大条数
     */
    private static final int MAX_MARKERS = 10;

    /**
     * AI 常见套话（中文）
     */
    private static final String[] AI_CLICHES_CN = {
            "值得注意的是", "值得一提的是", "综上所述", "总而言之", "总体而言", "综合来看",
            "由此可见", "不仅如此", "更重要的是", "更为重要的是", "换句话说", "换言之",
            "与此同时", "众所周知", "毋庸置疑", "不可否认", "毫无疑问", "显而易见", "不难发现",
            "总的来说", "总体来说", "总的来看", "不难看出", "可以预见", "我们有理由相信",
            "从长远来看", "在一定程度上", "从某种意义上说"
    };

    /**
     * AI 常见套话（英文）
     */
    private static final String[] AI_CLICHES_EN = {
            "it is important to note", "it's important to note", "it is worth noting", "it's worth noting",
            "in conclusion", "to sum up", "in summary", "in essence",
            "moreover", "furthermore", "additionally", "nevertheless", "nonetheless",
            "it goes without saying", "needless to say", "first and foremost",
            "it is essential to note", "at its core", "when it comes to",
            "let's dive into", "let's delve into", "whether you're", "whether you are",
            "as of my last update", "in the world of", "a double-edged sword", "the key takeaway",
            "rest assured"
    };

    /**
     * AI 常见套路化表达（中文）
     */
    private static final String[] AI_FORMULAIC_CN = {
            "发挥着至关重要的作用", "发挥着重要作用", "扮演着至关重要的角色", "扮演着重要的角色",
            "起着至关重要的作用", "奠定了坚实的基础", "奠定了坚实", "提供了有力的保障",
            "注入了新的活力", "迈上了新的台阶", "保驾护航", "添砖加瓦", "蓬勃发展",
            "欣欣向荣", "日新月异", "应运而生", "层出不穷", "方兴未艾", "与时俱进",
            "不断推进", "持续发力", "在当今快速发展的时代", "在当今社会", "在数字化时代",
            "随着人工智能技术", "随着科技的不断发展", "随着互联网的普及", "随着数字化时代的到来",
            "随着社会的发展", "随着经济的快速发展", "在人工智能时代", "在信息时代", "在大数据时代",
            "迈出坚实步伐", "书写新篇章", "翻开新篇章", "扬帆起航", "行稳致远", "落地生根",
            "开花结果", "更上一层楼", "按下快进键", "跑出加速度", "插上腾飞的翅膀",
            "注入强大动力", "迎来了前所未有的机遇"
    };

    /**
     * AI 常见套路化表达（英文）
     */
    private static final String[] AI_FORMULAIC_EN = {
            "plays a crucial role", "plays a vital role", "plays a significant role",
            "paves the way", "sets the stage", "a testament to",
            "in today's fast-paced", "in the ever-evolving", "in the realm of",
            "delve into", "delves into", "navigating the", "harness the power",
            "unlock the potential", "seamlessly integrate", "cutting-edge",
            "state-of-the-art", "myriad of", "plethora of", "paradigm shift", "game-changer",
            "in the ever-changing", "as we navigate", "we find ourselves", "it's no secret",
            "revolutionize", "game-changing", "next-generation", "at the forefront",
            "push the boundaries", "bridge the gap", "foster innovation", "empower",
            "seamless experience", "holistic approach", "digital transformation", "unlock new"
    };

    /**
     * 中文 AI 高频词 / 互联网黑话（弱信号，单项权重低，聚簇时起效）
     */
    private static final String[] AI_BUZZWORDS_CN = {
            "赋能", "抓手", "闭环", "底层逻辑", "顶层设计", "颗粒度", "生态化反",
            "协同增效", "降本增效", "打通壁垒", "组合拳", "护城河", "降维打击",
            "数字化转型", "深度融合", "创新驱动", "全方位", "多维度", "系统性", "一体化"
    };

    /**
     * AI 免责/提示语（中文）：聊天机器人惯用的「仅供参考」「请核实」类收尾
     */
    private static final String[] AI_DISCLAIMERS_CN = {
            "仅供参考", "请以官方", "可能不准确", "可能已过时", "信息可能不完整",
            "请自行核实", "请咨询专业人士", "不构成投资建议", "不构成法律建议",
            "我可能出错", "请谨慎参考", "请谨慎甄别"
    };

    /**
     * AI 免责/提示语（英文）
     */
    private static final String[] AI_DISCLAIMERS_EN = {
            "as of my last update", "as of my knowledge cutoff", "not financial advice",
            "not legal advice", "not medical advice", "i am not a lawyer",
            "consult a qualified", "always consult", "please verify", "double-check",
            "use with caution", "proceed with caution"
    };

    /**
     * 逻辑连接词（中文）：统计密度，密度过高对应 AI 文本的低困惑度特征
     */
    private static final String[] CONNECTIVES_CN = {
            "此外", "另外", "与此同时", "然而", "因此", "并且", "而且", "其次",
            "首先", "总之", "例如", "也就是说", "具体来说", "总的来说", "相较之下"
    };

    /**
     * 逻辑连接词（英文）
     */
    private static final String[] CONNECTIVES_EN = {
            "however", "moreover", "furthermore", "additionally", "therefore", "thus",
            "hence", "nevertheless", "meanwhile", "consequently", "in addition",
            "for instance", "for example", "specifically", "overall", "finally",
            "firstly", "secondly", "lastly", "in other words"
    };

    /**
     * 英文连接词的整词匹配模式（预编译）
     */
    private static final List<Pattern> CONNECTIVE_EN_PATTERNS = buildWordPatterns(CONNECTIVES_EN);

    /**
     * 把英文短语表编译为忽略大小写的整词匹配模式
     */
    private static List<Pattern> buildWordPatterns(String[] phrases) {
        List<Pattern> patterns = new ArrayList<>(phrases.length);
        for (String phrase : phrases) {
            patterns.add(Pattern.compile("\\b" + Pattern.quote(phrase) + "\\b", Pattern.CASE_INSENSITIVE));
        }
        return patterns;
    }

    /**
     * 口语化表达（中文），更像人工写作
     */
    private static final String[] HUMAN_COLLOQUIAL_CN = {
            "其实", "说实话", "坦白讲", "说白了", "反正", "居然", "竟然", "貌似", "好像",
            "大概", "也许", "估计", "搞不懂", "弄不明白", "鬼知道", "天知道", "无语", "醉了", "挺好", "蛮好"
    };

    /**
     * 语气词/感叹（中文），更像人工写作
     */
    private static final String[] HUMAN_INTERJECTIONS_CN = {
            "哈哈", "嘿嘿", "笑死", "呃", "嗯", "欸", "诶", "哇塞", "天呐", "妈呀"
    };

    /**
     * 中文第一人称（「我国」除外）
     */
    private static final Pattern PATTERN_CN_FIRST_PERSON = Pattern.compile("我(?!国)");

    /**
     * 英文第一人称
     */
    private static final Pattern PATTERN_EN_FIRST_PERSON = Pattern.compile("\\b(?:I|me|my|mine|we|our|us)\\b");

    /**
     * 英文缩略形式（don't / it's / they're 等）
     */
    private static final Pattern PATTERN_EN_CONTRACTION = Pattern.compile("[A-Za-z]+(?:n't|'re|'ve|'ll|'m|'d)");

    /**
     * 具体数字（年份、数量、百分比等）
     */
    private static final Pattern PATTERN_NUMBER = Pattern.compile("[0-9]+(?:[.,][0-9]+)*");

    /**
     * 破折号（中文双破折号、英文 em/en dash、空格夹连字符）
     */
    private static final Pattern PATTERN_DASH = Pattern.compile("——|—|–|\\s-\\s");

    /**
     * 中文多项并列：一句内出现两个及以上顿号
     */
    private static final Pattern PATTERN_CN_ENUMERATION = Pattern.compile("、[^。！？；!?\\n]{0,30}、");

    /**
     * 中文递进套语：「不仅…而且/还/也…」
     */
    private static final Pattern PATTERN_CN_PROGRESSIVE = Pattern.compile("不仅.{0,24}(?:而且|还|也)");

    /**
     * 中文对仗结构：「一方面…另一方面…」
     */
    private static final Pattern PATTERN_CN_BALANCED = Pattern.compile("一方面.{0,40}另一方面");

    /**
     * 英文三项并列：「X, Y, and Z」
     */
    private static final Pattern PATTERN_EN_TRIAD =
            Pattern.compile("[A-Za-z][^.!?\\n]{0,40},\\s+[^,.!?\\n]{1,40},\\s+(?:and|or)\\s+", Pattern.CASE_INSENSITIVE);

    /**
     * 英文「not just X, but Y」式对比重构（negative parallelism）：LLM 高频修辞
     */
    private static final Pattern PATTERN_EN_NOT_JUST = Pattern.compile(
            "\\bnot\\s+(?:just|only|merely|simply)\\b[^.!?\\n]{0,80}?\\b(?:but(?:\\s+also)?|it's|it is|they're|they are|this is|that's)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * 中文固定三段式：「首先…其次…」
     */
    private static final Pattern PATTERN_CN_RIGID_SEQ = Pattern.compile("首先[^。！？；!?\\n]{0,60}其次");

    /**
     * 中文「随着…（不断/持续/快速）发展/普及/深入」式模板开头
     */
    private static final Pattern PATTERN_CN_SUIZHE = Pattern.compile(
            "随着[^。！？；!?\\n]{0,24}(?:不断|持续|迅速|快速|日益|深入)?(?:发展|推进|普及|增长|提升|深入|完善)");

    /**
     * 列表/代码/模板特征行：项目符号、编号、大括号、分号结尾、箭头、函数定义等
     */
    private static final Pattern PATTERN_CODE_HINT_LINE = Pattern.compile(
            "^\\s*[-*•]\\s+|^\\s*\\d+[.)]\\s+|\\{\\s*$|;\\s*$|=>|function\\s+\\w+\\s*\\(|def\\s+\\w+\\s*\\(",
            Pattern.MULTILINE);

    /**
     * 私有构造方法，防止实例化
     */
    private DetectorUtils() {
    }

    /**
     * 对文本做完整 AI 内容检测。
     *
     * <p>文本为空白时返回评分为 0、判定为「无有效文本」的空报告；
     * 文本过短（低于 {@link #MIN_RELIABLE_CHARS} 个字符）或疑似列表/代码/模板时，
     * 置信度会标为「低」。</p>
     *
     * @param text 待检测文本
     * @return 检测报告，永不为 {@code null}
     */
    public static DetectionResult detect(String text) {
        if (StringUtils.isBlank(text)) {
            return DetectionResult.empty();
        }
        List<String> rawSentences = splitSentences(text);
        List<SentenceResult> sentenceResults = new ArrayList<>(rawSentences.size());
        Set<Marker> aiMarkers = new LinkedHashSet<>();
        Set<Marker> humanMarkers = new LinkedHashSet<>();

        int totalChars = 0;
        long weightedScoreSum = 0;
        int aiPhraseHits = 0;
        int firstPersonHits = 0;
        int contractionHits = 0;
        int colloquialHits = 0;
        int numberHits = 0;
        int dashHits = 0;
        int parallelismHits = 0;
        int connectiveHits = 0;
        int disclaimerHits = 0;
        Map<String, Integer> openerCounts = new LinkedHashMap<>();

        for (int i = 0; i < rawSentences.size(); i++) {
            String sentence = rawSentences.get(i);
            int charCount = sentence.length();
            totalChars += charCount;

            SentenceAnalysis analysis = analyzeSentence(sentence);
            aiPhraseHits += analysis.aiPhraseHits;
            firstPersonHits += analysis.firstPersonHits;
            contractionHits += analysis.contractionHits;
            colloquialHits += analysis.colloquialHits;
            numberHits += analysis.numberHits;
            dashHits += analysis.dashHits;
            parallelismHits += analysis.parallelismHits;
            connectiveHits += analysis.connectiveHits;
            disclaimerHits += analysis.disclaimerHits;
            String opener = extractOpener(sentence);
            if (!opener.isEmpty()) {
                openerCounts.merge(opener, 1, Integer::sum);
            }

            int score = clampSentenceScore(analysis.score);
            sentenceResults.add(new SentenceResult(i, sentence, charCount, score,
                    labelOf(score), analysis.reasons));
            weightedScoreSum += (long) score * Math.max(1, charCount);

            for (Marker marker : analysis.aiMarkers) {
                if (aiMarkers.size() < MAX_MARKERS) {
                    aiMarkers.add(marker);
                }
            }
            for (Marker marker : analysis.humanMarkers) {
                if (humanMarkers.size() < MAX_MARKERS) {
                    humanMarkers.add(marker);
                }
            }
        }

        int maxOpenerHits = 0;
        for (int count : openerCounts.values()) {
            maxOpenerHits = Math.max(maxOpenerHits, count);
        }

        int aiProbability = weightedAverage(sentenceResults, weightedScoreSum);
        aiProbability = adjustByDocumentFingerprint(aiProbability, sentenceResults, totalChars,
                aiPhraseHits, firstPersonHits, connectiveHits, disclaimerHits, maxOpenerHits);

        int aiSentenceChars = 0;
        for (SentenceResult sentence : sentenceResults) {
            if (sentence.score() >= SENTENCE_MAYBE_LABEL) {
                aiSentenceChars += sentence.charCount();
            }
        }

        Fingerprint fingerprint = new Fingerprint(sentenceResults.size(), totalChars,
                avgSentenceLength(sentenceResults), lengthStdDev(sentenceResults),
                aiPhraseHits, firstPersonHits, contractionHits, colloquialHits,
                numberHits, dashHits, parallelismHits, connectiveHits, disclaimerHits, maxOpenerHits);

        String confidenceNote = confidenceNote(text, totalChars, sentenceResults.size());
        String confidence = confidenceOf(text, totalChars, sentenceResults.size());

        return new DetectionResult(aiProbability, verdictOf(aiProbability), verdictDescriptionOf(aiProbability),
                confidence, confidenceNote, sentenceResults, new ArrayList<>(aiMarkers),
                new ArrayList<>(humanMarkers), fingerprint, aiSentenceChars);
    }

    /**
     * 仅计算 AI 生成可能性评分（0–100，按句子长度加权）。
     *
     * @param text 待检测文本
     * @return AI 生成可能性评分；空白文本返回 0
     */
    public static int detectScore(String text) {
        return detect(text).aiProbability();
    }

    /**
     * 仅计算判定结果（人工撰写 / 大部分为人工撰写 / 混合：人工与 AI / 可能为 AI 生成 / AI 生成）。
     *
     * @param text 待检测文本
     * @return 判定结果；空白文本返回「无有效文本」
     */
    public static String detectVerdict(String text) {
        return detect(text).verdict();
    }

    /**
     * 快速判断文本是否可能由 AI 生成。
     *
     * @param text 待检测文本
     * @return 评分 ≥ {@link #LIKELY_AI_THRESHOLD} 时返回 true
     */
    public static boolean isLikelyAiGenerated(String text) {
        return detectScore(text) >= LIKELY_AI_THRESHOLD;
    }

    /**
     * 按参考工具的五档口径把评分映射为判定结果。
     *
     * @param score AI 生成可能性评分（0–100）
     * @return 判定结果文案
     */
    public static String verdictOf(int score) {
        if (score <= BAND_HUMAN_MAX) {
            return "人工撰写";
        }
        if (score <= BAND_MOSTLY_HUMAN_MAX) {
            return "大部分为人工撰写";
        }
        if (score <= BAND_MIXED_MAX) {
            return "混合：人工与 AI";
        }
        if (score <= BAND_LIKELY_AI_MAX) {
            return "可能为 AI 生成";
        }
        return "AI 生成";
    }

    /**
     * 判定结果的补充说明。
     *
     * @param score AI 生成可能性评分（0–100）
     * @return 与分档对应的说明文案
     */
    public static String verdictDescriptionOf(int score) {
        if (score <= BAND_HUMAN_MAX) {
            return "几乎没有明显的 AI 写作迹象。";
        }
        if (score <= BAND_MOSTLY_HUMAN_MAX) {
            return "主要由人撰写，部分段落可能带有 AI 特征或经过 AI 润色。";
        }
        if (score <= BAND_MIXED_MAX) {
            return "部分内容可能由 AI 生成或改写，请查看具体标注。";
        }
        if (score <= BAND_LIKELY_AI_MAX) {
            return "文本大部分读起来像 AI 输出，也可能经过少量编辑。";
        }
        return "文本几乎完全像未经编辑的 AI 输出。";
    }

    /**
     * 把句子评分映射为逐句标注：≥ 65 为「AI生成」，40–64 为「可能有AI参与」，其余为「人工撰写」。
     *
     * @param sentenceScore 句子评分（0–100）
     * @return 句子标注文案
     */
    public static String labelOf(int sentenceScore) {
        if (sentenceScore >= SENTENCE_AI_LABEL) {
            return "AI生成";
        }
        if (sentenceScore >= SENTENCE_MAYBE_LABEL) {
            return "可能有AI参与";
        }
        return "人工撰写";
    }

    /**
     * 把文本切分为句子。中文按句号/叹号/问号/分号切分；英文按句点后跟空白切分，
     * 并规避「U.S.」这类缩写误切；换行在当前句足够长或下一行是列表项时也视为边界。
     *
     * @param text 原始文本
     * @return 句子列表（已去除首尾空白与空句）
     */
    static List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            current.append(c);
            if (isHardTerminator(c)) {
                flushSentence(sentences, current);
                continue;
            }
            if (c == '.' && isEnglishSentenceBoundary(text, i)) {
                flushSentence(sentences, current);
                continue;
            }
            if (c == '\n' && isLineBreakBoundary(text, i, current.length() - 1)) {
                flushSentence(sentences, current);
            }
        }
        String rest = current.toString().trim();
        if (!rest.isEmpty()) {
            sentences.add(rest);
        }
        return sentences;
    }

    /**
     * 是否为中文/通用的句末标点
     */
    private static boolean isHardTerminator(char c) {
        return c == '。' || c == '！' || c == '？' || c == '；' || c == '!' || c == '?' || c == ';';
    }

    /**
     * 英文句点是否构成句子边界：后面是空白或文本结束，且不是「U.S.」这类单字母缩写
     */
    private static boolean isEnglishSentenceBoundary(String text, int dotIndex) {
        int next = dotIndex + 1;
        if (next < text.length() && !Character.isWhitespace(text.charAt(next))) {
            return false;
        }
        if (dotIndex == 0) {
            return false;
        }
        char prev = text.charAt(dotIndex - 1);
        if (!Character.isUpperCase(prev)) {
            return true;
        }
        boolean prevIsSingleLetter = dotIndex < 2 || !Character.isLetter(text.charAt(dotIndex - 2));
        return !prevIsSingleLetter;
    }

    /**
     * 换行是否构成句子边界：当前句已足够长，或下一行是列表项（- / * / • / 1.）
     */
    private static boolean isLineBreakBoundary(String text, int newlineIndex, int currentLength) {
        if (currentLength >= MIN_LINE_SENTENCE_CHARS) {
            return true;
        }
        int next = newlineIndex + 1;
        while (next < text.length() && (text.charAt(next) == ' ' || text.charAt(next) == '\t')) {
            next++;
        }
        if (next >= text.length()) {
            return true;
        }
        char head = text.charAt(next);
        if (head == '-' || head == '*' || head == '•') {
            return true;
        }
        return Character.isDigit(head) && next + 1 < text.length() && text.charAt(next + 1) == '.';
    }

    /**
     * 收尾当前句子
     */
    private static void flushSentence(List<String> sentences, StringBuilder current) {
        String sentence = current.toString().trim();
        current.setLength(0);
        if (!sentence.isEmpty()) {
            sentences.add(sentence);
        }
    }

    /**
     * 单句特征分析结果（内部使用）
     */
    private static final class SentenceAnalysis {
        private int score = BASE_SCORE;
        private int aiPhraseHits;
        private int firstPersonHits;
        private int contractionHits;
        private int colloquialHits;
        private int numberHits;
        private int dashHits;
        private int parallelismHits;
        private int connectiveHits;
        private int disclaimerHits;
        private final List<String> reasons = new ArrayList<>();
        private final List<Marker> aiMarkers = new ArrayList<>();
        private final List<Marker> humanMarkers = new ArrayList<>();
    }

    /**
     * 对单个句子做 AI/人工特征分析并给出评分与依据
     */
    private static SentenceAnalysis analyzeSentence(String sentence) {
        SentenceAnalysis analysis = new SentenceAnalysis();
        int aiBonus = 0;
        int humanReduction = 0;

        for (String phrase : AI_CLICHES_CN) {
            if (sentence.contains(phrase)) {
                aiBonus += WEIGHT_CLICHE;
                analysis.aiPhraseHits++;
                analysis.reasons.add("含 AI 常见套话「" + phrase + "」");
                analysis.aiMarkers.add(new Marker(phrase, "AI 常见套话/过渡语"));
            }
        }
        for (String phrase : AI_CLICHES_EN) {
            if (containsIgnoreCase(sentence, phrase)) {
                aiBonus += WEIGHT_CLICHE;
                analysis.aiPhraseHits++;
                analysis.reasons.add("含 AI 常见套话「" + phrase + "」");
                analysis.aiMarkers.add(new Marker(phrase, "AI 常见套话/过渡语"));
            }
        }
        for (String phrase : AI_FORMULAIC_CN) {
            if (sentence.contains(phrase)) {
                aiBonus += WEIGHT_FORMULAIC;
                analysis.aiPhraseHits++;
                analysis.reasons.add("含 AI 常见套路化表达「" + phrase + "」");
                analysis.aiMarkers.add(new Marker(phrase, "AI 常见套路化表达"));
            }
        }
        for (String phrase : AI_FORMULAIC_EN) {
            if (containsIgnoreCase(sentence, phrase)) {
                aiBonus += WEIGHT_FORMULAIC;
                analysis.aiPhraseHits++;
                analysis.reasons.add("含 AI 常见套路化表达「" + phrase + "」");
                analysis.aiMarkers.add(new Marker(phrase, "AI 常见套路化表达"));
            }
        }
        for (String phrase : AI_BUZZWORDS_CN) {
            if (sentence.contains(phrase)) {
                aiBonus += WEIGHT_BUZZWORD;
                analysis.aiPhraseHits++;
                analysis.reasons.add("含 AI 高频词/黑话「" + phrase + "」");
                analysis.aiMarkers.add(new Marker(phrase, "AI 高频词/互联网黑话"));
            }
        }
        for (String phrase : AI_DISCLAIMERS_CN) {
            if (sentence.contains(phrase)) {
                aiBonus += WEIGHT_DISCLAIMER;
                analysis.disclaimerHits++;
                analysis.reasons.add("含 AI 常见免责/提示语「" + phrase + "」");
                analysis.aiMarkers.add(new Marker(phrase, "AI 免责/提示语"));
            }
        }
        for (String phrase : AI_DISCLAIMERS_EN) {
            if (containsIgnoreCase(sentence, phrase)) {
                aiBonus += WEIGHT_DISCLAIMER;
                analysis.disclaimerHits++;
                analysis.reasons.add("含 AI 常见免责/提示语「" + phrase + "」");
                analysis.aiMarkers.add(new Marker(phrase, "AI 免责/提示语"));
            }
        }

        if (PATTERN_CN_ENUMERATION.matcher(sentence).find()) {
            aiBonus += WEIGHT_PARALLELISM;
            analysis.parallelismHits++;
            analysis.reasons.add("出现整齐的多项并列结构（顿号连用）");
            analysis.aiMarkers.add(new Marker("、…、", "整齐的多项并列"));
        }
        if (PATTERN_EN_TRIAD.matcher(sentence).find()) {
            aiBonus += WEIGHT_PARALLELISM;
            analysis.parallelismHits++;
            analysis.reasons.add("出现整齐的三项并列结构（X, Y, and Z）");
            analysis.aiMarkers.add(new Marker("X, Y, and Z", "整齐的三项并列"));
        }
        if (PATTERN_CN_PROGRESSIVE.matcher(sentence).find()) {
            aiBonus += WEIGHT_BALANCED;
            analysis.reasons.add("出现「不仅…而且/还…」式递进套语");
            analysis.aiMarkers.add(new Marker("不仅…而且", "递进套语"));
        }
        if (PATTERN_CN_BALANCED.matcher(sentence).find()) {
            aiBonus += WEIGHT_BALANCED;
            analysis.reasons.add("出现「一方面…另一方面」式对仗结构，语气过度平衡");
            analysis.aiMarkers.add(new Marker("一方面…另一方面", "对仗结构"));
        }
        if (PATTERN_CN_RIGID_SEQ.matcher(sentence).find()) {
            aiBonus += WEIGHT_BALANCED;
            analysis.reasons.add("出现「首先…其次…」式固定分点结构");
            analysis.aiMarkers.add(new Marker("首先…其次", "固定分点结构"));
        }
        if (PATTERN_CN_SUIZHE.matcher(sentence).find()) {
            aiBonus += WEIGHT_BALANCED;
            analysis.reasons.add("出现「随着…发展」式模板化开头");
            analysis.aiMarkers.add(new Marker("随着…发展", "模板化开头"));
        }
        if (PATTERN_EN_NOT_JUST.matcher(sentence).find()) {
            aiBonus += WEIGHT_PARALLELISM;
            analysis.parallelismHits++;
            analysis.reasons.add("出现「not just X, but Y」式对比重构（LLM 高频修辞）");
            analysis.aiMarkers.add(new Marker("not just… but", "对比重构句式"));
        }

        Matcher dashMatcher = PATTERN_DASH.matcher(sentence);
        while (dashMatcher.find()) {
            analysis.dashHits++;
        }
        if (analysis.dashHits > 0 && sentence.length() > VAGUE_SENTENCE_CHARS / 2) {
            aiBonus += Math.min(WEIGHT_DASH * analysis.dashHits, WEIGHT_DASH * 2);
            analysis.reasons.add("破折号使用偏多（AI 英文写作常见特征）");
            analysis.aiMarkers.add(new Marker("—", "破折号偏好"));
        }

        Matcher cnFirstPerson = PATTERN_CN_FIRST_PERSON.matcher(sentence);
        while (cnFirstPerson.find()) {
            humanReduction += REDUCTION_FIRST_PERSON;
            analysis.firstPersonHits++;
            analysis.reasons.add("含第一人称表达，更像人工写作");
            analysis.humanMarkers.add(new Marker("我/我们", "第一人称表达"));
        }
        Matcher enFirstPerson = PATTERN_EN_FIRST_PERSON.matcher(sentence);
        while (enFirstPerson.find()) {
            humanReduction += REDUCTION_FIRST_PERSON;
            analysis.firstPersonHits++;
            analysis.reasons.add("含第一人称表达「" + enFirstPerson.group() + "」");
            analysis.humanMarkers.add(new Marker(enFirstPerson.group(), "第一人称表达"));
        }
        Matcher contraction = PATTERN_EN_CONTRACTION.matcher(sentence);
        while (contraction.find()) {
            humanReduction += REDUCTION_CONTRACTION;
            analysis.contractionHits++;
            analysis.reasons.add("含英语缩略形式「" + contraction.group() + "」，更像人工写作");
            analysis.humanMarkers.add(new Marker(contraction.group(), "英语缩略形式"));
        }
        for (String phrase : HUMAN_COLLOQUIAL_CN) {
            if (sentence.contains(phrase)) {
                humanReduction += REDUCTION_COLLOQUIAL;
                analysis.colloquialHits++;
                analysis.reasons.add("含口语化表达「" + phrase + "」，更像人工写作");
                analysis.humanMarkers.add(new Marker(phrase, "口语化表达"));
            }
        }
        for (String phrase : HUMAN_INTERJECTIONS_CN) {
            if (sentence.contains(phrase)) {
                humanReduction += REDUCTION_INTERJECTION;
                analysis.colloquialHits++;
                analysis.reasons.add("含语气词「" + phrase + "」，更像人工写作");
                analysis.humanMarkers.add(new Marker(phrase, "语气词/感叹"));
            }
        }
        Matcher number = PATTERN_NUMBER.matcher(sentence);
        while (number.find()) {
            humanReduction += REDUCTION_NUMBER;
            analysis.numberHits++;
            analysis.reasons.add("含具体数字「" + number.group() + "」，细节感更强");
            analysis.humanMarkers.add(new Marker(number.group(), "具体数字"));
        }

        for (String phrase : CONNECTIVES_CN) {
            analysis.connectiveHits += countOccurrences(sentence, phrase);
        }
        for (Pattern pattern : CONNECTIVE_EN_PATTERNS) {
            Matcher connective = pattern.matcher(sentence);
            while (connective.find()) {
                analysis.connectiveHits++;
            }
        }

        if (sentence.length() >= VAGUE_SENTENCE_CHARS && analysis.numberHits == 0 && analysis.firstPersonHits == 0) {
            aiBonus += WEIGHT_VAGUE;
            analysis.reasons.add("句子较长且缺少具体数字、第一人称等细节，偏泛泛而谈");
            analysis.aiMarkers.add(new Marker(truncate(sentence, 30), "长句泛泛而谈"));
        }

        analysis.score = clampSentenceScore(BASE_SCORE + Math.min(aiBonus, MAX_AI_BONUS)
                - Math.min(humanReduction, MAX_HUMAN_REDUCTION));
        return analysis;
    }

    /**
     * 按句子长度加权求整篇评分，并用写作指纹（句子长度变化幅度、AI 措辞密度、
     * 第一人称密度、连接词密度、免责语与句首重复）做整篇修正。
     */
    private static int adjustByDocumentFingerprint(int baseScore, List<SentenceResult> sentences,
                                                   int totalChars, int aiPhraseHits, int firstPersonHits,
                                                   int connectiveHits, int disclaimerHits, int maxOpenerHits) {
        int score = baseScore;
        int adjust = 0;

        if (sentences.size() >= MIN_SENTENCES_FOR_CV_ADJUST) {
            double cv = lengthCV(sentences);
            if (cv < CV_STRONG_UNIFORM) {
                adjust += ADJUST_STRONG_UNIFORM;
            } else if (cv < CV_MILD_UNIFORM) {
                adjust += ADJUST_MILD_UNIFORM;
            } else if (cv > CV_STRONG_VARIED) {
                adjust += ADJUST_STRONG_VARIED;
            }
        }
        if (totalChars > 0) {
            double phraseDensity = aiPhraseHits * 1000.0 / totalChars;
            if (phraseDensity >= AI_PHRASE_DENSITY_STRONG) {
                adjust += 6;
            } else if (phraseDensity >= AI_PHRASE_DENSITY_MILD) {
                adjust += 3;
            }
            double firstPersonDensity = firstPersonHits * 1000.0 / totalChars;
            if (firstPersonDensity >= FIRST_PERSON_DENSITY) {
                adjust -= 6;
            }
            double connectiveDensity = connectiveHits * 1000.0 / totalChars;
            if (connectiveDensity >= CONNECTIVE_DENSITY_STRONG) {
                adjust += 4;
            } else if (connectiveDensity >= CONNECTIVE_DENSITY_MILD) {
                adjust += 2;
            }
        }
        if (disclaimerHits >= 2) {
            adjust += ADJUST_DISCLAIMER_MANY;
        } else if (disclaimerHits >= 1) {
            adjust += ADJUST_DISCLAIMER_ONE;
        }
        if (sentences.size() >= MIN_SENTENCES_FOR_OPENER_ADJUST && maxOpenerHits >= 2) {
            double openerShare = maxOpenerHits / (double) sentences.size();
            if (openerShare >= OPENER_SHARE_STRONG && maxOpenerHits >= 3) {
                adjust += ADJUST_OPENER_STRONG;
            } else if (openerShare >= OPENER_SHARE_MILD) {
                adjust += ADJUST_OPENER_MILD;
            }
        }
        return clampScore(score + adjust);
    }

    /**
     * 句首开头（用于统计句首重复）：中文取前两个汉字，英文取首个单词（转小写）
     */
    private static String extractOpener(String sentence) {
        String trimmed = sentence.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        char first = trimmed.charAt(0);
        if (first >= 0x4E00 && first <= 0x9FFF) {
            return trimmed.substring(0, Math.min(2, trimmed.length()));
        }
        if (Character.isLetter(first)) {
            int end = 1;
            while (end < trimmed.length()
                    && (Character.isLetterOrDigit(trimmed.charAt(end)) || trimmed.charAt(end) == '\'')) {
                end++;
            }
            return trimmed.substring(0, end).toLowerCase();
        }
        return "";
    }

    /**
     * 统计子串出现次数（中文短语按普通子串匹配）
     */
    private static int countOccurrences(String sentence, String phrase) {
        int count = 0;
        int index = 0;
        while ((index = sentence.indexOf(phrase, index)) >= 0) {
            count++;
            index += phrase.length();
        }
        return count;
    }

    /**
     * 句子评分的加权平均
     */
    private static int weightedAverage(List<SentenceResult> sentences, long weightedScoreSum) {
        int totalChars = 0;
        for (SentenceResult sentence : sentences) {
            totalChars += Math.max(1, sentence.charCount());
        }
        if (totalChars == 0) {
            return 0;
        }
        return clampScore((int) Math.round(weightedScoreSum / (double) totalChars));
    }

    /**
     * 句子平均长度（字符）
     */
    private static double avgSentenceLength(List<SentenceResult> sentences) {
        if (sentences.isEmpty()) {
            return 0.0;
        }
        int total = 0;
        for (SentenceResult sentence : sentences) {
            total += sentence.charCount();
        }
        return round2(total / (double) sentences.size());
    }

    /**
     * 句子长度的标准差
     */
    private static double lengthStdDev(List<SentenceResult> sentences) {
        if (sentences.size() < 2) {
            return 0.0;
        }
        double avg = avgSentenceLength(sentences);
        double variance = 0.0;
        for (SentenceResult sentence : sentences) {
            double diff = sentence.charCount() - avg;
            variance += diff * diff;
        }
        return round2(Math.sqrt(variance / sentences.size()));
    }

    /**
     * 句子长度的变异系数（标准差 / 平均值），衡量长短交错程度
     */
    private static double lengthCV(List<SentenceResult> sentences) {
        double avg = avgSentenceLength(sentences);
        if (avg <= 0.0) {
            return 0.0;
        }
        return round2(lengthStdDev(sentences) / avg);
    }

    /**
     * 置信度：空白或过短为「低」；疑似列表/代码/模板为「低」；
     * 不足 {@link #MORE_RELIABLE_CHARS} 字符或句子太少为「中」；否则为「高」。
     */
    private static String confidenceOf(String text, int totalChars, int sentenceCount) {
        if (totalChars < MIN_RELIABLE_CHARS || looksLikeCodeOrList(text)) {
            return "低";
        }
        if (totalChars < MORE_RELIABLE_CHARS || sentenceCount < 2) {
            return "中";
        }
        if (sentenceCount >= SENTENCES_FOR_HIGH_CONFIDENCE) {
            return "高";
        }
        return "中";
    }

    /**
     * 置信度的补充说明（如「文本过短」「疑似列表/代码/模板文本」），无则返回空串
     */
    private static String confidenceNote(String text, int totalChars, int sentenceCount) {
        if (looksLikeCodeOrList(text)) {
            return "疑似列表、代码或模板文本，缺少自然句式结构，结果仅供参考";
        }
        if (totalChars < MIN_RELIABLE_CHARS) {
            return "文本过短（建议至少 " + MIN_RELIABLE_CHARS + " 个字符），可供判断的依据太少";
        }
        if (totalChars < MORE_RELIABLE_CHARS) {
            return "文本较短（达到 " + MORE_RELIABLE_CHARS + " 个字符以上结果通常更可靠）";
        }
        if (sentenceCount < SENTENCES_FOR_HIGH_CONFIDENCE) {
            return "句子数较少，结果参考性有限";
        }
        return "";
    }

    /**
     * 是否疑似列表、代码或模板文本（项目符号/编号行、大括号、分号结尾、箭头、函数定义等占比过高）
     */
    private static boolean looksLikeCodeOrList(String text) {
        String[] lines = text.split("\\r?\\n");
        if (lines.length < 3) {
            return false;
        }
        int hits = 0;
        for (String line : lines) {
            if (PATTERN_CODE_HINT_LINE.matcher(line).find()) {
                hits++;
            }
        }
        return hits * 2 >= lines.length;
    }

    /**
     * 忽略大小写的包含判断（对中文短语同样适用）
     */
    private static boolean containsIgnoreCase(String source, String phrase) {
        if (source == null || phrase == null || phrase.isEmpty()) {
            return false;
        }
        return source.regionMatches(true, 0, phrase, 0, phrase.length())
                || indexOfIgnoreCase(source, phrase) >= 0;
    }

    /**
     * 忽略大小写查找子串位置
     */
    private static int indexOfIgnoreCase(String source, String phrase) {
        int limit = source.length() - phrase.length();
        for (int i = 0; i <= limit; i++) {
            if (source.regionMatches(true, i, phrase, 0, phrase.length())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 截断文本并追加省略号
     */
    private static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "…";
    }

    /**
     * 句子评分限幅
     */
    private static int clampSentenceScore(int score) {
        return Math.max(SENTENCE_SCORE_FLOOR, Math.min(SENTENCE_SCORE_CEILING, score));
    }

    /**
     * 整篇评分限幅
     */
    private static int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
    }

    /**
     * 保留两位小数
     */
    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * 文档级依据：一条「证据 + 判断依据」。
     *
     * @param evidence 从原文摘录的证据（短语、数字或句子片段）
     * @param reason   判断依据说明
     */
    public record Marker(String evidence, String reason) {
    }

    /**
     * 逐句标注结果。
     *
     * @param index     句子在全文中的序号（从 0 开始）
     * @param text      句子原文
     * @param charCount 句子字符数
     * @param score     该句 AI 生成可能性评分（0–100）
     * @param label     标注：AI生成 / 可能有AI参与 / 人工撰写
     * @param reasons   判断依据列表（含 AI 迹象与人工迹象）
     */
    public record SentenceResult(int index, String text, int charCount, int score, String label,
                                 List<String> reasons) {
    }

    /**
     * 写作指纹：由代码直接统计、无需主观判断的文本特征。
     *
     * @param sentenceCount   句子数
     * @param totalChars      总字符数
     * @param avgSentenceLength 句子平均长度（字符）
     * @param lengthStdDev    句子长度标准差（人类写作通常更大，AI 文本偏小）
     * @param aiPhraseHits    AI 常见措辞命中次数
     * @param firstPersonHits 第一人称表达次数
     * @param contractionHits 英语缩略形式次数
     * @param colloquialHits  口语化表达/语气词次数
     * @param numberHits      具体数字出现次数
     * @param dashHits        破折号出现次数
     * @param parallelismHits 整齐并列/对仗结构命中次数
     * @param connectiveHits  逻辑连接词出现次数（密度过高是 AI 文本特征）
     * @param disclaimerHits  AI 免责/提示语命中次数
     * @param repeatedOpenerHits 最高频句首开头的出现次数（句首重复是 AI 文本特征）
     */
    public record Fingerprint(int sentenceCount, int totalChars, double avgSentenceLength, double lengthStdDev,
                              int aiPhraseHits, int firstPersonHits, int contractionHits, int colloquialHits,
                              int numberHits, int dashHits, int parallelismHits,
                              int connectiveHits, int disclaimerHits, int repeatedOpenerHits) {
    }

    /**
     * 完整检测报告。
     *
     * @param aiProbability    AI 生成可能性评分（0–100，按句子长度加权）
     * @param verdict          判定结果（五档口径，见类文档）
     * @param verdictDescription 判定结果的补充说明
     * @param confidence       置信度：高 / 中 / 低
     * @param confidenceNote   置信度补充说明（如「文本过短」），无则返回空串
     * @param sentences        逐句标注结果
     * @param aiMarkers        AI 写作迹象依据（从原文摘录）
     * @param humanMarkers     人工写作迹象依据（从原文摘录）
     * @param fingerprint      写作指纹统计
     * @param aiSentenceChars  被标记为「可能有AI参与」及以上的句子的总字符数
     */
    public record DetectionResult(int aiProbability, String verdict, String verdictDescription,
                                  String confidence, String confidenceNote,
                                  List<SentenceResult> sentences, List<Marker> aiMarkers,
                                  List<Marker> humanMarkers, Fingerprint fingerprint, int aiSentenceChars) {

        /**
         * 空白文本的空报告
         */
        static DetectionResult empty() {
            return new DetectionResult(0, "无有效文本", "未提供可分析的文本。", "低", "文本为空",
                    List.of(), List.of(), List.of(),
                    new Fingerprint(0, 0, 0.0, 0.0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), 0);
        }
    }
}

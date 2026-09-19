package com.chua.deeplearning.support.onnx.classification;

import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;


/**
 * CL Tagger                            
 * <p>
 *        CL Tagger                                     
 *                                                                      
 * </p>
 * <p>
 *                
 * -                          
 * -                             Resize + Normalize   
 * -            Top-K       
 * </p>
 * <p>
 *                   
 * 1.           448x448
 * 2.              [0, 1]
 * 3.           CHW       
 * 4. 镜像net
 * </p>
 *
 * @author CH
 * @since 2025/01/26
 */
public class ClTaggerTranslator implements Translator<Image, Classifications> {

    /**
     * JSON 对象映射器
    */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /**
     * 默认类别数量
    */
    private static final int DEFAULT_CLASS_COUNT = 51213;
    /**
     * 低信息范围阈值
    */
    private static final float LOW_INFORMATION_RANGE_THRESHOLD = 2.0f;
    /**
     * 日志记录器
    */
    private static final Logger log = LoggerFactory.getLogger(ClTaggerTranslator.class);

    /**
     *                   
     */
    private static final int INPUT_SIZE = 448;

    /**
     * Top-K             
     */
    private final int topk;

    /**
     *                   
     */
    private List<String> classes;

    /**
     * 低信息输入标记
    */
    private final ThreadLocal<Boolean> lowInformationInput = ThreadLocal.withInitial(() -> false);

    /**
     *              -              Top-K
     *
     * @param classes                   
     */
    public ClTaggerTranslator() {
        this(defaultClasses(DEFAULT_CLASS_COUNT), 10);
    }

    /**
     * 创建 cltaggertranslator 实例
     * @param classes classes
     */
    public ClTaggerTranslator(List<String> classes) {
        this(classes, 10);
    }

    /**
     *              -           Top-K
     *
     * @param classes                   
     * @param topk    Top-K             
     */
    public ClTaggerTranslator(List<String> classes, int topk) {
        this.classes = new ArrayList<>(classes);
        this.topk = topk;
        if (log.isDebugEnabled()) {
            log.debug("[CL Tagger][Translator]                -             : {}x{},          : {}, Top-K: {}",
                    INPUT_SIZE, INPUT_SIZE, classes.size(), topk);
        }
    }

    @Override
    /**
     * Prepare
    */
    public void prepare(TranslatorContext ctx) throws IOException {
        Path modelRoot = resolveModelRoot(ctx.getModel().getModelPath());
        Path tagMappingPath = modelRoot.resolve("tag_mapping.json");
        if (Files.exists(tagMappingPath)) {
            List<String> loadedClasses = loadTagMapping(tagMappingPath);
            if (!loadedClasses.isEmpty()) {
                this.classes = loadedClasses;
            }
        } else if(looksLikeDefaultClasses(this.classes) && this.classes.size() != DEFAULT_CLASS_COUNT) {
            this.classes = defaultClasses(DEFAULT_CLASS_COUNT);
        }

        if (log.isDebugEnabled()) {
            log.debug("[CL Tagger][Translator]prepare        - modelRoot={}, classes={}", modelRoot, this.classes.size());
        }
    }

    /**
     *                   
     *
     * @param ctx                     
     * @param input             
     * @return              NDList
     * @throws Exception             
     */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) throws Exception {
        var manager = ctx.getNDManager();
        var array = input.toNDArray(manager, Image.Flag.COLOR);
        lowInformationInput.set(isLowInformation(array));

        //                      
        array = NDImageUtils.resize(array, INPUT_SIZE, INPUT_SIZE);

        //        float32                 0~1
        array = array.toType(DataType.FLOAT32, false).div(255f);

        // HWC -> CHW
        array = array.transpose(2, 0, 1);

 // 镜像net
        var mean = manager.create(new float[]{0.485f, 0.456f, 0.406f}, new Shape(3, 1, 1));
        var std = manager.create(new float[]{0.229f, 0.224f, 0.225f}, new Shape(3, 1, 1));
        array = array.sub(mean).div(std);

        if (log.isDebugEnabled()) {
            log.debug("[CL Tagger][Translator]                  : shape={}, dtype={}", array.getShape(), array.getDataType());
        }

        return new NDList(array);
    }

    /**
     *                   
     *
     * @param ctx                    
     * @param list              nd列表
     * @return             
     * @throws Exception             
     */
    @Override
    public Classifications processOutput(TranslatorContext ctx, NDList list) throws Exception {
        try {
            if (Boolean.TRUE.equals(lowInformationInput.get())) {
                return null;
            }

            var probabilitiesNd = list.singletonOrThrow();

            if (log.isDebugEnabled()) {
                log.debug("[CL Tagger][Translator]       shape: {}, dtype: {}", probabilitiesNd.getShape(), probabilitiesNd.getDataType());
            }

            //                                   Top-K   
            return new Classifications(classes, probabilitiesNd, Math.min(topk, classes.size()));
        }
finally {
            lowInformationInput.remove();
        }
    }

    /**
     *                   
     *
     * @return STACK             
     */
    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }

    /**
     * 默认类
     *
     * @param size 大小
     * @return 默认类的结果
     */
    private static List<String> defaultClasses(int size) {
        return IntStream.range(0, size)
                .mapToObj(index -> "tag-" + index)
                .toList();
    }

    /**
     * lookslike默认类
     *
     * @param classes 类
     * @return lookslike默认类的结果
     */
    private static boolean looksLikeDefaultClasses(List<String> classes) {
        return classes != null
                && !classes.isEmpty()
                && classes.stream().limit(Math.min(16, classes.size())).allMatch(value -> value != null && value.startsWith("tag-"));
    }

    /**
     * 是否low信息
     *
     * @param array array
     * @return 是否low信息的结果
     */
    private static boolean isLowInformation(NDArray array) {
        NDArray floatArray = array.toType(DataType.FLOAT32, false);
        float min = floatArray.min().toFloatArray()[0];
        float max = floatArray.max().toFloatArray()[0];
        return (max - min) <= LOW_INFORMATION_RANGE_THRESHOLD;
    }

    /**
     * 解析模型根
     *
     * @param modelPath 模型路径
     * @return resolve模型根的结果
     */
    private static Path resolveModelRoot(Path modelPath) {
        if (modelPath == null) {
            return Path.of(".");
        }
        Path normalized = modelPath.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized)) {
            return normalized;
        }
        return normalized.getParent() == null ? normalized : normalized.getParent();
    }

    /**
     * 加载标签mapping
     *
     * @param tagMappingPath 标签mapping路径
     * @return 加载标签mapping的结果
     */
    private static List<String> loadTagMapping(Path tagMappingPath) throws IOException {
        Map<String, TagMappingEntry> rawMapping = OBJECT_MAPPER.readValue(tagMappingPath.toFile(),
                new TypeReference<Map<String, TagMappingEntry>>() {
                });
        if (rawMapping == null || rawMapping.isEmpty()) {
            return List.of();
        }
        return rawMapping.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> Integer.parseInt(entry.getKey())))
                .map(entry -> {
                    TagMappingEntry value = entry.getValue();
                    if (value == null || value.tag == null || value.tag.isBlank()) {
                        return "tag-" + entry.getKey();
                    }
                    return value.tag;
                })
                .toList();
    }

    /**
     * 标签mappingentry
     *
     * @param tag 标签
     * @param category 分类
     * @return 标签mappingentry的结果
     */
    private record TagMappingEntry(String tag, String category) {
    }
}

package com.chua.ueba.support.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
* UEBA 配置序列化器。
* <p>
* 将内存中的 {@link UebaConfig} 以纯 YAML 结构写盘（仅基础类型：列表/映射/字符串/
* 数字/bool），与 Java 读取端 模式 完全一致，可被 Python 训练脚本直接
* {@code yaml.safe_load} 解析。用于程序化构造配置（非文件来源）时的训练契约落盘。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public final class UebaConfigSerializer {

    /**
    * 私有构造，防止实例化。
    */
    private UebaConfigSerializer() {
    }

    /**
    * 将配置以纯 YAML 写入目标文件。
    *
    * @param target 目标文件，不能为 空
    * @param config 配置对象，不能为 空
    * @throws IllegalArgumentException 当任一参数为 空 时
    * @throws UncheckedIOException     当写入失败时
    */
    public static void write(Path target, UebaConfig config) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(config, "config must not be null");
        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumperOptions.setIndent(2);
        Yaml yaml = new Yaml(dumperOptions);
        try {
            Files.writeString(target, yaml.dump(toPlainMap(config)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("序列化 UEBA 配置失败: " + target, e);
        }
    }

    /**
    * 将配置转换为纯结构 映射（与 YAML 模式 一致）。
    *
    * @param config 配置对象，不能为 空
    * @return 纯结构 映射
    */
    private static Map<String, Object> toPlainMap(UebaConfig config) {
        Map<String, Object> root = new LinkedHashMap<>(8);
        root.put("features", features(config.getFeatures()));
        root.put("autoEncoder", autoEncoder(config.getAutoEncoder()));
        root.put("lstm", lstm(config.getLstm()));
        root.put("risk", risk(config.getRisk()));
        root.put("preprocessing", preprocessing(config.getPreprocessing()));
        return root;
    }

    /**
    * 序列化特征列表。
    *
    * @param defs 特征定义列表，允许为 空
    * @return 纯结构列表
    */
    private static List<Object> features(List<FeatureDefinition> defs) {
        List<Object> result = new ArrayList<>();
        if (defs == null) {
            return result;
        }
        for (FeatureDefinition def : defs) {
            Map<String, Object> item = new LinkedHashMap<>(8);
            item.put("name", def.getName());
            item.put("type", def.getType() == null ? "NUMERIC" : def.getType().name());
            item.put("normalize", def.getNormalize() == null ? "NONE" : def.getNormalize().name());
            item.put("window", def.getWindow());
            item.put("vocabSize", def.getVocabSize());
            item.put("vocab", def.getVocab() == null ? List.of() : new ArrayList<>(def.getVocab()));
            item.put("period", def.getPeriod());
            result.add(item);
        }
        return result;
    }

    /**
    * 序列化 auto编码器 配置。
    *
    * @param ae auto编码器 配置，允许为 空
    * @return 纯结构 映射，ae 为 空 时返回空 映射
    */
    private static Map<String, Object> autoEncoder(UebaConfig.AutoEncoder ae) {
        Map<String, Object> map = new LinkedHashMap<>(4);
        if (ae == null) {
            return map;
        }
        map.put("hiddenDims", ae.getHiddenDims() == null ? List.of() : new ArrayList<>(ae.getHiddenDims()));
        map.put("threshold", ae.getThreshold());
        map.put("modelFile", ae.getModelFile());
        map.put("modelPath", ae.getModelPath() == null ? "" : ae.getModelPath());
        return map;
    }

    /**
    * 序列化 LSTM 配置。
    *
    * @param lstm LSTM 配置，允许为 空
    * @return 纯结构 映射，lstm 为 空 时返回空 映射
    */
    private static Map<String, Object> lstm(UebaConfig.Lstm lstm) {
        Map<String, Object> map = new LinkedHashMap<>(6);
        if (lstm == null) {
            return map;
        }
        map.put("windowSize", lstm.getWindowSize());
        map.put("numNumericFeatures", lstm.getNumNumericFeatures());
        map.put("numClasses", lstm.getNumClasses());
        map.put("classLabels", lstm.getClassLabels() == null ? List.of() : new ArrayList<>(lstm.getClassLabels()));
        map.put("modelFile", lstm.getModelFile());
        map.put("modelPath", lstm.getModelPath() == null ? "" : lstm.getModelPath());
        return map;
    }

    /**
    * 序列化风险配置。
    *
    * @param risk 风险配置，允许为 空
    * @return 纯结构 映射，risk 为 空 时返回空 映射
    */
    private static Map<String, Object> risk(UebaConfig.Risk risk) {
        Map<String, Object> map = new LinkedHashMap<>(4);
        if (risk == null) {
            return map;
        }
        map.put("ipWeight", risk.getIpWeight());
        map.put("behaviorWeight", risk.getBehaviorWeight());
        map.put("highThreshold", risk.getHighThreshold());
        map.put("mediumThreshold", risk.getMediumThreshold());
        return map;
    }

    /**
    * 序列化预处理参数。
    *
    * @param preprocessing 预处理配置，允许为 空
    * @return 纯结构 映射，preprocessing 为 空 时返回空 映射
    */
    private static Map<String, Object> preprocessing(UebaConfig.Preprocessing preprocessing) {
        Map<String, Object> map = new LinkedHashMap<>(2);
        if (preprocessing == null) {
            return map;
        }
        Map<String, Object> scalers = new LinkedHashMap<>(16);
        if (preprocessing.getScalers() != null) {
            for (Map.Entry<String, UebaConfig.Scaler> entry : preprocessing.getScalers().entrySet()) {
                scalers.put(entry.getKey(), scaler(entry.getValue()));
            }
        }
        Map<String, Object> vocab = new LinkedHashMap<>(64);
        if (preprocessing.getVocab() != null) {
            for (Map.Entry<String, Integer> entry : preprocessing.getVocab().entrySet()) {
                vocab.put(entry.getKey(), entry.getValue());
            }
        }
        map.put("scalers", scalers);
        map.put("vocab", vocab);
        return map;
    }

    /**
    * 序列化归一化参数（仅写入非 空 字段）。
    *
    * @param scaler 归一化参数，允许为 空
    * @return 纯结构 映射，scaler 为 空 时返回空 映射
    */
    private static Map<String, Object> scaler(UebaConfig.Scaler scaler) {
        Map<String, Object> map = new LinkedHashMap<>(4);
        if (scaler == null) {
            return map;
        }
        if (scaler.getMin() != null) {
            map.put("min", scaler.getMin());
        }
        if (scaler.getMax() != null) {
            map.put("max", scaler.getMax());
        }
        if (scaler.getMean() != null) {
            map.put("mean", scaler.getMean());
        }
        if (scaler.getStd() != null) {
            map.put("std", scaler.getStd());
        }
        return map;
    }
}

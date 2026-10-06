package com.chua.deeplearning.support.weka.rf;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.task.classifier.ClassifierTask;
import com.chua.deeplearning.support.weka.data.FeatureColumn;
import com.chua.deeplearning.support.weka.data.WekaInstanceData;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Weka 随机森林分类单测。
 *
 * @author CH
 * @since 4.0.0.42
 */
class WekaRandomForestClassifierTest {

    /**
     * 特征列（数值 + 类别各一列）。
     */
    private static List<FeatureColumn> features() {
        return List.of(FeatureColumn.numeric("age"), FeatureColumn.categorical("city"));
    }

    /**
     * 构造分类样本（年龄区分 + 城市噪声，随机森林应可拟合）。
     *
     * @param count 每类样本数
     * @return 样本行
     */
    private static List<Map<String, Object>> samples(int count) {
        var cities = List.of("北京", "上海", "广州");
        var rows = new ArrayList<Map<String, Object>>(count * 2);
        for (var i = 0; i < count; i++) {
            var low = new LinkedHashMap<String, Object>();
            low.put("age", 20 + i % 10);
            low.put("city", cities.get(i % cities.size()));
            low.put("label", "low");
            rows.add(low);
            var high = new LinkedHashMap<String, Object>();
            high.put("age", 50 + i % 10);
            high.put("city", cities.get((i + 1) % cities.size()));
            high.put("label", "high");
            rows.add(high);
        }
        return rows;
    }

    @Test
    void trainPredictsTrainingSamples() {
        var rows = samples(15);
        var classifier = new WekaRandomForestClassifier();
        var model = classifier.train(WekaInstanceData.classification(features(), "label", rows),
                RandomForestOptions.defaults());
        assertNotNull(model);
        var hit = 0;
        for (var row : rows) {
            if (classifier.predict(model, row).label().equals(String.valueOf(row.get("label")))) {
                hit++;
            }
        }
        assertTrue(hit >= rows.size() * 0.8, "训练集命中 " + hit + "/" + rows.size());
    }

    @Test
    void batchPredictKeepsOrder() {
        var rows = samples(10);
        var classifier = new WekaRandomForestClassifier();
        var model = classifier.train(WekaInstanceData.classification(features(), "label", rows),
                RandomForestOptions.defaults());
        var results = classifier.predictBatch(model, rows);
        assertEquals(rows.size(), results.size());
        for (var i = 0; i < rows.size(); i++) {
            assertEquals(String.valueOf(rows.get(i).get("label")), results.get(i).label());
        }
    }

    @Test
    void spiResolvesWekaRandomForest() {
        var task = ServiceProvider.of(ClassifierTask.class).getExtension("weka-random-forest");
        assertNotNull(task);
        var model = task.train("label", samples(10));
        var result = model.predict(Map.of("age", 22, "city", "北京"));
        assertEquals("low", result.label());
    }

    @Test
    void saveAndLoadKeepsPredicting() throws Exception {
        var rows = samples(10);
        var classifier = new WekaRandomForestClassifier();
        var model = classifier.train(WekaInstanceData.classification(features(), "label", rows),
                RandomForestOptions.defaults());
        var row = Map.<String, Object>of("age", 55, "city", "上海");
        var before = classifier.predict(model, row).label();
        var file = Files.createTempFile("weka-rf", ".ser");
        try {
            model.save(file);
            var loaded = RandomForestModel.load(file);
            assertEquals(before, classifier.predict(loaded, row).label());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void evaluateReportsAccuracy() {
        var rows = samples(20);
        var classifier = new WekaRandomForestClassifier();
        var model = classifier.train(WekaInstanceData.classification(features(), "label", rows),
                RandomForestOptions.defaults());
        var report = classifier.evaluate(model,
                WekaInstanceData.classification(features(), "label", rows));
        assertEquals(rows.size(), report.numInstances());
        assertTrue(report.accuracyPct() >= 80.0, "准确率 " + report.accuracyPct());
    }
}

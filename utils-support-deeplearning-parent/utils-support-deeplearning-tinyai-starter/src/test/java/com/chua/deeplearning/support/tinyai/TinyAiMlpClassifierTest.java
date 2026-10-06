package com.chua.deeplearning.support.tinyai;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.task.classifier.ClassifierTask;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TinyAI MLP 分类单测。
 *
 * @author CH
 * @since 4.0.0.42
 */
class TinyAiMlpClassifierTest {

    /**
     * 构造二分类鸢尾式样本（数值特征线性可分，保证小参数可收敛）。
     *
     * @param count 每类样本数
     * @return 样本行
     */
    private static List<Map<String, Object>> samples(int count) {
        var rows = new ArrayList<Map<String, Object>>(count * 2);
        for (var i = 0; i < count; i++) {
            var low = new LinkedHashMap<String, Object>();
            low.put("x1", 0.1 + i * 0.01);
            low.put("x2", 0.2 + i * 0.01);
            low.put("label", "low");
            rows.add(low);
            var high = new LinkedHashMap<String, Object>();
            high.put("x1", 0.9 - i * 0.01);
            high.put("x2", 0.8 - i * 0.01);
            high.put("label", "high");
            rows.add(high);
        }
        return rows;
    }

    @Test
    void domainEncodesNumericAndLabels() {
        var domain = ModelDomain.of(samples(4), "label");
        assertEquals(2, domain.inputSize());
        assertEquals(List.of("low", "high"), domain.labels());
        var vector = domain.encode(Map.of("x1", 0.5, "x2", 0.5));
        assertEquals(2, vector.length);
    }

    @Test
    void trainPredictsTrainingSamples() {
        var rows = samples(10);
        var domain = ModelDomain.of(rows, "label");
        var classifier = new TinyAiMlpClassifier();
        var model = classifier.train(domain, rows, MlpOptions.testDefaults());
        assertNotNull(model);
        var hit = 0;
        for (var row : rows) {
            if (classifier.predict(model, row).label().equals(String.valueOf(row.get("label")))) {
                hit++;
            }
        }
        // 小参数 20 轮在该线性可分集上应基本拟合（>=80% 即过）。
        assertTrue(hit >= rows.size() * 0.8, "训练集命中 " + hit + "/" + rows.size());
    }

    @Test
    void spiResolvesTinyAiMlp() {
        var task = ServiceProvider.of(ClassifierTask.class).getExtension("tinyai-mlp");
        assertNotNull(task);
        var model = task.train("label", samples(10));
        var result = model.predict(Map.of("x1", 0.15, "x2", 0.25));
        assertEquals("low", result.label());
    }

    @Test
    void saveAndLoadKeepsPredicting() throws Exception {
        var rows = samples(10);
        var domain = ModelDomain.of(rows, "label");
        var classifier = new TinyAiMlpClassifier();
        var model = classifier.train(domain, rows, MlpOptions.testDefaults());
        var before = classifier.predict(model, Map.of("x1", 0.15, "x2", 0.25)).label();
        var file = Files.createTempFile("tinyai-mlp", ".ser");
        try {
            model.save(file);
            var loaded = TinyAiMlpModel.load(file);
            assertEquals(before, classifier.predict(loaded, Map.of("x1", 0.15, "x2", 0.25)).label());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void crossValidateRuns() {
        var rows = samples(10);
        var domain = ModelDomain.of(rows, "label");
        var report = new TinyAiMlpClassifier().crossValidate(domain, rows, MlpOptions.testDefaults(), 2);
        assertEquals(2, report.numFolds());
        assertTrue(report.accuracyPct() >= 0 && report.accuracyPct() <= 100.0);
    }

    @Test
    void finetuneKeepsPredicting() {
        var rows = samples(10);
        var domain = ModelDomain.of(rows, "label");
        var classifier = new TinyAiMlpClassifier();
        var model = classifier.train(domain, rows, MlpOptions.testDefaults());
        var before = classifier.evaluate(model, rows).accuracyPct();
        classifier.finetune(model, rows, MlpOptions.testDefaults());
        var after = classifier.evaluate(model, rows).accuracyPct();
        // 增量训练后在同分布上不应退化（允许小幅波动，阈值放宽到 -20）。
        assertTrue(after >= before - 20.0, "微调前后准确率 " + before + " -> " + after);
    }

    @Test
    void finetuneRejectsUnseenLabel() {
        var rows = samples(4);
        var domain = ModelDomain.of(rows, "label");
        var classifier = new TinyAiMlpClassifier();
        var model = classifier.train(domain, rows, MlpOptions.testDefaults());
        var bad = new LinkedHashMap<String, Object>();
        bad.put("x1", 0.5);
        bad.put("x2", 0.5);
        bad.put("label", "unknown-class");
        try {
            classifier.finetune(model, List.of(bad), MlpOptions.testDefaults());
            throw new AssertionError("未见标签应抛错");
        } catch (TinyAiException e) {
            assertTrue(e.getMessage().contains("未在数据域中"));
        }
    }

    @Test
    void trainDataBuilderTrains() {
        var data = TinyAiTrainData.classification("label")
                .numeric("x1").numeric("x2")
                .rows(samples(10))
                .build();
        var model = new TinyAiMlpClassifier().train(data, MlpOptions.testDefaults());
        assertNotNull(model);
        assertEquals(List.of("low", "high"), model.getDomain().labels());
    }

    @Test
    void trainDataFromJsonTrains() {
        var data = TinyAiTrainData.fromJson("label",
                "[{\"x1\":0.1,\"x2\":0.2,\"label\":\"low\"},"
                        + "{\"x1\":0.9,\"x2\":0.8,\"label\":\"high\"},"
                        + "{\"x1\":0.15,\"x2\":0.25,\"label\":\"low\"},"
                        + "{\"x1\":0.85,\"x2\":0.75,\"label\":\"high\"}]");
        var model = new TinyAiMlpClassifier().train(data, MlpOptions.testDefaults());
        assertNotNull(model);
        assertEquals(2, model.getDomain().inputSize());
    }

    @Test
    void trainDataInferCategorical() {
        var rows = new ArrayList<Map<String, Object>>();
        var low = new LinkedHashMap<String, Object>();
        low.put("city", "北京");
        low.put("label", "low");
        rows.add(low);
        var high = new LinkedHashMap<String, Object>();
        high.put("city", "上海");
        high.put("label", "high");
        rows.add(high);
        var data = TinyAiTrainData.infer("label", rows);
        assertEquals(1, data.features().size());
        assertTrue(!data.features().get(0).isNumeric());
    }

    @Test
    void finetuneWithTrainData() {
        var data = TinyAiTrainData.classification("label")
                .numeric("x1").numeric("x2")
                .rows(samples(10))
                .build();
        var classifier = new TinyAiMlpClassifier();
        var model = classifier.train(data, MlpOptions.testDefaults());
        var delta = TinyAiTrainData.classification("label")
                .numeric("x1").numeric("x2")
                .rows(samples(4))
                .build();
        var before = classifier.evaluate(model, data).accuracyPct();
        classifier.finetune(model, delta, MlpOptions.testDefaults());
        var after = classifier.evaluate(model, data).accuracyPct();
        assertTrue(after >= before - 20.0, "微调前后准确率 " + before + " -> " + after);
    }

    @Test
    void finetuneWithTrainDataRejectsSchemaMismatch() {
        var data = TinyAiTrainData.classification("label")
                .numeric("x1").numeric("x2")
                .rows(samples(4))
                .build();
        var classifier = new TinyAiMlpClassifier();
        var model = classifier.train(data, MlpOptions.testDefaults());
        var bad = TinyAiTrainData.classification("other")
                .numeric("x1").numeric("x2")
                .addRow(Map.of("x1", 0.5, "x2", 0.5, "other", "low"))
                .addRow(Map.of("x1", 0.6, "x2", 0.6, "other", "high"))
                .build();
        try {
            classifier.finetune(model, bad, MlpOptions.testDefaults());
            throw new AssertionError("标签列不一致应抛错");
        } catch (TinyAiException e) {
            assertTrue(e.getMessage().contains("标签列"));
        }
    }
}

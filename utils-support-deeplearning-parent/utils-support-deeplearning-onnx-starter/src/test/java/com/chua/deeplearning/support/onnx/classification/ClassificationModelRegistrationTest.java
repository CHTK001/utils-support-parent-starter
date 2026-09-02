package com.chua.deeplearning.support.onnx.classification;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.image.ImageClassifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 水果/动物/食物分类器注册测试。
 *
 * @author CH
 * @since 4.0.0.47
 */
class ClassificationModelRegistrationTest {

    static {
        try {
            Class.forName("com.chua.deeplearning.support.onnx.OnnxModelRegistrar");
        } catch (ClassNotFoundException ignored) {
        }
    }

    @Test
    void fruitClassificationRegistered() {
        var entry = ModelRegistry.get("fruit-classification");
        assertNotNull(entry, "fruit-classification should be registered");
        assertEquals("com.chua.deeplearning.support.onnx.classification.FruitClassificationTranslator",
                entry.translatorClassName());
        assertEquals(ImageClassifier.class, entry.capabilityInterface());
    }

    @Test
    void dogClassificationRegistered() {
        var entry = ModelRegistry.get("dog-classification");
        assertNotNull(entry, "dog-classification should be registered");
        assertEquals("com.chua.deeplearning.support.onnx.classification.DogClassificationTranslator",
                entry.translatorClassName());
        assertEquals(ImageClassifier.class, entry.capabilityInterface());
    }

    @Test
    void catClassificationRegistered() {
        var entry = ModelRegistry.get("cat-classification");
        assertNotNull(entry, "cat-classification should be registered");
        assertEquals("com.chua.deeplearning.support.onnx.classification.CatClassificationTranslator",
                entry.translatorClassName());
        assertEquals(ImageClassifier.class, entry.capabilityInterface());
    }

    @Test
    void food101ClassificationRegistered() {
        var entry = ModelRegistry.get("food-101-classification");
        assertNotNull(entry, "food-101-classification should be registered");
        assertEquals("com.chua.deeplearning.support.onnx.classification.SiglipZeroShotClassificationTranslator",
                entry.translatorClassName());
        assertEquals(ImageClassifier.class, entry.capabilityInterface());
    }

    @Test
    void listModelsContainsNewClassifiers() {
        List<String> models = ImageClassifier.listModels();
        assertTrue(models.contains("fruit-classification"), "models should contain fruit-classification");
        assertTrue(models.contains("dog-classification"), "models should contain dog-classification");
        assertTrue(models.contains("cat-classification"), "models should contain cat-classification");
        assertTrue(models.contains("food-101-classification"), "models should contain food-101-classification");
    }
}

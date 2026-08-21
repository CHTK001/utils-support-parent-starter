package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.image.ImageClassifier;
import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.image.ImageSegmenter;

public class ZeroShotDebugTest {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Debug: Loading OnnxModelRegistrar ===");
        Class.forName("com.chua.deeplearning.support.onnx.OnnxModelRegistrar");
        System.out.println("=== Debug: OnnxModelRegistrar loaded ===");
        
        System.out.println("\n=== All registered models ===");
        var all = ModelRegistry.getAll();
        System.out.println("Total: " + all.size());
        
        System.out.println("\n=== ImageClassifier models ===");
        var classifiers = ImageClassifier.listModels();
        System.out.println("Count: " + classifiers.size());
        classifiers.forEach(id -> System.out.println("  - " + id));
        
        System.out.println("\n=== ImageDetector models ===");
        var detectors = ImageDetector.listModels();
        System.out.println("Count: " + detectors.size());
        detectors.forEach(id -> System.out.println("  - " + id));
        
        System.out.println("\n=== ImageSegmenter models ===");
        var segmenters = ImageSegmenter.listModels();
        System.out.println("Count: " + segmenters.size());
        segmenters.forEach(id -> System.out.println("  - " + id));
    }
}

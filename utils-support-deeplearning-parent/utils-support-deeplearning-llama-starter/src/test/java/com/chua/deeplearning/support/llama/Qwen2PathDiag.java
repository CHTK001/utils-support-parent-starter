package com.chua.deeplearning.support.llama;

import com.chua.deeplearning.support.engine.ModelRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Qwen2PathDiag {

    private Qwen2PathDiag() {
    }

    public static void main(String[] args) {
        ModelRegistry.discoverAll();
        try {
            // 反射拿 extractRoot/modelRootDir
            var extract = com.chua.deeplearning.support.engine.ModelRegistry.class
                    .getDeclaredField("extractRoot");
            extract.setAccessible(true);
            Object er = extract.get(null);
            System.out.println("extractRoot = " + er);

            var rootField = com.chua.deeplearning.support.engine.ModelRegistry.class
                    .getDeclaredField("modelRootDir");
            rootField.setAccessible(true);
            System.out.println("modelRootDir = " + rootField.get(null));

            System.out.println("java.io.tmpdir = " + System.getProperty("java.io.tmpdir"));
            System.out.println("cache-dir prop = " + System.getProperty("deeplearning.model.cache-dir"));

            // 缓存文件是否存在
            Path cache = Paths.get("C:/Users/Administrator/AppData/Local/Temp/chua-dl-models/download/qwen2-0.5b/qwen2.5-0.5b-instruct-q4_k_m.gguf");
            System.out.println("缓存文件存在 = " + Files.exists(cache) + " size=" + (Files.exists(cache) ? Files.size(cache) : 0));

            Path p = ModelRegistry.resolveModelPath("qwen2-0.5b");
            System.out.println("resolveModelPath = " + p);
            System.out.println("存在 = " + (p != null && Files.exists(p)));

            var e = ModelRegistry.get("qwen2-0.5b");
            System.out.println("entry.relPath   = " + (e == null ? "null" : e.relativePath()));
            System.out.println("entry.dlUrl    = " + (e == null ? "null" : e.downloadUrl()));
            System.out.println("entry.dlFile   = " + (e == null ? "null" : e.downloadFileName()));

            // 反射调用 tryDownloadFromRemote 观察结果
            var m = ModelRegistry.class.getDeclaredMethod("tryDownloadFromRemote", String.class, Class.forName("com.chua.deeplearning.support.engine.ModelRegistry$Entry"));
            m.setAccessible(true);
            Object dl = m.invoke(null, "qwen2-0.5b", e);
            System.out.println("tryDownloadFromRemote = " + dl);
        } catch (Exception e) {
            System.out.println("DIAG FAIL: " + e);
        }
    }
}
package com.chua.deeplearning.support.onnx.face;

import com.chua.deeplearning.support.engine.ModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Classpath JAR 解压验证：模拟 fat-jar 运行环境（jar: 协议），
 * 验证 {@link ModelRegistry#resolveClasspathResource(String)} 能从嵌套 model jar 中
 * 解压 onnx 到 {@code java.io.tmpdir/chua-dl-models/}。
 *
 * <p>使用反射调用 ModelRegistry 静态方法，避免直接依赖内部 API。</p>
 *
 * @author CH
 * @since 2026-08-08
 */
public class ClasspathExtractionTest {

    private static final Logger log = LoggerFactory.getLogger(ClasspathExtractionTest.class);

    public static void main(String[] args) throws Exception {
        Path m2 = Paths.get(System.getProperty("user.home"), ".m2", "repository",
                "com", "chua");
        Path detectJar = m2.resolve("utils-support-models-faceplugin-detect/4.0.0.42/utils-support-models-faceplugin-detect-4.0.0.42.jar");
        Path landmarkJar = m2.resolve("utils-support-models-faceplugin-landmark/4.0.0.42/utils-support-models-faceplugin-landmark-4.0.0.42.jar");
        Path featureJar = m2.resolve("utils-support-models-faceplugin-feature/4.0.0.42/utils-support-models-faceplugin-feature-4.0.0.42.jar");

        for (Path p : new Path[]{detectJar, landmarkJar, featureJar}) {
            if (!Files.exists(p)) {
                throw new IllegalStateException("Model jar missing in m2: " + p);
            }
        }

        URL[] urls = {detectJar.toUri().toURL(), landmarkJar.toUri().toURL(), featureJar.toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(urls, Thread.currentThread().getContextClassLoader())) {
            URL res = loader.getResource("models/onnx/face/detection/faceplugin/face_detect_slim.onnx");
            if (res == null) {
                throw new IllegalStateException("resource not found in jar: " + detectJar);
            }
            log.info("模型资源 URL 协议: {} -> {}", res.getProtocol(), res);
            if (!"jar".equalsIgnoreCase(res.getProtocol())) {
                throw new IllegalStateException("expected jar: protocol, got " + res.getProtocol());
            }

            Thread.currentThread().setContextClassLoader(loader);
            Method m = ModelRegistry.class.getMethod("resolveClasspathResource", String.class);
            Path extracted = (Path) m.invoke(null, "models/onnx/face/detection/faceplugin/face_detect_slim.onnx");

            if (extracted == null || !Files.exists(extracted)) {
                throw new IllegalStateException("extracted path missing: " + extracted);
            }
            long size = Files.size(extracted);
            log.info("解压后路径: {} ({} bytes)", extracted, size);
            if (size < 1_000_000) {
                throw new IllegalStateException("extracted file too small: " + size);
            }

            String tmpRoot = new File(System.getProperty("java.io.tmpdir"), "chua-dl-models").getAbsolutePath();
            String got = extracted.toAbsolutePath().toString();
            String alt = tmpRoot.replace(File.separatorChar, '/');
            String gotNorm = got.replace(File.separatorChar, '/');
            if (!gotNorm.startsWith(alt)) {
                throw new IllegalStateException("extracted path " + gotNorm + " not under " + alt);
            }
            log.info("OK: 解压位置符合 java.io.tmpdir/chua-dl-models/");

            Path extracted2 = (Path) m.invoke(null, "models/onnx/face/detection/faceplugin/face_detect_slim.onnx");
            if (!extracted.equals(extracted2)) {
                throw new IllegalStateException("缓存未命中: " + extracted + " vs " + extracted2);
            }
            log.info("OK: 缓存命中，第二次返回同一 Path");
        }
        log.info("ALL PASS");
    }
}

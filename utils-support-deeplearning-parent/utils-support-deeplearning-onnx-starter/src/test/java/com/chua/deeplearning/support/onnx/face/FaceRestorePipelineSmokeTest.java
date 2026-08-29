package com.chua.deeplearning.support.onnx.face;

import com.chua.deeplearning.support.face.FacePipeline;
import com.chua.deeplearning.support.face.FacePipelineDiskCallback;
import com.chua.deeplearning.support.face.FaceRestoreResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 人脸修复管线端到端冒烟测试：检测 → 5点对齐 → GFPGAN 修复。
 *
 * <p>默认跳过（GFPGAN onnx 权重约 636MB、推理耗时），显式执行：
 * {@code mvn test -Dtest=FaceRestorePipelineSmokeTest -Dface.restore.it=true}</p>
 */
class FaceRestorePipelineSmokeTest {

    @Test
    void restoreWithAlign() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Boolean.getBoolean("face.restore.it"), "未开启 -Dface.restore.it=true，跳过");

        byte[] img = Files.readAllBytes(Path.of("G:\\images\\三个人.jpg"));
        Path out = Path.of("D:\\images\\output\\face_pipeline_test");

        FacePipeline pipeline = FacePipeline.builder()
                .detector("scrfd-face-detector")
                .restore("onnx-gfpgan")
                .build();
        pipeline.setCallback(new FacePipelineDiskCallback(out));

        List<FaceRestoreResult> results = pipeline.restoreWithAlign(img);

        org.junit.jupiter.api.Assertions.assertFalse(results.isEmpty(), "未检出人脸");
        System.out.printf("[face-restore-smoke] 检出人脸=%d, 输出目录=%s%n", results.size(), out);
        for (int i = 0; i < results.size(); i++) {
            FaceRestoreResult r = results.get(i);
            System.out.printf("  face[%d] box=(%.0f,%.0f) %.0fx%.0f conf=%.2f%n",
                    i, r.box().x(), r.box().y(), r.box().width(), r.box().height(), r.box().confidence());
        }
    }
}
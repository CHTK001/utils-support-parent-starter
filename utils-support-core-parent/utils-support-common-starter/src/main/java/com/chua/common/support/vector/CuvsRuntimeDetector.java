package com.chua.common.support.vector;

import com.chua.common.support.reflection.ReflectUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * cuVS GPU 环境检测器。
 *
 * <p>通过反射尝试创建 {@code com.nvidia.cuvs.CuVSResources} 来判断 GPU 是否可用。
 * cuVS native 库不存在或 CUDA Driver 不可用时返回 false。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see RuntimeDetector
 */
@Slf4j
public class CuvsRuntimeDetector implements RuntimeDetector {

    @Override
    public String name() {
        return "cuvs";
    }

    @Override
    public boolean isAvailable() {
        if (!ReflectUtils.isPresent("com.nvidia.cuvs.CuVSResources")) {
            return false;
        }
        try {
            Object resources = ReflectUtils.invokeStatic("com.nvidia.cuvs.CuVSResources",
                    "create", Object.class);
            try {
                int deviceId = (int) ReflectUtils.invoke(resources, "deviceId", int.class);
                log.info("[vector-runtime] cuVS GPU detected, device: {}", deviceId);
                return true;
            } finally {
                try {
                    ReflectUtils.invoke(resources, "close", Object.class);
                } catch (Throwable ignored) {
                    log.debug("[vector-runtime] Failed to close CuVSResources during detection", ignored);
                }
            }
        } catch (Throwable t) {
            log.debug("[vector-runtime] cuVS unavailable: {} ({})", t.getMessage(), t.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public int priority() {
        return 100;
    }
}

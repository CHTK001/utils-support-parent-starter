package com.chua.deeplearning.support.engine;

import com.chua.deeplearning.support.capability.ModelCapabilities;
import com.chua.deeplearning.support.model.HardwareConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
* 模型推荐选择器。
*
* <p>根据当前服务器硬件配置，从 {@link ModelRegistry} 中为该能力类型挑选推荐模型：</p>
* <ul>
*   <li>{@code auto} 设备策略下先探测本机 GPU；GPU 可用时从显存满足要求的
*       {@code recommended} 模型中挑选，否则回退 CPU（直接取第一个推荐配置）；</li>
*   <li>显式 {@code cpu} 时直接取第一个推荐模型；</li>
*   <li>显式 {@code gpu}/{@code cuda} 时从显存满足要求的推荐模型中挑选。</li>
* </ul>
*
* <p>同一能力类型可注册多个推荐模型，由本选择器在运行时按服务器配置落地。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public final class ModelSelector {

    /**
    * 模型selector。
     */
    private ModelSelector() {
    }

    /**
    * 按能力接口挑选推荐模型。
    *
    * @param capabilityInterface 能力接口（如 镜像detector.类、facedetector.类）
    * @param deviceSetting       设备设置：auto / cpu / gpu / cuda，可为 空
    * @return 推荐模型 标识；无可用模型返回 空
     */
    public static String selectRecommended(Class<?> capabilityInterface, String deviceSetting) {
        return selectRecommended(ModelRegistry.getModelIdsByCapability(capabilityInterface), deviceSetting);
    }

    /**
    * 按能力标签挑选推荐模型。
    *
    * @param capability    能力标签（见 {@link ModelCapabilities}）
    * @param deviceSetting 设备设置：auto / cpu / gpu / cuda，可为 空
    * @return 推荐模型 标识；无可用模型返回 空
     */
    public static String selectRecommended(String capability, String deviceSetting) {
        if (capability == null || capability.isBlank()) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        for (ModelRegistry.Entry e : ModelRegistry.getAll()) {
            String label = ModelCapabilities.labelOf(e.capabilityInterface());
            if (label != null && label.equalsIgnoreCase(capability)) {
                candidates.add(e.modelId());
            }
        }
        return selectRecommended(candidates, deviceSetting);
    }

    /**
    * 从候选模型 标识 列表中挑选推荐模型。
    *
    * <p>策略：优先候选列表中的 {@code recommended=true} 且硬件配置满足当前设备的模型；
    * 无推荐条目时退化为列表第一个；CPU 设备直接取第一个推荐条目。</p>
    *
    * @param candidates    候选模型 标识 列表（同一能力类型）
    * @param deviceSetting 设备设置：auto / cpu / gpu / cuda，可为 空
    * @return 选中的模型 标识；候选为空返回 空
     */
    public static String selectRecommended(List<String> candidates, String deviceSetting) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        String device = DeviceSelector.resolve(deviceSetting);
        boolean gpu = "gpu".equals(device);
        long vramMb = gpu ? DeviceSelector.gpuTotalVramMb() : -1;

        // 阶段1（仅 GPU）：GPU 型推荐模型，且显存满足要求（上限判断）
        if (gpu) {
            for (String id : candidates) {
                HardwareConfig hw = hwOf(id);
                if (hw != null && hw.recommended() && hw.isGpu()
                        && (hw.minVramMb() <= 0 || vramMb < 0 || hw.minVramMb() <= vramMb)) {
                    log.debug("[deeplearning-engine] ModelSelector GPU 推荐选中: {} (device={}, vram={}MB)", id, device, vramMb);
                    return id;
                }
            }
            // 阶段2（GPU 显存不足或无可选 GPU 模型）：兜底 CPU/auto 型推荐
        }
        for (String id : candidates) {
            HardwareConfig hw = hwOf(id);
            if (hw != null && hw.recommended() && !hw.isGpu()) {
                log.debug("[deeplearning-engine] ModelSelector CPU 推荐选中: {} (device={}, vram={}MB)", id, device, vramMb);
                return id;
            }
        }
        // 阶段3：无推荐命中，退化候选列表第一个（注册顺序）
        String fallback = candidates.get(0);
        log.debug("[deeplearning-engine] ModelSelector 无推荐命中，退化首选: {} (device={}, vram={}MB)", fallback, device, vramMb);
        return fallback;
    }

    /**
    * hw的。
    * @param modelId 模型标识
    * @return hw的的结果
     */
    private static HardwareConfig hwOf(String modelId) {
        ModelRegistry.Entry entry = ModelRegistry.get(modelId);
        return entry == null ? null : entry.hardwareConfig();
    }

    /**
    * 解析实际使用的设备（门面统一入口）。
    *
    * @param deviceSetting 设备设置：auto / cpu / gpu / cuda，可为 空
    * @return "gpu" 或 "cpu"
     */
    public static String resolveDevice(String deviceSetting) {
        return DeviceSelector.resolve(deviceSetting);
    }
}
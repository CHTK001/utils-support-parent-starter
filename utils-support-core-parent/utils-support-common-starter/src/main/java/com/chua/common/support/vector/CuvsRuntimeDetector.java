package com.chua.common.support.vector;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.utils.ClassUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
* cuvs GPU 环境检测器，按层级检测 NVIDIA 驱动、CUDA Toolkit、GPU 设备可用性。
*
* <p>检测流程（逐级失败则停止并打印安装指引）：
* <ol>
*   <li>检查 cuVS Java API 是否在 classpath 中（{@code com.nvidia.cuvs.*}）</li>
*   <li>检查 NVIDIA 驱动是否安装（Windows: registry；Linux: {@code /proc/driver/nvidia}）</li>
*   <li>检查 CUDA Driver API 是否可用（尝试加载 nvcuda.dll / libcuda.so）</li>
*   <li>检查 GPU 设备是否可访问（nvidia-smi 或 CUDA runtime）</li>
*   <li>尝试创建 {@code CuVSResources}（验证 cuVS native 库可用性）</li>
* </ol>
* </p>
*
* <p>失败时会在日志中打印详细的安装指引。</p>
*
* @author CH
* @since 4.0.0.42
* @see RuntimeDetector
 */
@Slf4j
public class CuvsRuntimeDetector implements RuntimeDetector {

    /** cuvs Java API 类名前缀，用于检测包是否已加载 */
    private static final String CUVS_PACKAGE_PREFIX = "com.nvidia.cuvs.";

    @Override
    public String name() {
        return "cuvs";
    }

    @Override
    public boolean isAvailable() {
 // Step 1: cuvs Java API 是否在 类路径
        if (!checkCuvsApiPresent()) {
            log.warn("[vector-runtime] cuVS Java API not found in classpath. "
                    + "Install via: conda install -c rapidsai -c conda-forge libcuvs cuda-version=12.9\n"
                    + "  Or build from source: git clone https://github.com/rapidsai/cuvs && cd cuvs/java && ./build.sh");
            return false;
        }

        // Step 2: NVIDIA 驱动是否安装
        if (!checkNvidiaDriver()) {
            log.warn("[vector-runtime] NVIDIA driver not detected. Install the latest driver from:\n"
                    + "  Windows: https://www.nvidia.com/Download/index.aspx\n"
                    + "  Linux:   sudo apt-get install nvidia-driver\n"
                    + "  Required: CUDA Driver API compatible with CUDA 12.x");
            return false;
        }

        // Step 3: 是否有可用的 GPU 设备
        List<String> gpuList = detectGpus();
        if (gpuList.isEmpty()) {
            log.warn("[vector-runtime] No accessible GPU devices found. Run 'nvidia-smi' to check GPU status.\n"
                    + "  Possible causes:\n"
                    + "    - GPU is not supported by CUDA 12.x (requires Compute Capability 7.0+)\n"
                    + "    - GPU is in use by another process\n"
                    + "    - CUDA context initialization failed");
            return false;
        }

 // Step 4: 创建 cuvsresources 验证 cuvs NAT 库
        try {
            Object resources = ReflectUtils.invokeStatic("com.nvidia.cuvs.CuVSResources",
                    "create", Object.class);
            try {
                int deviceId = (int) ReflectUtils.invoke(resources, "deviceId", int.class);
                log.info("[vector-runtime] cuVS GPU ready, device: {} ({})",
                        deviceId, gpuList.get(Math.min(deviceId, gpuList.size() - 1)));
                return true;
            } finally {
                try {
                    ReflectUtils.invoke(resources, "close", Object.class);
                } catch (Throwable ignored) {
                    log.debug("[vector-runtime] Cleanup CuVSResources failed", ignored);
                }
            }
        } catch (Throwable t) {
            log.warn("[vector-runtime] cuVS native library failed to initialize: {} ({})\n"
                    + "  Ensure libcuvs and libcuvs_c are on your library path:\n"
                    + "    Windows: add cuvs/bin to PATH\n"
                    + "    Linux:   export LD_LIBRARY_PATH=$LD_LIBRARY_PATH:<cuvs-install>/lib",
                    t.getMessage(), t.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public int priority() {
        return 100;
    }

    // ==================== 内部检测方法 ====================

    /**
    * 检查 cuvs Java API 是否在 类路径 中。
    * @return 检查cuvsapipresent的结果
     */
    private boolean checkCuvsApiPresent() {
        // 通过反射尝试加载核心类来判断
        String[] testClasses = {
                "com.nvidia.cuvs.CuVSResources",
                "com.nvidia.cuvs.CagraIndex",
                "com.nvidia.cuvs.CuvsDistanceType"
        };
        for (String cls : testClasses) {
            if (!ClassUtils.isPresent(cls)) {
                return false;
            }
        }
        return true;
    }

    /**
    * 检查 NVIDIA 驱动是否安装。
    *
    * <p>Windows：查询注册表 HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Services\nvidia;
    * Linux：检查 /proc/driver/nvidia 是否存在。</p>
    * @return 检查nvidiadriver的结果
     */
    private boolean checkNvidiaDriver() {
 // 窗口 registry 降级
        if (isWindows() && checkNvidiaRegistry()) {
            return true;
        }
 // Linux /proc 降级
        if (isLinux() && Files.exists(Paths.get("/proc/driver/nvidia"))) {
            return true;
        }
        return false;
    }

    /**
    * 检测可用的 GPU 设备列表（通过 nvidia-smi）。
    * @return detectGpus的结果
     */
    private List<String> detectGpus() {
        List<String> gpus = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("nvidia-smi", "--query-gpu=name", "--format=csv,noheader");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean completed = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!completed) {
                p.destroyForcibly();
                log.debug("[vector-runtime] nvidia-smi timed out, no GPU");
                return gpus;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String gpu = line.trim();
                    if (!gpu.isEmpty()) {
                        gpus.add(gpu);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[vector-runtime] Failed to enumerate GPUs: {}", e.getMessage());
        }
        return gpus;
    }

    /**
    * Windows 注册表检查 NVIDIA 驱动。
    * @return 检查nvidiaregistry的结果
     */
    private boolean checkNvidiaRegistry() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "reg", "query",
                    "HKLM\\SYSTEM\\CurrentControlSet\\Services\\nvidia",
                    "/ve");
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
            pb.redirectError(ProcessBuilder.Redirect.PIPE);
             Process p = pb.start();
             boolean completed = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
             return completed && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private boolean isLinux() {
        return System.getProperty("os.name").toLowerCase().contains("linux");
    }
}

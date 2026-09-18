package com.chua.common.support.vector;

import com.chua.common.support.reflection.ReflectUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
/**
 * CUDA 运行时库环境检测器，检测 onnxruntime-gpu 所需的 CUDA 运行库是否就绪。
 *
 * <p>检测流程（逐级失败则停止并打印安装指引）：
 * <ol>
 *   <li>检查 onnxruntime_gpu 构件是否在 classpath（{@code ai.onnxruntime} 且含 CUDA provider）</li>
 *   <li>检查 NVIDIA 驱动是否安装（Windows: registry / nvidia-smi；Linux: /proc/driver/nvidia）</li>
 *   <li>检查 GPU 设备是否可访问（nvidia-smi）</li>
 *   <li>检查 CUDA 运行库（cudart/cublas/cudnn）是否可从 PATH 加载</li>
 * </ol>
 * </p>
 *
 * <p>CUDA 运行库缺失时可通过 {@code utils-support-native-cuda} 模块脚本自动安装：
 * <pre>
 *   Windows: scripts/setup-cuda.bat
 *   Linux:   scripts/setup-cuda.sh
 *   macOS:   scripts/setup-cuda-macos.sh（仅检测，NVIDIA 已停止 macOS CUDA 支持）
 * </pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see RuntimeDetector
*/
@Slf4j
public class CudaRuntimeDetector implements RuntimeDetector {

    /** onnxruntime 类名，用于检测 GPU 构件是否在 类路径 */
    private static final String ORT_GPU_CLASS = "ai.onnxruntime.OrtSession";

    @Override
    public String name() {
        return "cuda";
    }

    @Override
    public boolean isAvailable() {
 // Step 1: onnxruntime 是否在 类路径（gpu 配置文件 引入 onnxruntime_gpu）
        if (!isClassPresent(ORT_GPU_CLASS)) {
            log.warn("[cuda-runtime] onnxruntime not found in classpath. "
                    + "Enable GPU profile: mvn -Pgpu 或引入 com.microsoft.onnxruntime:onnxruntime_gpu");
            return false;
        }

        // Step 2: NVIDIA 驱动是否安装
        if (!checkNvidiaDriver()) {
            log.warn("[cuda-runtime] NVIDIA driver not detected. Install the latest driver from:\n"
                    + "  Windows: https://www.nvidia.com/Download/index.aspx\n"
                    + "  Linux:   sudo apt-get install nvidia-driver");
            return false;
        }

        // Step 3: 是否有可用的 GPU 设备
        List<String> gpuList = detectGpus();
        if (gpuList.isEmpty()) {
            log.warn("[cuda-runtime] No accessible GPU devices found. Run 'nvidia-smi' to check GPU status.");
            return false;
        }

 // Step 4: CUDA 运行库是否可从 路径 加载
        if (!checkCudaRuntimeLibraries()) {
            log.warn("[cuda-runtime] CUDA 运行库 (cudart/cublas/cudnn) 未就绪。\n"
                    + "  请先执行 utils-support-native-cuda 模块脚本一键安装:\n"
                    + "    Windows: scripts/setup-cuda.bat\n"
                    + "    Linux:   scripts/setup-cuda.sh\n"
                    + "  或将 CUDA 库目录加入 PATH (Windows) / LD_LIBRARY_PATH (Linux)");
            return false;
        }

        log.info("[cuda-runtime] CUDA 环境就绪, GPU: {}", gpuList);
        return true;
    }

    @Override
    public int priority() {
        return 200;
    }

    // ==================== 内部检测方法 ====================

    /**
    * 自包含的 类路径 类检测（避免依赖 类工具 编译产物）。
    *
    * @param className 全限定类名
    * @return true 表示类可加载
    */
    private static boolean isClassPresent(String className) {
        try {
            ReflectUtils.forName(className, CudaRuntimeDetector.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
    * 检查 NVIDIA 驱动是否安装。
    *
    * <p>Windows：查询注册表 HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Services\nvidia;
    * Linux：检查 /proc/driver/nvidia 是否存在。</p>
    * @return 检查nvidiadriver的结果
    */
    private boolean checkNvidiaDriver() {
        if (isWindows() && checkNvidiaRegistry()) {
            return true;
        }
        if (isLinux() && Files.exists(Paths.get("/proc/driver/nvidia"))) {
            return true;
        }
        // 兜底：nvidia-smi 可用即认为驱动就绪
        return !detectGpus().isEmpty();
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
                log.debug("[cuda-runtime] nvidia-smi timed out, no GPU");
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
            log.debug("[cuda-runtime] Failed to enumerate GPUs: {}", e.getMessage());
        }
        return gpus;
    }

    /**
    * 检查 CUDA 运行库（cudart/cublas/cudnn）是否可从系统库路径加载。
    *
    * <p>不做完整加载（避免副作用），仅检查关键 DLL/.so 是否存在于
    * 路径 可搜索目录（窗口）或常见库目录（Linux）。</p>
    * @return 检查cudaruntime图书馆的结果
    */
    private boolean checkCudaRuntimeLibraries() {
        String cudaMajor = System.getProperty("cuda.major", "12");
        String[] libs;
        if (isWindows()) {
            libs = new String[]{"cudart64_" + cudaMajor + ".dll", "cublas64_" + cudaMajor + ".dll", "cudnn64_9.dll"};
        } else if (isLinux()) {
            libs = new String[]{"libcudart.so." + cudaMajor, "libcublas.so." + cudaMajor, "libcudnn.so.9"};
        } else {
            // macOS：NVIDIA 已停止 CUDA 支持，视为不满足
            return false;
        }

 // 搜索 路径 目录（窗口）与常见库目录（Linux）
        List<Path> searchDirs = new ArrayList<>();
        String pathEnv = System.getenv(isWindows() ? "PATH" : "LD_LIBRARY_PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(isWindows() ? ";" : ":")) {
                if (!dir.isBlank()) {
                    searchDirs.add(Path.of(dir));
                }
            }
        }
        if (isLinux()) {
            searchDirs.addAll(List.of(
                    Path.of("/usr/lib/x86_64-linux-gnu"),
                    Path.of("/usr/local/cuda/lib64"),
                    Path.of("/usr/lib")
            ));
        }

        int found = 0;
        for (String lib : libs) {
            boolean ok = false;
            for (Path dir : searchDirs) {
                if (Files.exists(dir.resolve(lib))) {
                    ok = true;
                    break;
                }
            }
            if (ok) {
                found++;
            } else {
                log.debug("[cuda-runtime] 缺失运行库: {}", lib);
            }
        }
        boolean ready = found == libs.length;
        if (!ready) {
            log.warn("[cuda-runtime] CUDA 运行库不全: 找到 {}/{} (cuda.major={})",
                    found, libs.length, cudaMajor);
        }
        return ready;
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

    /**
     * 是否Windows。
     *
     * @return 是否成功（true 表示成功）
     */
    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * 是否Linux。
     *
     * @return 是否成功（true 表示成功）
     */
    private boolean isLinux() {
        return System.getProperty("os.name").toLowerCase().contains("linux");
    }
}

package com.chua.ibd.support;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
* Python 脚本执行器
*
* <p>通过系统 Python 解释器执行 Python 脚本。
* 支持传入上下文参数，脚本通过命令行参数或环境变量获取。
*
* @author CH
* @since 4.0.0.42
 */
public class PythonScriptExecutor implements ScriptExecutor {

    /**
    * Python 解释器路径
     */
    private final String pythonPath;

    /**
    * 执行超时（毫秒）
     */
    private final long timeoutMillis;

    /** 创建 pythonscript执行器 实例 */
    public PythonScriptExecutor() {
        this("python3");
    }

    /**
    * 创建 pythonscript执行器 实例
    * @param pythonPath Python路径
     */
    public PythonScriptExecutor(String pythonPath) {
        this(pythonPath, 60000);
    }

    /**
    * 创建 pythonscript执行器 实例
    * @param pythonPath Python路径
    * @param timeoutMillis long
    * @param timeoutMillis 超时millis
     */
    public PythonScriptExecutor(String pythonPath, long timeoutMillis) {
        this.pythonPath = pythonPath;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    /** 执行 */
    public String execute(Path scriptPath, Map<String, Object> context) {
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonPath, scriptPath.toString());

            // 设置上下文环境变量
            if (context != null) {
                for (Map.Entry<String, Object> entry : context.entrySet()) {
                    pb.environment().put("IBD_" + entry.getKey(),
                            entry.getValue() != null ? String.valueOf(entry.getValue()) : "");
                }
            }

            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 读取输出
            String output = new String(process.getInputStream().readAllBytes());

            // 等待完成
            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("Python 脚本执行超时: " + scriptPath);
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException("Python 脚本执行失败 (exit=" + exitCode + "): " + output);
            }

            return output.trim();
        } catch (IOException e) {
            throw new RuntimeException("Python 脚本执行异常: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Python 脚本执行被中断", e);
        }
    }

    @Override
    /** 获取延伸 */
    public String getExtension() {
        return ".py";
    }
}

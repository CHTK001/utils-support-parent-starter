package com.chua.common.support.task.script.process;

import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.task.script.ScriptProvider;
import com.chua.common.support.utils.StringUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 进程型脚本引擎基类：把脚本文件交给宿主解释器以子进程方式执行。
 *
 * <p>适用对象是「语言本身就以命令行解释器为运行形态」的脚本：Shell / PHP / Node.js /
 * PowerShell。它们没有可嵌入 JVM 的编译产物，硬塞嵌入式实现是给部署机加一层兼容性赌注；
 * 而「机器上装了 bash / php / node / powershell」是运维剪脚本时最常见的事实。</p>
 *
 * <p>与 {@code PythonScriptProvider} 的差别：Python 走 stdin/stdout 传上下文与结果，
 * 这里遵循各解释器的原生用法——{@code 解释器 [前置参数] <脚本文件> [执行参数]}，
 * 执行参数原样作为单个位置参数传入（不按空白切分，避免带空格的参数被撕开），
 * 脚本退出码非 0 即视为任务失败，标准输出作为执行结果返回。</p>
 *
 * <p>两类超时口径：解释器探测与语法检查固定 5 秒 / 30 秒，执行固定 30 秒后强制中断
 * （与 Python 引擎一致）。超时即 {@code destroyForcibly}，由
 * {@link CmdExecutors} 统一处理。</p>
 *
 * <p>保存期校验与执行期失败的取舍：解释器不存在时 {@link #loadScript(Path)}
 * <b>跳过</b>而不是报错——装没装引擎是部署问题，不该让调度中心保存任务失败；
 * 真正执行时再抛可读的「未找到解释器」。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public abstract class ProcessScriptProvider implements ScriptProvider {

    /**
     * 脚本执行与语法检查的默认超时（毫秒）
     */
    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    /**
     * 解释器探测超时（毫秒）
     */
    private static final long PROBE_TIMEOUT_MS = 5_000L;

    /**
     * 错误输出最多摘录的行数，避免整段堆栈灌进任务日志
     */
    private static final int STDERR_LINES = 6;

    /**
     * 执行上下文里执行参数所在的键（与 {@code ScriptGlueHandlers} 的绑定名一致）
     */
    private static final String EXECUTE_PARAM_KEY = "executeParam";

    /**
     * 已通过校验的脚本路径
     */
    private final Map<Path, Boolean> loaded = new ConcurrentHashMap<>();

    /**
     * 探测成功的解释器命令；{@code null} 表示尚未探测或本机未安装
     */
    private volatile String interpreter;

    /**
     * 解释器候选命令，按优先级排列（Windows 与各发行版命名不同，需要对多个候选做探测）。
     *
     * @return 候选命令数组
     */
    protected abstract String[] interpreterCandidates();

    /**
     * 脚本正文之前的固定参数，如 PowerShell 的 {@code -NoProfile -ExecutionPolicy Bypass -File}。
     *
     * @return 前置参数数组，默认空
     */
    protected String[] preArgs() {
        return new String[0];
    }

    /**
     * 解释器探测参数，默认 {@code --version}；不支持该开关的解释器需覆盖。
     *
     * @return 探测参数数组
     */
    protected String[] probeArgs() {
        return new String[]{"--version"};
    }

    /**
     * 语法检查参数（不含解释器与脚本路径）；返回 {@code null} 表示该引擎不做保存期语法检查。
     *
     * @param scriptPath 脚本路径
     * @return 语法检查参数数组或 {@code null}
     */
    protected String[] checkArgs(Path scriptPath) {
        return null;
    }

    /**
     * 加载脚本：能检查语法的引擎做一次语法检查，不执行脚本。
     *
     * <p>解释器不存在时跳过校验并返回 {@code true}，理由见类注释。</p>
     *
     * @param scriptPath 脚本路径
     * @return 校验通过（或本机无解释器而跳过）返回 {@code true}
     */
    @Override
    public boolean loadScript(Path scriptPath) {
        if (scriptPath == null) {
            throw new IllegalArgumentException(engineName() + " 脚本路径不能为 null");
        }
        String bin;
        try {
            bin = resolveInterpreter();
        } catch (IllegalStateException missing) {
            return true;
        }
        String[] check = checkArgs(scriptPath);
        if (check == null) {
            loaded.put(scriptPath, Boolean.TRUE);
            return true;
        }
        var command = new ArrayList<String>(check.length + 2);
        command.add(bin);
        for (String arg : check) {
            command.add(arg);
        }
        command.add(scriptPath.toString());
        CmdResult result = CmdExecutors.execute(command.toArray(new String[0]),
                DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (result.isTimeout()) {
            throw new IllegalStateException(engineName() + " 脚本语法检查超时: " + scriptPath);
        }
        if (!result.isSuccess()) {
            throw new IllegalStateException(engineName() + " 脚本语法检查失败（exit="
                    + result.getExitCode() + "）: " + scriptPath + "\n" + firstLines(result.getStderr()));
        }
        loaded.put(scriptPath, Boolean.TRUE);
        return true;
    }

    /**
     * 执行脚本：以子进程方式运行脚本文件。
     *
     * @param scriptPath 脚本路径
     * @param context    执行上下文（Map 时取 {@code executeParam} 作为位置参数）
     * @return 标准输出文本（脚本无输出时为空串）
     */
    @Override
    public Object executeScript(Path scriptPath, Object context) {
        if (scriptPath == null) {
            throw new IllegalArgumentException(engineName() + " 脚本路径不能为 null");
        }
        String bin = resolveInterpreter();
        var command = new ArrayList<String>();
        command.add(bin);
        for (String arg : preArgs()) {
            command.add(arg);
        }
        command.add(scriptPath.toString());
        String param = executeParamOf(context);
        if (!param.isEmpty()) {
            command.add(param);
        }
        CmdResult result = CmdExecutors.execute(command.toArray(new String[0]),
                DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (result.isTimeout()) {
            throw new IllegalStateException(engineName() + " 脚本执行超过 " + DEFAULT_TIMEOUT_MS
                    + "ms，已中断: " + scriptPath);
        }
        if (!result.isSuccess()) {
            throw new IllegalStateException(engineName() + " 脚本执行失败（exit="
                    + result.getExitCode() + "）: " + scriptPath + "\n" + firstLines(result.getStderr()));
        }
        String stdout = result.getStdout();
        return stdout == null ? "" : stdout;
    }

    /**
     * 卸载脚本：进程型引擎没有编译产物，清掉校验标记即可。
     *
     * @param scriptPath 脚本路径
     */
    @Override
    public void unloadScript(Path scriptPath) {
        if (scriptPath != null) {
            loaded.remove(scriptPath);
        }
    }

    /**
     * 是否已通过校验。
     *
     * @param scriptPath 脚本路径
     * @return 校验过返回 {@code true}
     */
    @Override
    public boolean isLoaded(Path scriptPath) {
        return scriptPath != null && loaded.containsKey(scriptPath);
    }

    /**
     * 取执行参数：上下文为 Map 时读 {@code executeParam}，其它形态一律视为无参数。
     *
     * @param context 执行上下文，允许为 null
     * @return 执行参数，无则空串
     */
    private static String executeParamOf(Object context) {
        if (context instanceof Map<?, ?> map) {
            Object value = map.get(EXECUTE_PARAM_KEY);
            return value == null ? "" : String.valueOf(value).trim();
        }
        return context == null ? "" : String.valueOf(context).trim();
    }

    /**
     * 探测并缓存本机可用的解释器命令。
     *
     * @return 解释器命令
     * @throws IllegalStateException 全部候选都不可用
     */
    private String resolveInterpreter() {
        String cached = interpreter;
        if (cached != null) {
            return cached;
        }
        var tried = new StringBuilder();
        String[] probes = probeArgs();
        for (String candidate : interpreterCandidates()) {
            var command = new ArrayList<String>(probes.length + 1);
            command.add(candidate);
            for (String arg : probes) {
                command.add(arg);
            }
            CmdResult probe = CmdExecutors.execute(command.toArray(new String[0]),
                    PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (probe.isSuccess()) {
                interpreter = candidate;
                return candidate;
            }
            if (tried.length() > 0) {
                tried.append(", ");
            }
            tried.append(candidate);
        }
        throw new IllegalStateException("未找到可用的 " + engineName() + " 解释器（尝试过: "
                + tried + "），请安装后加入 PATH");
    }

    /**
     * 取错误输出的前几行，避免把整段堆栈塞进任务日志。
     *
     * @param stderr 错误输出
     * @return 摘录文本
     */
    private static String firstLines(String stderr) {
        if (StringUtils.isBlank(stderr)) {
            return "（无错误输出）";
        }
        String[] lines = stderr.strip().split("\n");
        int count = Math.min(lines.length, STDERR_LINES);
        var builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(lines[i]);
        }
        if (lines.length > count) {
            builder.append("\n…（其余 ").append(lines.length - count).append(" 行已省略）");
        }
        return builder.toString();
    }
}

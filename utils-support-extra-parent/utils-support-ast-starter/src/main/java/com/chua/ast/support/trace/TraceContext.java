package com.chua.ast.support.trace;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 链路追踪上下文 —— 线程级别的 traceId 生命周期管理器
 *
 * <p>核心设计：push 时记录节点，pop 时计算耗时，栈空时一次性输出整棵树。</p>
 *
 * <h3>输出示例</h3>
 * <pre>
 * └── UserService.getUser(name="john") 15ms
 *     └── UserRepository.findById() 5ms
 *     └── UserRepository.findById() 5ms
 * </pre>
 *
 * @author CH
 */
public final class TraceContext {

    private static final String ENV_ENABLED = "TRACE_ENABLED";
    private static final String INDENT_UNIT = "    ";
    private static final String BRANCH = "└── ";
    private static final int MAX_INDENT_DEPTH = 16;

    private static final ThreadLocal<Deque<TraceNode>> STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Integer> MAX_DEPTH = new ThreadLocal<>();
    /** 共享节点存储：traceId → 该调用链的所有节点（跨线程共享） */
    private static final java.util.concurrent.ConcurrentHashMap<String, List<TraceNode>> SHARED_NODES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicLong ID_SEQ = new AtomicLong(0);

    private static final boolean ENABLED;
    private static final String[] INDENTS;

    private static MethodHandle mdcPutHandle;
    private static MethodHandle mdcRemoveHandle;
    private static volatile boolean mdcAvailable = true;

    private static MethodHandle loggerInfoHandle;
    private static Object logger;
    private static volatile boolean slf4jAvailable = true;

    static {
        String val = System.getenv(ENV_ENABLED);
        ENABLED = !"false".equalsIgnoreCase(val);

        INDENTS = new String[MAX_INDENT_DEPTH + 1];
        StringBuilder sb = new StringBuilder();
        INDENTS[0] = "";
        for (int i = 1; i <= MAX_INDENT_DEPTH; i++) {
            sb.append(INDENT_UNIT);
            INDENTS[i] = sb.toString();
        }

        if (ENABLED) {
            initMdcHandles();
            initSlf4jHandle();
        }
    }

    private TraceContext() {
    }

    // ==================== 核心 API ====================

    /**
     * 方法入口入栈
     *
     * @param className   类简单名
     * @param packageName 包名
     * @param methodName  方法名
     * @return traceId
     */
    public static String push(String className, String packageName, String methodName) {
        if (!ENABLED) { return ""; }

        Deque<TraceNode> stack = STACK.get();
        String traceId;
        int depth;
        if (stack.isEmpty()) {
            traceId = nextId();
            depth = 0;
        } else {
            TraceNode parent = stack.peek();
            traceId = parent.traceId;
            depth = parent.depth + 1;
        }

        TraceNode node = new TraceNode(traceId, className, methodName, depth);
        node.packageName = packageName;

        // 超过最大层级时仍然入栈（保持父子关系），但不收集
        Integer maxDepth = MAX_DEPTH.get();
        boolean suppressed = maxDepth != null && maxDepth > 0 && depth >= maxDepth;
        node.suppressed = suppressed;

        // 标记根节点（栈空时第一个 push）
        if (stack.isEmpty()) {
            node.isRoot = true;
        }

        stack.push(node);
        if (!suppressed) {
            SHARED_NODES.computeIfAbsent(traceId, k -> new ArrayList<>()).add(node);
        }
        putMdc(traceId);
        return traceId;
    }

    public static void push(String traceId, String className, String packageName, String methodName) {
        if (!ENABLED) { return; }

        Deque<TraceNode> stack = STACK.get();
        int depth = stack.isEmpty() ? 0 : stack.peek().depth + 1;

        TraceNode node = new TraceNode(traceId, className, methodName, depth);
        node.packageName = packageName;

        Integer maxDepth = MAX_DEPTH.get();
        boolean suppressed = maxDepth != null && maxDepth > 0 && depth >= maxDepth;
        node.suppressed = suppressed;

        if (stack.isEmpty()) {
            node.isRoot = true;
        }

        stack.push(node);
        if (!suppressed) {
            SHARED_NODES.computeIfAbsent(traceId, k -> new ArrayList<>()).add(node);
        }
        putMdc(traceId);
    }

    public static String pushWithArgs(String className, String packageName, String methodName, String args) {
        if (!ENABLED) { return ""; }

        Deque<TraceNode> stack = STACK.get();
        String traceId;
        int depth;
        if (stack.isEmpty()) {
            traceId = nextId();
            depth = 0;
        } else {
            TraceNode parent = stack.peek();
            traceId = parent.traceId;
            depth = parent.depth + 1;
        }

        TraceNode node = new TraceNode(traceId, className, methodName, depth);
        node.args = args;
        node.packageName = packageName;

        Integer maxDepth = MAX_DEPTH.get();
        boolean suppressed = maxDepth != null && maxDepth > 0 && depth >= maxDepth;
        node.suppressed = suppressed;

        if (stack.isEmpty()) {
            node.isRoot = true;
        }

        stack.push(node);
        if (!suppressed) {
            SHARED_NODES.computeIfAbsent(traceId, k -> new ArrayList<>()).add(node);
        }
        putMdc(traceId);
        return traceId;
    }

    /**
     * 方法出口出栈 —— 栈空时一次性输出整棵树
     */
    /**
     * 记录当前方法的异常信息
     *
     * @param t 捕获的异常
     */
    public static void catchException(Throwable t) {
        if (!ENABLED) { return; }
        Deque<TraceNode> stack = STACK.get();
        if (!stack.isEmpty()) {
            stack.peek().exception = t;
        }
    }

    public static void pop() {
        if (!ENABLED) { return; }

        Deque<TraceNode> stack = STACK.get();
        if (stack.isEmpty()) { return; }

        TraceNode node = stack.pop();
        node.elapsed = System.nanoTime() - node.startTime;

        if (stack.isEmpty()) {
            // 根节点出栈，输出整棵树
            flushTree(node.traceId);
            STACK.remove();
            MAX_DEPTH.remove();
            SHARED_NODES.remove(node.traceId);
            removeMdc();
        } else {
            putMdc(stack.peek().traceId);
        }
    }

    /**
     * 强制退出 —— 清空并输出所有已收集节点
     */
    public static void exit() {
        Deque<TraceNode> stack = STACK.get();
        String traceId = null;
        while (!stack.isEmpty()) {
            TraceNode n = stack.pop();
            n.elapsed = System.nanoTime() - n.startTime;
            traceId = n.traceId;
        }
        if (traceId != null) {
            flushTree(traceId);
        }
        STACK.remove();
        MAX_DEPTH.remove();
        if (traceId != null) {
            SHARED_NODES.remove(traceId);
        }
        removeMdc();
    }

    /**
     * 设置最大追踪层级
     */
    public static void setMaxDepth(int depth) {
        MAX_DEPTH.set(depth);
    }

    public static String getTraceId() {
        Deque<TraceNode> stack = STACK.get();
        return stack.isEmpty() ? null : stack.peek().traceId;
    }

    public static boolean isEnabled() { return ENABLED; }

    public static int getDepth() {
        Deque<TraceNode> stack = STACK.get();
        return stack.isEmpty() ? 0 : stack.peek().depth;
    }

    // ==================== 线程传递 ====================

    public static Runnable wrap(Runnable task) {
        if (!ENABLED) { return task; }
        String traceId = getTraceId();
        int parentDepth = getDepth();
        if (traceId == null) { return task; }
        return () -> {
            pushWithDepth(traceId, "Thread", "run", parentDepth);
            try {
                task.run();
            } finally {
                // 子线程清理：只弹栈不输出，由根线程统一输出
                Deque<TraceNode> stack = STACK.get();
                if (!stack.isEmpty()) {
                    stack.pop();
                }
                if (stack.isEmpty()) {
                    STACK.remove();
                    MAX_DEPTH.remove();
                    removeMdc();
                } else {
                    putMdc(stack.peek().traceId);
                }
            }
        };
    }

    public static <T> Callable<T> wrap(Callable<T> task) {
        if (!ENABLED) { return task; }
        String traceId = getTraceId();
        int parentDepth = getDepth();
        if (traceId == null) { return task; }
        return () -> {
            pushWithDepth(traceId, "Thread", "call", parentDepth);
            try {
                return task.call();
            } finally {
                Deque<TraceNode> stack = STACK.get();
                if (!stack.isEmpty()) {
                    stack.pop();
                }
                if (stack.isEmpty()) {
                    STACK.remove();
                    MAX_DEPTH.remove();
                    removeMdc();
                } else {
                    putMdc(stack.peek().traceId);
                }
            }
        };
    }

    private static void pushWithDepth(String traceId, String className, String methodName, int parentDepth) {
        if (!ENABLED) { return; }
        Deque<TraceNode> stack = STACK.get();
        int depth = parentDepth + 1;
        Integer maxDepth = MAX_DEPTH.get();
        if (maxDepth != null && maxDepth > 0 && depth >= maxDepth) { return; }
        TraceNode node = new TraceNode(traceId, className, methodName, depth);
        stack.push(node);
        SHARED_NODES.computeIfAbsent(traceId, k -> new ArrayList<>()).add(node);
        putMdc(traceId);
    }

    // ==================== 树形输出 ====================

    /** 颜色阈值：耗时占比超过此值显示红色 */
    private static final long COLOR_THRESHOLD_PERCENT = 80;

    /** ANSI 红色 */
    private static final String RED = "\033[31m";
    /** ANSI 重置 */
    private static final String RESET = "\033[0m";

    /**
     * 一次性输出整棵树（对齐格式：占比 + 类.方法 | 耗时 | 包名）
     */
    private static void flushTree(String traceId) {
        List<TraceNode> nodes = SHARED_NODES.get(traceId);
        if (nodes == null || nodes.isEmpty()) { return; }

        // 找到根节点的耗时作为基准
        long rootElapsed = 0;
        for (TraceNode n : nodes) {
            if (n.isRoot) { rootElapsed = n.elapsed; break; }
        }
        if (rootElapsed == 0 && !nodes.isEmpty()) {
            rootElapsed = nodes.get(0).elapsed;
        }

        Integer maxDepth = MAX_DEPTH.get();

        // 第一遍：计算最大左侧宽度（缩进 + 百分比 + 类.方法）
        int maxLeftWidth = 0;
        for (TraceNode node : nodes) {
            if (maxDepth != null && maxDepth > 0 && node.depth >= maxDepth) { continue; }
            int leftWidth = getIndent(node.depth).length() + 5; // "100%  "
            leftWidth += node.className.length() + 1 + node.methodName.length();
            if (node.args != null) {
                leftWidth += node.args.length() + 2; // "()" 或 "(args)"
            } else {
                leftWidth += 2;
            }
            if (leftWidth > maxLeftWidth) { maxLeftWidth = leftWidth; }
        }

        // 第二遍：输出对齐的结果
        StringBuilder sb = new StringBuilder(128);
        for (TraceNode node : nodes) {
            if (maxDepth != null && maxDepth > 0 && node.depth >= maxDepth) { continue; }

            sb.setLength(0);

            long percent = rootElapsed > 0 ? (node.elapsed * 100 / rootElapsed) : 0;

            // 缩进
            sb.append(getIndent(node.depth));

            // 树形符号
            sb.append(BRANCH);

            // 占比（带颜色）
            if (percent >= COLOR_THRESHOLD_PERCENT && node.elapsed > 1_000_000) {
                sb.append(RED);
                sb.append(String.format("%3d%%", percent));
                sb.append(RESET);
            } else {
                sb.append(String.format("%3d%%", percent));
            }
            sb.append("  ");

            // 类.方法
            sb.append(node.className).append('.').append(node.methodName);
            if (node.args != null) {
                sb.append('(').append(node.args).append(')');
            } else {
                sb.append("()");
            }

            // 填充到最大宽度
            int currentLen = sb.length();
            // 减去 ANSI 转义序列的长度
            int ansiLen = (percent >= COLOR_THRESHOLD_PERCENT && node.elapsed > 1_000_000) ? (RED.length() + RESET.length()) : 0;
            int visibleLen = currentLen - ansiLen;
            while (visibleLen < maxLeftWidth) {
                sb.append(' ');
                visibleLen++;
            }

            // 耗时
            sb.append("  ");
            formatElapsed(sb, node.elapsed);

            // 异常
            if (node.exception != null) {
                sb.append("  ! ").append(node.exception.getClass().getSimpleName())
                  .append(": ").append(node.exception.getMessage());
            }

            // 包名（右对齐）
            if (node.packageName != null && !node.packageName.isEmpty()) {
                sb.append("  ").append(node.packageName);
            }

            output(sb);
        }
    }

    private static void formatElapsed(StringBuilder sb, long nanos) {
        if (nanos >= 1_000_000) {
            long ms = nanos / 1_000_000;
            long remain = (nanos % 1_000_000) / 10_000;
            if (remain > 0) {
                sb.append(ms).append('.').append(remain).append("ms");
            } else {
                sb.append(ms).append("ms");
            }
        } else {
            sb.append(nanos / 1_000).append("us");
        }
    }

    private static String getIndent(int depth) {
        if (depth < INDENTS.length) { return INDENTS[depth]; }
        StringBuilder sb = new StringBuilder(depth * 4);
        for (int i = 0; i < depth; i++) { sb.append(INDENT_UNIT); }
        return sb.toString();
    }

    private static void output(StringBuilder sb) {
        if (slf4jAvailable) {
            try {
                Object log = getLogger();
                if (log != null && loggerInfoHandle != null) {
                    loggerInfoHandle.invoke(log, sb.toString());
                    return;
                }
            } catch (Throwable ignored) { slf4jAvailable = false; }
        }
        System.out.println(sb.toString());
    }

    // ==================== ID / MDC / slf4j ====================

    private static String nextId() {
        return Long.toHexString(ID_SEQ.incrementAndGet()) + Long.toHexString(ThreadLocalRandom.current().nextLong());
    }

    private static void putMdc(String traceId) {
        if (!mdcAvailable || mdcPutHandle == null) { return; }
        try { mdcPutHandle.invoke("traceId", traceId); } catch (Throwable ignored) { mdcAvailable = false; }
    }

    private static void removeMdc() {
        if (!mdcAvailable || mdcRemoveHandle == null) { return; }
        try { mdcRemoveHandle.invoke("traceId"); } catch (Throwable ignored) { mdcAvailable = false; }
    }

    private static void initMdcHandles() {
        try {
            Class<?> c = Class.forName("org.slf4j.MDC");
            MethodHandles.Lookup l = MethodHandles.lookup();
            mdcPutHandle = l.unreflect(c.getMethod("put", String.class, String.class));
            mdcRemoveHandle = l.unreflect(c.getMethod("remove", String.class));
        } catch (Exception ignored) { mdcAvailable = false; }
    }

    private static Object getLogger() throws Throwable {
        if (logger == null && slf4jAvailable) {
            Class<?> fc = Class.forName("org.slf4j.LoggerFactory");
            MethodHandle fh = MethodHandles.lookup().findStatic(fc, "getLogger",
                    MethodType.methodType(Class.forName("org.slf4j.Logger"), Class.class));
            logger = fh.invoke(TraceContext.class);
            loggerInfoHandle = MethodHandles.lookup().unreflect(
                    logger.getClass().getMethod("info", String.class));
        }
        return logger;
    }

    private static void initSlf4jHandle() {
        try { Class.forName("org.slf4j.LoggerFactory"); }
        catch (Exception ignored) { slf4jAvailable = false; }
    }

    // ==================== 内部类 ====================

    static class TraceNode {
        final String traceId, className, methodName;
        final int depth;
        final long startTime;
        long elapsed;
        String args;
        boolean suppressed;
        boolean isRoot;
        Throwable exception;
        String packageName;

        TraceNode(String traceId, String className, String methodName, int depth) {
            this.traceId = traceId;
            this.className = className;
            this.methodName = methodName;
            this.depth = depth;
            this.startTime = System.nanoTime();
        }
    }
}

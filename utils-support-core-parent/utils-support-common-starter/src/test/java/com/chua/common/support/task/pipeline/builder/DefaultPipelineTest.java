package com.chua.common.support.task.pipeline.builder;

import com.chua.common.support.task.pipeline.callback.PipelineListener;
import com.chua.common.support.task.pipeline.core.Action;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;
import com.chua.common.support.task.pipeline.core.RouteStrategy;
import com.chua.common.support.task.pipeline.exception.PipelineException;
import com.chua.common.support.task.pipeline.node.EndNode;
import com.chua.common.support.task.pipeline.node.StartNode;
import com.chua.common.support.task.pipeline.node.TaskNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultPipeline} 执行引擎的行为测试。
 *
 * <p>覆盖引擎主循环的关键分支：默认顺序推进、节点输出存储契约、循环检测、
 * 重播、返回值路由与三种路由策略、异常与错误恢复、显式动作优先级、
 * WAIT 挂起断点推进、BREAK/PREV 跳转、unit 数据依赖、节点参数与
 * env 前缀注入、节点本地数据隔离、监听回调顺序、执行深度上限。</p>
 *
 * <p>本测试不依赖任何 mock 框架，全部使用真实的内存节点实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class DefaultPipelineTest {

    /**
     * 节点回调函数。
     *
     * @param <T> 数据类型
     */
    @FunctionalInterface
    private interface NodeBody {
        /**
         * 执行节点。
         *
         * @param ctx 上下文
         * @return 下一节点 标识 或 空
         * @throws Exception 业务异常
         */
        String apply(PipelineContext<String> ctx) throws Exception;
    }

    /**
     * 构造任务节点。
     *
     * @param id   节点 标识
     * @param body 节点逻辑
     * @return 任务节点
     */
    private static TaskNode task(String id, NodeBody body) {
        return new TaskNode(id, ctx -> {
            try {
                return body.apply(castContext(ctx));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * 把通配泛型上下文收窄为字符串上下文。
     *
     * <p>引擎以 {@code PipelineNode.execute(PipelineContext<?>)} 的通配签名调用节点，
     * 测试数据统一为字符串，此处做受检窄化转换。</p>
     *
     * @param ctx 通配泛型上下文
     * @return 字符串上下文
     */
    @SuppressWarnings("unchecked")
    private static PipelineContext<String> castContext(PipelineContext<?> ctx) {
        return (PipelineContext<String>) ctx;
    }

    /**
     * 依据有序节点列表构造流水线。
     *
     * @param startNodeId 起始节点 标识，空 表示不设置
     * @param endNodeId   终止节点 标识，空 表示不设置
     * @param strategy    路由策略
     * @param listeners   监听器列表
     * @param nodes       有序节点
     * @return 默认流水线
     */
    private static DefaultPipeline pipeline(String startNodeId, String endNodeId,
                                            RouteStrategy strategy,
                                            List<PipelineListener> listeners,
                                            PipelineNode... nodes) {
        Map<String, PipelineNode> nodeMap = new LinkedHashMap<>();
        List<PipelineNode> ordered = new ArrayList<>();
        for (PipelineNode node : nodes) {
            nodeMap.put(node.getId(), node);
            ordered.add(node);
        }
        return new DefaultPipeline("p1", startNodeId, endNodeId, nodeMap, ordered,
                listeners, strategy);
    }

    /**
     * 构造最简流水线（THROW 策略、无监听器）。
     *
     * @param startNodeId 起始节点 标识
     * @param endNodeId   终止节点 标识
     * @param nodes       有序节点
     * @return 默认流水线
     */
    private static DefaultPipeline pipeline(String startNodeId, String endNodeId, PipelineNode... nodes) {
        return pipeline(startNodeId, endNodeId, RouteStrategy.THROW, List.of(), nodes);
    }

    // ==================== 默认顺序推进 ====================

    /**
     * 节点返回空值时按定义顺序前进，并把当前数据存入节点输出。
     */
    @Test
    void 顺序执行并按定义顺序前进() {
        List<String> visited = new ArrayList<>();
        PipelineNode a = task("a", ctx -> {
            visited.add("a");
            ctx.setCurrentData("A");
            return null;
        });
        PipelineNode b = task("b", ctx -> {
            visited.add("b");
            ctx.setCurrentData("B");
            return null;
        });

        PipelineContext<String> ctx = pipeline("a", "end", a, b, new EndNode("end")).execute("in");

        assertEquals(List.of("a", "b"), visited);
        assertEquals("B", ctx.getCurrentData());
        assertEquals("A", ctx.getNodeOutput("a"));
        assertEquals("B", ctx.getNodeOutput("b"));
        assertEquals(Action.EXIT, ctx.getAction());
    }

    /**
     * 起始节点为空时不进入主循环，直接结束。
     */
    @Test
    void 起始节点为空时不进入主循环() {
        PipelineNode a = task("a", ctx -> null);
        PipelineContext<String> ctx = pipeline(null, null, a).execute("in");

        assertNull(ctx.getCurrentNodeId());
        assertEquals(List.of(), ctx.getHistory());
    }

    /**
     * 终止节点 标识 匹配当前节点时，引擎在当前轮结束后退出。
     */
    @Test
    void 终止节点标识匹配时退出() {
        List<String> visited = new ArrayList<>();
        PipelineNode a = task("a", ctx -> {
            visited.add("a");
            return "end";
        });
        PipelineNode after = task("after", ctx -> {
            visited.add("after");
            return null;
        });

        PipelineContext<String> ctx = pipeline("a", "end", a, after, new EndNode("end")).execute("in");

        assertEquals(List.of("a"), visited);
        assertEquals(Action.EXIT, ctx.getAction());
    }

    // ==================== 节点输出存储契约 ====================

    /**
     * 当前数据为空时不写入节点输出，下游读取到空。
     */
    @Test
    void 当前数据为空时不存储节点输出() {
        PipelineNode a = task("a", ctx -> {
            ctx.setCurrentData(null);
            return null;
        });

        PipelineContext<String> ctx = pipeline("a", null, a, new EndNode("end")).execute("in");

        assertFalse(ctx.getNodeOutputs().containsKey("a"));
        assertNull(ctx.getNodeOutput("a"));
    }

    /**
     * 节点已自行存储结构化结果时，引擎不得用当前数据覆盖。
     */
    @Test
    void 节点自存结构化结果不被覆盖() {
        PipelineNode a = task("a", ctx -> {
            ctx.setNodeOutput("a", "结构化结果");
            ctx.setCurrentData("普通数据");
            return null;
        });

        PipelineContext<String> ctx = pipeline("a", null, a, new EndNode("end")).execute("in");

        assertEquals("结构化结果", ctx.getNodeOutput("a"));
    }

    // ==================== 循环检测与重播 ====================

    /**
     * 跳回已执行节点时抛出循环检测异常。
     */
    @Test
    void 跳回已执行节点触发循环检测() {
        PipelineNode a = task("a", ctx -> "b");
        PipelineNode b = task("b", ctx -> "a");

        PipelineException e = assertThrows(PipelineException.class,
                () -> pipeline("a", null, a, b).execute("in"));
        assertTrue(e.getMessage().contains("Cycle detected"), e.getMessage());
    }

    /**
     * 重播动作允许重复执行同一节点并保留历史。
     */
    @Test
    void 重播允许重复执行同一节点() {
        AtomicInteger counter = new AtomicInteger();
        PipelineNode a = task("a", ctx -> {
            if (counter.getAndIncrement() == 0) {
                ctx.setAction(Action.REPLAY);
            }
            return null;
        });
        PipelineNode end = new EndNode("end");

        PipelineContext<String> ctx = pipeline("a", "end", a, end).execute("in");

        assertEquals(2, counter.get());
        assertEquals(Action.EXIT, ctx.getAction());
        assertTrue(ctx.getHistory().size() >= 2, "重播应保留每次执行的历史: " + ctx.getHistory());
    }

    /**
     * 节点持续重播自身时触发执行深度上限，防止死循环。
     */
    @Test
    void 持续重播触发执行深度上限() {
        PipelineNode a = ctx -> {
            ctx.setAction(Action.REPLAY);
            return null;
        };
        TaskNode loop = new TaskNode("a", a);

        PipelineException e = assertThrows(PipelineException.class,
                () -> pipeline("a", null, loop).execute("in"));
        assertTrue(e.getMessage().contains("Execution depth exceeded"), e.getMessage());
    }

    // ==================== 返回值路由与路由策略 ====================

    /**
     * 节点返回非空目标且目标存在时触发跳转。
     */
    @Test
    void 返回存在的目标节点触发跳转() {
        List<String> visited = new ArrayList<>();
        PipelineNode a = task("a", ctx -> {
            visited.add("a");
            return "c";
        });
        PipelineNode b = task("b", ctx -> {
            visited.add("b");
            return null;
        });
        PipelineNode c = task("c", ctx -> {
            visited.add("c");
            return null;
        });

        pipeline("a", "end", a, b, c, new EndNode("end")).execute("in");

        assertEquals(List.of("a", "c"), visited);
    }

    /**
     * 目标不存在且策略为 THROW 时抛出异常。
     *
     * <p><strong>已知缺陷留痕：</strong>「目标节点不存在」的
     * {@link PipelineException} 是在节点执行的 try 块内部抛出的，会被其后的
     * {@code catch (Exception)} 捕获并重新包装为 "Node execution failed"，
     * 因此具体原因只能从 {@code getCause()} 读到，外层消息不含目标节点 标识。</p>
     */
    @Test
    void 目标不存在且策略为抛出时抛异常() {
        PipelineNode a = task("a", ctx -> "missing");

        PipelineException e = assertThrows(PipelineException.class,
                () -> pipeline("a", null, RouteStrategy.THROW, List.of(), a).execute("in"));
        assertTrue(e.getMessage().contains("Node execution failed"), e.getMessage());
        assertNotNull(e.getCause(), "原始原因应保留在 cause 中");
        assertTrue(e.getCause().getMessage().contains("Execute result target node not found"),
                e.getCause().getMessage());
    }

    /**
     * 目标不存在且策略为 EXIT 时优雅终止。
     */
    @Test
    void 目标不存在且策略为退出时优雅终止() {
        PipelineNode a = task("a", ctx -> "missing");
        PipelineNode b = task("b", ctx -> null);

        PipelineContext<String> ctx =
                pipeline("a", null, RouteStrategy.EXIT, List.of(), a, b).execute("in");

        assertEquals(Action.EXIT, ctx.getAction());
        assertEquals("a", ctx.getCurrentNodeId());
    }

    /**
     * 目标不存在且策略为 NEXT 时按定义顺序继续。
     */
    @Test
    void 目标不存在且策略为顺序继续时跳过() {
        List<String> visited = new ArrayList<>();
        PipelineNode a = task("a", ctx -> {
            visited.add("a");
            return "missing";
        });
        PipelineNode b = task("b", ctx -> {
            visited.add("b");
            return null;
        });

        pipeline("a", null, RouteStrategy.NEXT, List.of(), a, b).execute("in");

        assertEquals(List.of("a", "b"), visited);
    }

    /**
     * 上下文起始节点不存在时按策略处理，THROW 抛异常。
     */
    @Test
    void 上下文起始节点不存在且策略为抛出时抛异常() {
        PipelineNode a = task("a", ctx -> null);

        PipelineException e = assertThrows(PipelineException.class,
                () -> pipeline("missing", null, RouteStrategy.THROW, List.of(), a).execute("in"));
        assertTrue(e.getMessage().contains("Node not found"), e.getMessage());
    }

    /**
     * 上下文起始节点不存在且策略为 NEXT 时结束流水线。
     *
     * <p><strong>已知缺陷留痕：</strong>NEXT 策略依赖
     * {@code getNextNodeIdInOrder(nodeId)} 回退，而该方法只在
     * {@code orderedNodes} 中查找当前节点的下一节点。起始节点 标识 根本不在
     * 有序节点列表里时找不到匹配项，返回 空，主循环随即结束。
     * 也就是说 NEXT 策略无法从「未知起始节点」恢复。</p>
     */
    @Test
    void 上下文起始节点不存在且策略为顺序继续时无法恢复() {
        List<String> visited = new ArrayList<>();
        PipelineNode a = task("a", ctx -> {
            visited.add("a");
            return null;
        });

        PipelineContext<String> ctx =
                pipeline("missing", null, RouteStrategy.NEXT, List.of(), a).execute("in");

        assertEquals(List.of(), visited);
        assertNull(ctx.getNextNodeId());
    }

    // ==================== 显式动作优先级 ====================

    /**
     * 节点显式设置 EXIT 时，返回值被忽略，不发生跳转。
     */
    @Test
    void 显式退出优先于返回值() {
        List<String> visited = new ArrayList<>();
        PipelineNode a = task("a", ctx -> {
            visited.add("a");
            ctx.setAction(Action.EXIT);
            return "b";
        });
        PipelineNode b = task("b", ctx -> {
            visited.add("b");
            return null;
        });

        PipelineContext<String> ctx = pipeline("a", null, a, b).execute("in");

        assertEquals(List.of("a"), visited);
        assertEquals(Action.EXIT, ctx.getAction());
    }

    /**
     * WAIT 挂起前把断点推进到顺序下一节点，便于恢复执行。
     */
    @Test
    void 挂起前把断点推进到下一节点() {
        PipelineNode a = task("a", ctx -> {
            ctx.setAction(Action.WAIT);
            return null;
        });
        PipelineNode b = task("b", ctx -> null);

        PipelineContext<String> ctx = pipeline("a", null, a, b).execute("in");

        assertEquals(Action.WAIT, ctx.getAction());
        assertEquals("b", ctx.getNextNodeId());
        assertEquals("a", ctx.getCurrentNodeId());
    }

    /**
     * BREAK 中断当前分支，按定义顺序跳到下一节点。
     */
    @Test
    void 中断动作跳到顺序下一节点() {
        List<String> visited = new ArrayList<>();
        PipelineNode a = task("a", ctx -> {
            visited.add("a");
            ctx.setAction(Action.BREAK);
            return null;
        });
        PipelineNode b = task("b", ctx -> {
            visited.add("b");
            return null;
        });

        pipeline("a", null, a, b).execute("in");

        assertEquals(List.of("a", "b"), visited);
    }

    /**
     * PREV 回退会弹出历史记录，导致循环检测失效并最终触发执行深度上限。
     *
     * <p><strong>已知缺陷留痕：</strong>PREV 分支在回退时把当前节点与前一节点
     * 都从 {@code history} 中移除，于是再次轮到同一节点时
     * {@code history.contains(nodeId)} 为假，循环检测无法命中。
     * 对「两个节点互相 PREV」这类回退链路会一直执行到
     * {@code MAX_EXECUTION_DEPTH} 才以异常收场。</p>
     */
    @Test
    void 上一节点动作因弹出历史而绕过循环检测() {
        List<String> visited = new ArrayList<>();
        PipelineNode a = task("a", ctx -> {
            visited.add("a");
            return null;
        });
        PipelineNode b = task("b", ctx -> {
            visited.add("b");
            ctx.setAction(Action.PREV);
            return null;
        });

        PipelineException e = assertThrows(PipelineException.class,
                () -> pipeline("a", null, a, b).execute("in"));
        assertTrue(e.getMessage().contains("Execution depth exceeded"), e.getMessage());
        assertTrue(visited.size() > 2, "回退应导致同一节点被反复执行，实际执行 " + visited.size() + " 次");
    }

    // ==================== 异常与错误恢复 ====================

    /**
     * 节点抛异常且无恢复节点时，异常被包装为流水线异常。
     */
    @Test
    void 节点异常且无恢复节点时终止() {
        PipelineNode a = task("a", ctx -> {
            throw new IllegalStateException("业务失败");
        });

        PipelineException e = assertThrows(PipelineException.class,
                () -> pipeline("a", null, a).execute("in"));
        assertTrue(e.getMessage().contains("Node execution failed"), e.getMessage());
        assertNotNull(e.getCause());
        assertEquals("业务失败", e.getCause().getMessage());
    }

    /**
     * 监听器返回恢复节点时，引擎路由到恢复节点继续执行。
     */
    @Test
    void 监听器返回恢复节点时继续执行() {
        List<String> visited = new ArrayList<>();
        PipelineListener listener = new PipelineListener() {
            @Override
            public String onError(PipelineContext<?> context, Throwable e) {
                return "recover";
            }
        };
        PipelineNode a = task("a", ctx -> {
            visited.add("a");
            throw new IllegalStateException("业务失败");
        });
        PipelineNode recover = task("recover", ctx -> {
            visited.add("recover");
            return null;
        });

        pipeline("a", null, RouteStrategy.THROW, List.of(listener), a, recover).execute("in");

        assertEquals(List.of("a", "recover"), visited);
    }

    /**
     * 节点异常时异常对象存入上下文，供恢复节点判断。
     */
    @Test
    void 节点异常存入上下文() {
        PipelineNode a = task("a", ctx -> {
            throw new IllegalStateException("业务失败");
        });
        PipelineListener listener = new PipelineListener() {
            @Override
            public String onError(PipelineContext<?> context, Throwable e) {
                return "recover";
            }
        };
        PipelineNode recover = task("recover", ctx -> null);

        PipelineContext<String> ctx =
                pipeline("a", null, RouteStrategy.THROW, List.of(listener), a, recover).execute("in");

        assertNotNull(ctx.getLastError());
        assertEquals("ILLEGALSTATE", ctx.getLastErrorCode());
    }

    /**
     * 监听器返回不存在的恢复节点时抛出异常。
     */
    @Test
    void 恢复节点不存在时抛异常() {
        PipelineListener listener = new PipelineListener() {
            @Override
            public String onError(PipelineContext<?> context, Throwable e) {
                return "missing";
            }
        };
        PipelineNode a = task("a", ctx -> {
            throw new IllegalStateException("业务失败");
        });

        PipelineException e = assertThrows(PipelineException.class,
                () -> pipeline("a", null, RouteStrategy.THROW, List.of(listener), a).execute("in"));
        assertTrue(e.getMessage().contains("Recovery node not found"), e.getMessage());
    }

    // ==================== 节点本地数据 ====================

    /**
     * unit 依赖未满足时抛出异常。
     */
    @Test
    void 数据依赖未满足时抛异常() {
        PipelineNode a = task("a", ctx -> null);
        TaskNode merge = task("merge", ctx -> null);
        merge.setUnits(java.util.Set.of("a"));

        PipelineException e = assertThrows(PipelineException.class,
                () -> pipeline("merge", null, a, merge).execute("in"));
        assertTrue(e.getMessage().contains("Unit dependency not satisfied"), e.getMessage());
    }

    /**
     * 依赖满足时依赖数据以 unit 前缀注入节点本地数据。
     */
    @Test
    void 数据依赖满足时按前缀注入() {
        List<Object> seen = new ArrayList<>();
        PipelineNode a = task("a", ctx -> {
            ctx.setCurrentData("A的值");
            return null;
        });
        TaskNode merge = task("merge", ctx -> {
            seen.add(ctx.getNodeLocalValue("unit:a"));
            return null;
        });
        merge.setUnits(java.util.Set.of("a"));

        pipeline("a", "end", a, merge, new EndNode("end")).execute("in");

        assertEquals(1, seen.size());
        assertEquals("A的值", seen.get(0));
    }

    /**
     * 节点参数与环境参数分别注入，环境参数带 env. 前缀。
     */
    @Test
    void 节点参数与环境参数分别注入() {
        Map<String, Object> captured = new LinkedHashMap<>();
        PipelineNode a = task("a", ctx -> {
            captured.put("普通参数", ctx.getNodeLocalValue("阈值"));
            captured.put("环境参数", ctx.getNodeLocalValue("env.模型路径"));
            return null;
        });
        TaskNode node = (TaskNode) a;
        node.setParams(Map.of("阈值", "0.85"));
        node.setEnv(Map.of("模型路径", "/模型/ocr.onnx"));

        pipeline("a", "end", node, new EndNode("end")).execute("in");

        assertEquals("0.85", captured.get("普通参数"));
        assertEquals("/模型/ocr.onnx", captured.get("环境参数"));
    }

    /**
     * 节点本地数据在节点之间互相隔离。
     */
    @Test
    void 节点本地数据互相隔离() {
        Object[] leaked = new Object[1];
        PipelineNode a = task("a", ctx -> {
            ctx.setNodeLocalValue("痕迹", "不应泄漏");
            return null;
        });
        PipelineNode b = task("b", ctx -> {
            leaked[0] = ctx.getNodeLocalValue("痕迹");
            return null;
        });

        pipeline("a", "end", a, b, new EndNode("end")).execute("in");

        assertNull(leaked[0]);
    }

    /**
     * 引擎按只读方式注入按顺序的下一个节点 标识。
     */
    @Test
    void 注入按顺序的下一个节点标识() {
        List<String> seen = new ArrayList<>();
        PipelineNode a = task("a", ctx -> {
            seen.add(ctx.getNextNodeIdInOrder());
            return null;
        });
        PipelineNode b = task("b", ctx -> {
            seen.add(ctx.getNextNodeIdInOrder());
            return null;
        });

        pipeline("a", null, a, b, new EndNode("end")).execute("in");

        assertEquals(List.of("b", "end"), seen);
    }

    // ==================== 监听回调 ====================

    /**
     * 监听回调按启动、前置、完成后、绘制、完成的顺序触发。
     */
    @Test
    void 监听回调按预期顺序触发() {
        List<String> events = new ArrayList<>();
        PipelineListener listener = new PipelineListener() {
            @Override
            public void onStart(PipelineContext<?> context) {
                events.add("启动");
            }

            @Override
            public void beforeNode(PipelineContext<?> context) {
                events.add("前置:" + context.getCurrentNodeId());
            }

            @Override
            public void afterNode(PipelineContext<?> context) {
                events.add("完成后:" + context.getCurrentNodeId());
            }

            @Override
            public void onDraw(PipelineContext<?> context) {
                events.add("绘制");
            }

            @Override
            public void onComplete(PipelineContext<?> context) {
                events.add("完成");
            }
        };

        PipelineNode a = task("a", ctx -> null);
        pipeline("a", "end", RouteStrategy.THROW, List.of(listener), a, new EndNode("end"))
                .execute("in");

        assertEquals(List.of("启动", "前置:a", "完成后:a", "绘制",
                "前置:end", "完成后:end", "绘制", "完成"), events);
    }

    /**
     * 恢复执行时自动补齐起始节点，从头执行。
     */
    @Test
    void 恢复执行自动补齐起始节点() {
        List<String> visited = new ArrayList<>();
        PipelineNode a = task("a", ctx -> {
            visited.add("a");
            return null;
        });
        DefaultPipeline p = pipeline("a", "end", a, new EndNode("end"));

        PipelineContext<String> ctx = new PipelineContext<>("p1", "in");
        p.resume(ctx);

        assertEquals(List.of("a"), visited);
    }

    /**
     * 终止流水线后可重复执行，不残留上一次的状态。
     */
    @Test
    void 终止后重复执行不残留状态() {
        AtomicInteger counter = new AtomicInteger();
        PipelineNode a = task("a", ctx -> {
            counter.incrementAndGet();
            return null;
        });
        DefaultPipeline p = pipeline("a", "end", a, new EndNode("end"));

        p.execute("in");
        p.stop();
        p.execute("in");

        assertEquals(2, counter.get());
    }

    /**
     * 起始节点为内置开始节点时，顺序推进到下一个节点。
     */
    @Test
    void 起始节点为开始节点时顺序推进() {
        List<String> visited = new ArrayList<>();
        PipelineNode a = task("a", ctx -> {
            visited.add("a");
            return null;
        });
        StartNode start = new StartNode("start", "a");

        pipeline("start", "end", start, a, new EndNode("end")).execute("in");

        assertEquals(List.of("a"), visited);
    }

    /**
     * 节点输出与上下文对象为同一引用，跨节点可读。
     */
    @Test
    void 跨节点可读取上游输出() {
        List<Object> seen = new ArrayList<>();
        PipelineNode a = task("a", ctx -> {
            ctx.setCurrentData("上游数据");
            return null;
        });
        PipelineNode b = task("b", ctx -> {
            seen.add(ctx.getData("a"));
            return null;
        });

        PipelineContext<String> ctx = pipeline("a", "end", a, b, new EndNode("end")).execute("in");

        assertEquals(List.of("上游数据"), seen);
        assertSame(ctx.getNodeOutputs().get("a"), ctx.getNodeOutput("a"));
    }

    /**
     * 提供未使用的函数式引用，确保节点构造入口被覆盖。
     */
    @Test
    void 节点构造函数支持函数式实现() {
        TaskNode node = task("fn", ctx -> null);
        assertEquals("task", node.getType());
        assertEquals("fn", node.getId());
        assertTrue(node.getUnits().isEmpty());
        assertNull(node.getRetryConfig());
    }
}

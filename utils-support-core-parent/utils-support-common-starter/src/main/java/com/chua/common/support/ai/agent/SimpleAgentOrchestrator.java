package com.chua.common.support.ai.agent;

import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 默认多 Agent 编排器。
 *
 * <p>PIPELINE 串行链式执行；FAN_OUT 用固定守护线程池并行执行后按声明顺序聚合。
 * 流式模式下逐阶段/逐 Agent 转发事件并吞掉各自的 COMPLETE，最后补发一个聚合 COMPLETE。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public class SimpleAgentOrchestrator implements AgentOrchestrator {

    /**
     * 共享默认实例（无状态，可复用）
     */
    public static final SimpleAgentOrchestrator INSTANCE = new SimpleAgentOrchestrator();

    /**
     * FAN_OUT 默认聚合器：按双换行拼接
     */
    private static final Function<List<String>, String> JOIN_BY_NEWLINE =
            outputs -> String.join("\n\n", outputs);

    /**
     * 线程序号（命名用）
     */
    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    @Override
    public AgentResponse orchestrate(AgentOrchestrationRequest request) {
        if (request == null) {
            throw new NullPointerException("request 不能为 null");
        }
        return request.mode() == AgentOrchestrationRequest.Mode.PIPELINE
                ? orchestratePipeline(request)
                : orchestrateFanOut(request);
    }

    @Override
    public Flux<AgentStreamEvent> orchestrateStream(AgentOrchestrationRequest request) {
        if (request == null) {
            throw new NullPointerException("request 不能为 null");
        }
        return request.mode() == AgentOrchestrationRequest.Mode.PIPELINE
                ? orchestratePipelineStream(request)
                : orchestrateFanOutStream(request);
    }

    /**
     * 流水线同步执行：前序输出作为后序输入。
     */
    private AgentResponse orchestratePipeline(AgentOrchestrationRequest request) {
        String current = request.input() == null ? "" : request.input();
        List<String> outputs = new ArrayList<>(request.agents().size());
        for (Agent agent : request.agents()) {
            AgentResponse response = agent.run(current);
            String output = response == null ? "" : nullToEmpty(response.getOutput());
            outputs.add(output);
            current = output;
        }
        String finalOutput = aggregate(request, outputs);
        return buildResponse(request, finalOutput, outputs);
    }

    /**
     * 扇出同步执行：并行执行同一输入，按声明顺序聚合。
     */
    private AgentResponse orchestrateFanOut(AgentOrchestrationRequest request) {
        List<Agent> agents = request.agents();
        List<String> outputs = new ArrayList<>(agents.size());
        ExecutorService executor = newFanOutExecutor(agents.size());
        try {
            List<CompletableFuture<String>> futures = new ArrayList<>(agents.size());
            for (Agent agent : agents) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    AgentResponse response = agent.run(request.input());
                    return response == null ? "" : nullToEmpty(response.getOutput());
                }, executor));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    outputs.add(futures.get(i).join());
                } catch (Exception e) {
                    throw new IllegalStateException("扇出编排第 " + (i + 1) + " 个子 Agent 执行失败: "
                            + e.getMessage(), e);
                }
            }
        } finally {
            executor.shutdownNow();
        }
        String finalOutput = aggregate(request, outputs);
        return buildResponse(request, finalOutput, outputs);
    }

    /**
     * 流水线流式执行：逐阶段转发事件，阶段间用前序累计正文作为输入。
     */
    private Flux<AgentStreamEvent> orchestratePipelineStream(AgentOrchestrationRequest request) {
        AtomicReference<String> current = new AtomicReference<>(
                request.input() == null ? "" : request.input());
        return Flux.fromIterable(request.agents())
                .concatMap(agent -> stageStream(agent, current::get, current::set))
                .concatWith(Flux.defer(() -> Flux.just(completeEvent(current.get()))));
    }

    /**
     * 扇出流式执行：并行合并各 Agent 事件流，最后补发聚合 COMPLETE。
     */
    private Flux<AgentStreamEvent> orchestrateFanOutStream(AgentOrchestrationRequest request) {
        List<Agent> agents = request.agents();
        List<String> outputs = new ArrayList<>(agents.size());
        for (int i = 0; i < agents.size(); i++) {
            outputs.add(null);
        }
        List<Flux<AgentStreamEvent>> streams = new ArrayList<>(agents.size());
        for (int i = 0; i < agents.size(); i++) {
            final int index = i;
            streams.add(stageStream(agents.get(i), () -> request.input(),
                    output -> outputs.set(index, output)));
        }
        return Flux.merge(streams)
                .concatWith(Flux.defer(() -> Flux.just(completeEvent(aggregate(request, outputs)))));
    }

    /**
     * 单个 Agent 的阶段流：累计正文增量，转发非 COMPLETE 事件，
     * 完成时把累计正文交给回调（供链式/聚合使用）。
     */
    private Flux<AgentStreamEvent> stageStream(Agent agent,
                                               java.util.function.Supplier<String> inputSupplier,
                                               java.util.function.Consumer<String> outputConsumer) {
        StringBuilder text = new StringBuilder();
        return agent.runStream(inputSupplier.get())
                .doOnNext(event -> {
                    if (event.getType() == AgentStreamEvent.Type.REASONING_DELTA
                            && event.getDelta() != null) {
                        text.append(event.getDelta());
                    }
                })
                .doOnComplete(() -> outputConsumer.accept(text.toString()))
                .filter(event -> event.getType() != AgentStreamEvent.Type.COMPLETE);
    }

    /**
     * 按请求聚合器合并输出；未指定时 PIPELINE 取末位输出、FAN_OUT 双换行拼接。
     */
    private String aggregate(AgentOrchestrationRequest request, List<String> outputs) {
        Function<List<String>, String> aggregator = request.aggregator();
        if (aggregator != null) {
            return aggregator.apply(outputs);
        }
        if (request.mode() == AgentOrchestrationRequest.Mode.FAN_OUT) {
            return JOIN_BY_NEWLINE.apply(outputs);
        }
        return outputs.isEmpty() ? "" : outputs.get(outputs.size() - 1);
    }

    /**
     * 构建聚合响应（per-Agent 输出与模式写入 metadata）。
     */
    private AgentResponse buildResponse(AgentOrchestrationRequest request,
                                        String finalOutput, List<String> outputs) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("orchestration.mode", request.mode().name());
        metadata.put("orchestration.agentCount", outputs.size());
        metadata.put("orchestration.outputs", outputs);
        return AgentResponse.builder()
                .output(finalOutput)
                .mode("ORCHESTRATE_" + request.mode().name())
                .metadata(metadata)
                .build();
    }

    /**
     * 构建聚合 COMPLETE 事件。
     */
    private AgentStreamEvent completeEvent(String output) {
        return AgentStreamEvent.builder()
                .type(AgentStreamEvent.Type.COMPLETE)
                .agentId("orchestrator")
                .toolOutput(output)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 创建 FAN_OUT 专用守护线程池（用完即关，不常驻）。
     */
    private ExecutorService newFanOutExecutor(int size) {
        int threads = Math.max(1, Math.min(size, Runtime.getRuntime().availableProcessors()));
        return Executors.newFixedThreadPool(threads, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "agent-fanout-" + THREAD_SEQ.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /**
     * null 转空串。
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

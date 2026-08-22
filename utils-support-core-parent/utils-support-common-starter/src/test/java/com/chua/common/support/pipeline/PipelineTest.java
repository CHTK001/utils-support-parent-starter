package com.chua.common.support.pipeline;

import com.chua.common.support.task.pipeline.builder.PipelineBuilder;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pipeline 管道引擎基础测试
 */
class PipelineTest {

    @Test
    void testSequentialPipeline() {
        StringBuilder sb = new StringBuilder();
        Pipeline pipeline = PipelineBuilder.newBuilder("seq")
                .task("step1", ctx -> { sb.append("A"); return null; }).taskEnd()
                .task("step2", ctx -> { sb.append("B"); return null; }).taskEnd()
                .task("step3", ctx -> { sb.append("C"); return null; }).taskEnd()
                .build();

        pipeline.execute(new PipelineContext());
        assertEquals("ABC", sb.toString());
    }

    @Test
    void testExitStopsPipeline() {
        StringBuilder sb = new StringBuilder();
        Pipeline pipeline = PipelineBuilder.newBuilder("exit")
                .task("step1", ctx -> { sb.append("A"); return true; }).taskEnd()
                .task("step2", ctx -> { sb.append("B"); return false; }).taskEnd()
                .task("step3", ctx -> { sb.append("C"); return true; }).taskEnd()
                .build();

        pipeline.execute(new PipelineContext());
        assertEquals("AB", sb.toString()); // step3 should not run
    }

    @Test
    void testOnStepCallback() {
        java.util.List<String> steps = new java.util.ArrayList<>();
        Pipeline pipeline = PipelineBuilder.newBuilder("callback")
                .task("s1", ctx -> null).taskEnd()
                .task("s2", ctx -> null).taskEnd()
                .onStep(ctx -> steps.add(ctx.getCurrentStep()))
                .build();

        pipeline.execute(new PipelineContext());
        assertTrue(steps.contains("s1"));
        assertTrue(steps.contains("s2"));
    }

    @Test
    void testContextDataSharing() {
        Pipeline pipeline = PipelineBuilder.newBuilder("context")
                .task("producer", ctx -> {
                    ctx.setData("value", 42);
                    return null;
                }).taskEnd()
                .task("consumer", ctx -> {
                    Integer v = ctx.getData("value");
                    ctx.setData("doubled", v * 2);
                    return null;
                }).taskEnd()
                .build();

        PipelineContext context = new PipelineContext();
        pipeline.execute(context);
        assertEquals(84, context.getData("doubled"));
    }
}

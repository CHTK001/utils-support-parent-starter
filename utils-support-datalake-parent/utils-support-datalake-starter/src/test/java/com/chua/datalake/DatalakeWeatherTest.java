package com.chua.datalake;

import com.chua.common.support.lang.json.Json;
import com.chua.datalake.support.engine.ConditionRouter;
import com.chua.datalake.support.engine.PipelineEngine;
import com.chua.datalake.support.model.CollectData;
import com.chua.datalake.support.model.DataEnvelope;
import com.chua.datalake.support.model.PipelineState;
import com.chua.datalake.support.pipeline.DefaultPipelineManager;
import com.chua.datalake.support.sink.LogSink;
import com.chua.datalake.support.spi.pipeline.PipelineManager;
import com.chua.datalake.support.spi.storage.DataSink;
import com.chua.datalake.support.transport.DisruptorMq;
import com.chua.datalake.support.spi.transport.InternalMq;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 数据中台完整示例：Weather 实时采集 → Pipeline 处理 → Sink 验证。
 *
 * <p>演示 InternalMq + PipelineEngine + DataSink 的三层数据流，
 * 通过 DisruptorMq（内存发布订阅）解耦采集与处理阶段。</p>
 *
 * <h3>内部队列 InternalMq</h3>
 * SPI 接口：{@link com.chua.datalake.support.spi.transport.InternalMq}
 * 方法：publish(topic, envelope) / subscribe(topic, consumer)
 * 实现：{@link DisruptorMq}（内存版基于 ConcurrentHashMap + CopyOnWriteArrayList）
 *
 * <h3>数据流</h3>
 * WeatherCollector／程序模拟 → InternalMq.publish → PipelineEngine.execute → Sink.write
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class DatalakeWeatherTest {

    public static void main(String[] args) throws Exception {
        log.info("[datalake-server] === 数据中台 Weather 测试开始 ===");

        // ===== 1. 初始化内部队列 =====
        // InternalMq SPI 接口，用于 Pipeline 内部各阶段之间的消息发布/订阅
        InternalMq mq = new DisruptorMq();
        mq.start();

        // ===== 2. 注册 Sink（数据下沉目标）=====
        // 用 List 保存写入结果，方便断言验证
        List<DataEnvelope> receivedSinkData = new CopyOnWriteArrayList<>();
        DataSink testSink = new LogSink() {
            @Override
            public String type() {
                return "test";
            }

            @Override
            public boolean write(DataEnvelope envelope, Map<String, Object> config) {
                receivedSinkData.add(envelope);
                return super.write(envelope, config);
            }
        };

        // ===== 3. 创建 PipelineManager 并注册管线 =====
        String pipelineId = "weather-pipeline";
        String pipelineDsl = "{"
                + "\"stages\": {"
                + "  \"default\": {"
                + "    \"sink\": [{\"type\": \"test\"}]"
                + "  }"
                + "}"
                + "}";

        PipelineManager pipelineManager = new DefaultPipelineManager();
        pipelineManager.savePipeline(pipelineId, pipelineDsl);
        pipelineManager.start(pipelineId);

        // ===== 4. 初始化 PipelineEngine =====
        PipelineEngine engine = new PipelineEngine(
                pipelineManager,
                new ConditionRouter(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Map.of("test", testSink)
        );
        engine.start(pipelineId);

        // ===== 5. 通过 InternalMq 订阅 =====
        // 当数据发布到 InternalMq 的 "weather" 主题时，自动触发 Pipeline 处理
        mq.subscribe("weather", envelope -> {
            log.info("[datalake-server] [MQ] 收到天气数据, topic=weather, traceId={}", envelope.getTraceId());
            List<DataEnvelope> results = engine.execute(pipelineId, envelope);
            log.info("[datalake-server] [MQ] Pipeline 处理完成, 产出 {} 条结果", results.size());
        });

        // ===== 6. 模拟天气采集数据（WeatherCollector 的实际 payload 格式）=====
        simulateWeatherData(mq);

        // ===== 7. 验证 Sink 结果 =====
        log.info("[datalake-server] === 验证结果 ===");
        log.info("[datalake-server] Sink 收到 {} 条数据", receivedSinkData.size());
        for (DataEnvelope env : receivedSinkData) {
            log.info("[datalake-server]   traceId={}, state={}, parsed={}",
                    env.getTraceId(), env.getState(), env.getParsed());
        }

        if (receivedSinkData.isEmpty()) {
            log.error("[datalake-server] FAIL: Sink 未收到任何数据");
        } else {
            boolean allSuccess = receivedSinkData.stream()
                    .allMatch(d -> d.getState() == PipelineState.SINK_OK);
            if (allSuccess) {
                log.info("[datalake-server] SUCCESS: 所有数据已成功写入 Sink");
            } else {
                log.warn("[datalake-server] WARN: 部分数据状态异常");
            }
        }

        // ===== 8. 清理 =====
        mq.stop();
        pipelineManager.stop(pipelineId);
        log.info("[datalake-server] === 测试结束 ===");
    }

    /**
     * 模拟天气采集数据发布到 InternalMq。
     *
     * <p>实际使用 WeatherCollector 时会调用外部 Open-Meteo API，
     * 这里用模拟数据避免网络依赖。WeatherCollector 收到 API 响应后同样
     * 构建 CollectData → publish 到服务器 → 最终经 InternalMq 流转。</p>
     *
     * @param mq 内部队列实例
     */
    private static void simulateWeatherData(InternalMq mq) {
        // 模拟 Open-Meteo API 返回的天气 JSON
        String mockWeatherJson = "{"
                + "\"latitude\":39.9042,\"longitude\":116.4074,"
                + "\"current\":{"
                + "  \"temperature_2m\":28.5,"
                + "  \"relative_humidity_2m\":65,"
                + "  \"precipitation\":0.0,"
                + "  \"weather_code\":1,"
                + "  \"wind_speed_10m\":12.3"
                + "}"
                + "}";

        // 构建 DataEnvelope（Pipeline 处理的基本数据单元）
        DataEnvelope envelope = new DataEnvelope();
        envelope.setTraceId(UUID.randomUUID().toString().replace("-", ""));
        envelope.setPipelineId("weather-pipeline");
        envelope.setTimestamp(System.currentTimeMillis());
        envelope.setRawPayload(mockWeatherJson);
        envelope.setParsed(Json.fromJson(mockWeatherJson));
        envelope.setState(PipelineState.RECEIVED);
        envelope.addTrace("Received weather data");

        log.info("[datalake-server] [模拟] 发布天气数据到 InternalMq: topic=weather");
        mq.publish("weather", envelope);
    }
}
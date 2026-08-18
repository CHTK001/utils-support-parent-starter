package com.chua.runtime.apm.storage;

import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;
import com.chua.runtime.protocol.StatusCode;
import com.chua.runtime.protocol.TransmissionRecord;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 传输事件 — 持久化用扁平 record。
 *
 * <p>从 {@code TransmissionRecord} 提取扁平字段，避免持久化层依赖 spy 模块；
 * 查询时再由 Controller 还原为 {@link com.chua.runtime.protocol.TransmissionRecord}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransmissionEvent {

    /** 内部 row id，存储层自增 */
    /** ID */
    private long id;

    /**
     * trace Id
     */
    private String traceId;
    /**
     * span Id
     */
    private String spanId;
    /**
     * parent Span Id
     */
    private String parentSpanId;

    /**
     * source Protocol
     */
    private String sourceProtocol;
    /**
     * source Software
     */
    private String sourceSoftware;
    /**
     * source Host
     */
    private String sourceHost;
    /**
     * source Port
     */
    private int sourcePort;
    /**
     * source Path
     */
    private String sourcePath;

    /**
     * target Protocol
     */
    private String targetProtocol;
    /**
     * target Software
     */
    private String targetSoftware;
    /**
     * target Host
     */
    private String targetHost;
    /**
     * target Port
     */
    private int targetPort;
    /**
     * target Path
     */
    private String targetPath;

    /** 协议名（HTTP/TCP/REDIS/...） */
    /** 协议 */
    private String protocol;

    /** 软件栈名（JEDIS/TOMCAT/...） */
    /** Software */
    private String software;

    /** 操作描述（GET /api/order） */
    /** Operation */
    private String operation;

    /** 状态（OK/ERROR） */
    /** 状态 */
    private StatusCode status;

    /** 响应码（HTTP 状态码 / ZK rc / Redis reply） */
    /** 状态代码 */
    private int statusCode;

    /**
     * start Time
     */
    private long startTime;
    /**
     * end Time
     */
    private long endTime;
    /**
     * duration
     */
    private long duration;

    /**
     * bytes Out
     */
    private long bytesOut;
    /**
     * bytes In
     */
    private long bytesIn;

    /**
     * error Type
     */
    private String errorType;
    /**
     * error Message
     */
    private String errorMessage;

    /** 附加属性（懒填充） */
    @Builder.Default
    private Map<String, String> attributes = new HashMap<>();

    /**
     * 从 {@link com.chua.runtime.protocol.TransmissionRecord} 转扁平字段。
     */
    public static TransmissionEvent fromRecord(TransmissionRecord record) {
        if (record == null) {
            return null;
        }
        TransmissionEventBuilder b = builder()
                .traceId(record.getTraceId())
                .spanId(record.getSpanId())
                .parentSpanId(record.getParentSpanId())
                .protocol(record.getProtocol() != null ? record.getProtocol().name() : null)
                .software(record.getSoftware() != null ? record.getSoftware().name() : null)
                .operation(record.getOperation())
                .status(record.getStatus())
                .statusCode(record.getStatusCode())
                .startTime(record.getStartTime())
                .endTime(record.getEndTime())
                .duration(record.getDuration())
                .bytesOut(record.getBytesOut())
                .bytesIn(record.getBytesIn())
                .errorType(record.getErrorType())
                .errorMessage(record.getErrorMessage());
        Endpoint src = record.getSource();
        if (src != null) {
            b.sourceProtocol(src.getProtocol() != null ? src.getProtocol().name() : null)
                    .sourceSoftware(src.getSoftware() != null ? src.getSoftware().name() : null)
                    .sourceHost(src.getHost())
                    .sourcePort(src.getPort())
                    .sourcePath(src.getPath());
        }
        Endpoint tgt = record.getTarget();
        if (tgt != null) {
            b.targetProtocol(tgt.getProtocol() != null ? tgt.getProtocol().name() : null)
                    .targetSoftware(tgt.getSoftware() != null ? tgt.getSoftware().name() : null)
                    .targetHost(tgt.getHost())
                    .targetPort(tgt.getPort())
                    .targetPath(tgt.getPath());
        }
        return b.build();
    }

    /**
     * 还原为 {@link TransmissionRecord} — Controller 序列化时调用。
     */
    public TransmissionRecord toRecord() {
        TransmissionRecord r = new TransmissionRecord();
        r.setTraceId(traceId);
        r.setSpanId(spanId);
        r.setParentSpanId(parentSpanId);
        r.setProtocol(parseProtocol(protocol));
        r.setSoftware(parseSoftware(software));
        r.setOperation(operation);
        r.setStatus(status != null ? status : StatusCode.UNSET);
        r.setStatusCode(statusCode);
        r.setStartTime(startTime);
        r.setEndTime(endTime);
        r.setDuration(duration);
        r.setBytesOut(bytesOut);
        r.setBytesIn(bytesIn);
        r.setErrorType(errorType);
        r.setErrorMessage(errorMessage);
        r.setSource(Endpoint.builder()
                .kind(com.chua.runtime.protocol.EndpointKind.CLIENT)
                .protocol(parseProtocol(sourceProtocol))
                .software(parseSoftware(sourceSoftware))
                .host(sourceHost)
                .port(sourcePort)
                .path(sourcePath)
                .build());
        r.setTarget(Endpoint.builder()
                .kind(com.chua.runtime.protocol.EndpointKind.SERVER)
                .protocol(parseProtocol(targetProtocol))
                .software(parseSoftware(targetSoftware))
                .host(targetHost)
                .port(targetPort)
                .path(targetPath)
                .build());
        return r;
    }

    private static Protocol parseProtocol(String name) {
        if (name == null) {
            return Protocol.UNKNOWN;
        }
        try {
            return Protocol.valueOf(name);
        } catch (Exception e) {
            return Protocol.UNKNOWN;
        }
    }

    private static Software parseSoftware(String name) {
        if (name == null) {
            return Software.UNKNOWN;
        }
        try {
            return Software.valueOf(name);
        } catch (Exception e) {
            return Software.UNKNOWN;
        }
    }
}
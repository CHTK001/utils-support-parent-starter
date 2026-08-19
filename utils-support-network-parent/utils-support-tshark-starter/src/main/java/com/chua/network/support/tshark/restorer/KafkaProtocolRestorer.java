package com.chua.network.support.tshark.restorer;

/**
 * Kafka 协议还原器。
 *
 * <p>Kafka 请求/响应帧：length(4) + apiKey(2) + apiVersion(2) + correlationId(4) + clientId。
 * 解析常用 API key: 0=Produce, 1=Fetch, 2=ListOffsets, 3=Metadata, 8=OffsetCommit。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class KafkaProtocolRestorer extends AbstractProtocolRestorer {

    @Override
    /** 获取ProtocolName */
    public String getProtocolName() {
        return "kafka";
    }

    @Override
    /** 获取Priority */
    public int getPriority() {
        return 220;
    }

    @Override
    /** 是否可以Restore */
    public boolean canRestore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 12) {
            return false;
        }
        int length = ((rawData[0] & 0xff) << 24) | ((rawData[1] & 0xff) << 16)
                | ((rawData[2] & 0xff) << 8) | (rawData[3] & 0xff);
        if (length != rawData.length - 4) {
            return false;
        }
        int apiKey = ((rawData[4] & 0xff) << 8) | (rawData[5] & 0xff);
        return apiKey <= 70;
    }

    @Override
    /** Restore */
    public String restore(java.util.Map<String, Object> protocolInfo, byte[] rawData) {
        if (rawData == null || rawData.length < 12) {
            return "[Kafka] empty";
        }
        int length = ((rawData[0] & 0xff) << 24) | ((rawData[1] & 0xff) << 16)
                | ((rawData[2] & 0xff) << 8) | (rawData[3] & 0xff);
        int apiKey = ((rawData[4] & 0xff) << 8) | (rawData[5] & 0xff);
        int apiVersion = ((rawData[6] & 0xff) << 8) | (rawData[7] & 0xff);
        int correlationId = ((rawData[8] & 0xff) << 24) | ((rawData[9] & 0xff) << 16)
                | ((rawData[10] & 0xff) << 8) | (rawData[11] & 0xff);

        StringBuilder sb = new StringBuilder("[Kafka] ");
        sb.append("apiKey=").append(apiKey).append(" (").append(toApiKeyName(apiKey)).append(')');
        sb.append(", version=").append(apiVersion);
        sb.append(", correlationId=").append(correlationId);
        sb.append(", length=").append(length);
        return sb.toString();
    }

    /** ToApiKeyName */
    private static String toApiKeyName(int apiKey) {
        return switch (apiKey) {
            case 0 -> "Produce";
            case 1 -> "Fetch";
            case 2 -> "ListOffsets";
            case 3 -> "Metadata";
            case 4 -> "LeaderAndIsr";
            case 5 -> "StopReplica";
            case 6 -> "UpdateMetadata";
            case 7 -> "ControlledShutdown";
            case 8 -> "OffsetCommit";
            case 9 -> "OffsetFetch";
            case 10 -> "FindCoordinator";
            case 11 -> "JoinGroup";
            case 12 -> "Heartbeat";
            case 13 -> "LeaveGroup";
            case 14 -> "SyncGroup";
            case 15 -> "DescribeGroups";
            case 16 -> "ListGroups";
            case 17 -> "SaslHandshake";
            case 18 -> "ApiVersions";
            case 19 -> "CreateTopics";
            case 20 -> "DeleteTopics";
            case 21 -> "DeleteRecords";
            case 22 -> "InitProducerId";
            case 23 -> "OffsetForLeaderEpoch";
            case 24 -> "AddPartitionsToTxn";
            case 25 -> "AddOffsetsToTxn";
            case 26 -> "EndTxn";
            case 27 -> "WriteTxnMarkers";
            case 28 -> "TxnOffsetCommit";
            case 29 -> "DescribeAcls";
            case 30 -> "CreateAcls";
            case 31 -> "DeleteAcls";
            case 32 -> "DescribeConfigs";
            case 33 -> "AlterConfigs";
            case 37 -> "CreatePartitions";
            case 50 -> "DescribeUserScramCredentials";
            default -> "ApiKey_" + apiKey;
        };
    }
}
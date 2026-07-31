package com.chua.datasync.agent.support.source;

import com.chua.datasync.agent.support.DataSyncAgentSource;
import com.chua.datasync.agent.support.model.Direction;
import com.chua.datasync.agent.support.model.Directional;
import lombok.extern.slf4j.Slf4j;

/**
 * CSV 数据同步 Source，从 CSV 文件读取数据。
 * <p>
 * 底层委托 {@link DelimitedDataSyncAgentSource} 实现，支持 RFC 4180 CSV 格式。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class CsvDataSyncAgentSource extends DelimitedDataSyncAgentSource implements DataSyncAgentSource, Directional {

    /**
     * 构造 CSV Source。
     *
     * @param sourceId Source 实例 ID
     * @param inputId 输入标识
     * @param filePath CSV 文件路径
     */
    public CsvDataSyncAgentSource(String sourceId, String inputId, String filePath) {
        this(sourceId, inputId, filePath, ",");
    }

    /**
     * 构造 CSV Source。
     *
     * @param sourceId Source 实例 ID
     * @param inputId 输入标识
     * @param filePath CSV 文件路径
     * @param delimiter 定界符，默认","
     */
    public CsvDataSyncAgentSource(String sourceId, String inputId, String filePath, String delimiter) {
        super(sourceId, inputId, filePath, delimiter);
    }

    @Override
    public Direction direction() {
        return Direction.INPUT;
    }
}

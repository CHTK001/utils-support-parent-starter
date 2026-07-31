package com.chua.datasync.agent.support.source;

import com.chua.datasync.agent.support.DataSyncAgentSource;
import com.chua.datasync.agent.support.model.Direction;
import com.chua.datasync.agent.support.model.Directional;
import com.chua.datasync.agent.support.model.SyncDataOffset;
import com.chua.datasync.agent.support.offset.FileSyncDataOffsetStorage;
import com.chua.datasync.agent.support.offset.SyncDataOffsetStorage;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.regex.Pattern;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 定界符数据同步 Source，从定界符文件读取数据。
 * <p>
 * 流式读取，支持 UTF-8 编码，支持增量读取：
 * 当 params["offset"] 为数字时，跳过对应行数后开始读取。
 * 本次读取结束后通过 getLastReadOffset() 返回当前行号，
 * Scheduler 调用 writeOffset() 持久化。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DelimitedDataSyncAgentSource implements DataSyncAgentSource, Directional {

    private final String sourceId;
    private final String inputId;
    private final Path filePath;
    private final String delimiter;
    private final SyncDataOffsetStorage offsetStorage;
    private volatile long lastLineNumber = 0;

    public DelimitedDataSyncAgentSource(String sourceId, String inputId, String filePath, String delimiter) {
        this(sourceId, inputId, filePath, delimiter, new FileSyncDataOffsetStorage());
    }

    public DelimitedDataSyncAgentSource(String sourceId, String inputId, String filePath, String delimiter, SyncDataOffsetStorage offsetStorage) {
        this.sourceId = sourceId;
        this.inputId = inputId;
        this.filePath = Paths.get(filePath);
        this.delimiter = delimiter;
        this.offsetStorage = offsetStorage != null ? offsetStorage : new FileSyncDataOffsetStorage();
    }

    @Override
    public String sourceId() {
        return sourceId;
    }

    @Override
    public String inputId() {
        return inputId;
    }

    @Override
    public SyncDataOffset readOffset(Map<String, Object> params) {
        return offsetStorage.read(sourceId, null);
    }

    @Override
    public void writeOffset(SyncDataOffset offset) {
        if (offset == null) {
            return;
        }
        offsetStorage.write(offset);
    }

    @Override
    public Flux<Map<String, Object>> read(Map<String, Object> params) {
        return Flux.<Map<String, Object>>create(sink -> {
            long skip = 0;
            if (params != null) {
                Object offsetObj = params.get("offset");
                if (offsetObj instanceof Number) {
                    skip = ((Number) offsetObj).longValue();
                }
            }

            try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
                String line;
                String[] headers = null;
                long lineNumber = 0;

                while (skip > 0 && (line = reader.readLine()) != null) {
                    lineNumber++;
                    skip--;
                    if (lineNumber == 1) {
                        headers = line.split(Pattern.quote(delimiter), -1);
                    }
                }

                if (lineNumber == 0 && (line = reader.readLine()) != null) {
                    headers = line.split(Pattern.quote(delimiter), -1);
                    lineNumber = 1;
                }

                final String[] finalHeaders = headers;
                long readCount = 0;
                long currentLastLine = lineNumber;

                while ((line = reader.readLine()) != null) {
                    if (finalHeaders == null) {
                        continue;
                    }
                    String[] values = line.split(Pattern.quote(delimiter), -1);
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 0; i < finalHeaders.length && i < values.length; i++) {
                        row.put(finalHeaders[i], values[i]);
                    }
                    sink.next(row);
                    readCount++;
                    currentLastLine = lineNumber + readCount;
                }

                lastLineNumber = currentLastLine;
                sink.complete();
                log.info("[DelimitedDataSyncAgentSource] 读取完成, sourceId={}, totalLines={}", sourceId, lastLineNumber);

            } catch (Exception e) {
                sink.error(e);
                log.error("[DelimitedDataSyncAgentSource] 读取失败, filePath={}", filePath, e);
            }
        });
    }

    @Override
    public Object getLastReadOffset() {
        return lastLineNumber;
    }

    @Override
    public void close() {
    }

    @Override
    public Direction direction() {
        return Direction.INPUT;
    }
}
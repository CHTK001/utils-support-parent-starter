package com.chua.datasync.agent.support.sink;

import com.chua.datasync.agent.support.DataSyncAgentSink;
import com.chua.datasync.agent.support.exception.DataSyncAgentException;
import com.chua.datasync.agent.support.exception.DataSyncErrorCode;
import com.chua.datasync.agent.support.model.Direction;
import com.chua.datasync.agent.support.model.Directional;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 定界符数据同步 Sink，将数据写入定界符文件。
 * <p>
 * 同步写入模式：消费完 Flux 后自动关闭文件。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DelimitedDataSyncAgentSink implements DataSyncAgentSink, Directional {

    /** 写入端标识 */
    /** SinkID */
    private final String sinkId;
    /** 文件路径 */
    private final Path filePath;
    /** 分隔符 */
    /** Delimiter */
    private final String delimiter;
    /** 是否追加写入 */
    /** Append */
    private final boolean append;

    public DelimitedDataSyncAgentSink(String sinkId, String filePath, String delimiter) {
        this(sinkId, filePath, delimiter, false);
    }

    public DelimitedDataSyncAgentSink(String sinkId, String filePath, String delimiter, boolean append) {
        this.sinkId = sinkId;
        this.filePath = Paths.get(filePath);
        this.delimiter = delimiter;
        this.append = append;
    }

    @Override
    public String sinkId() {
        return sinkId;
    }

    @Override
    public void write(Flux<Map<String, Object>> data) {
        try (BufferedWriter writer = openWriter()) {
            log.info("[DelimitedDataSyncAgentSink] 开始写入, sinkId={}, path={}, append={}", sinkId, filePath, append);
            int count = data.toStream()
                    .mapToInt(row -> {
                        try {
                            writer.write(row.toString());
                            writer.write(System.lineSeparator());
                            return 1;
                        } catch (Exception e) {
                            log.error("[DelimitedDataSyncAgentSink] " + DataSyncErrorCode.FILE_WRITE_FAILED.formatWithCode(sinkId, e.getMessage()), e);
                            return 0;
                        }
                    })
                    .sum();
            log.info("[DelimitedDataSyncAgentSink] 写入完成, sinkId={}, count={}", sinkId, count);
        } catch (Exception e) {
            log.error("[DelimitedDataSyncAgentSink] " + DataSyncErrorCode.FILE_WRITE_FAILED.formatWithCode(sinkId, e.getMessage()), e);
        }
    }

    private BufferedWriter openWriter() throws java.io.IOException {
        if (append) {
            return Files.newBufferedWriter(filePath, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        }
        return Files.newBufferedWriter(filePath, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Override
    public void close() {
        // 每次 write 都是 try-with-resources，无需额外关闭
    }

    @Override
    public Direction direction() {
        return Direction.OUTPUT;
    }
}
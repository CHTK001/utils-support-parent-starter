package com.chua.datasource.support.engine;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.FileUtils;
import com.chua.common.support.lang.datasource.page.Page;
import com.chua.datasource.support.wrapper.ReactorLambdaDeleteWrapper;
import com.chua.datasource.support.wrapper.ReactorLambdaQueryWrapper;
import com.chua.datasource.support.wrapper.ReactorLambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 文件响应式引擎，真响应式文件读取与内存查询。
 *
 * <p>文件读取通过 {@link AsynchronousFileChannel} 完成，为非阻塞 I/O；
 * 加载完成后数据驻留内存，查询/更新/删除均在订阅者线程直接执行，不经过 {@code boundedElastic}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @param clazz clazz
 * @return 执行查询的结果
 * @param pn pn
 * @param ps ps
 */
@Spi("file")
@Slf4j
public class FileReactorEngine implements ReactorEngine {

    private static final ObjectMapper MAPPER = new ObjectMapper(); // 映射器
    private static final TypeReference<List<Map<String, Object>>> JSON_LIST_TYPE =
            new TypeReference<List<Map<String, Object>>>() {};
/**
 * 查询。
 * @param entityClass 实体类
 * @return 查询的结果
 */

    private final FileEngine delegate = new FileEngine(); // delegate

    /**
     * 列表。
     * @return 列表的结果
     */
    @Override
    public <T> ReactorLambdaQueryWrapper<T> query(Class<T> entityClass) {
        // 文件引擎没有 SqlExecutor：包装器默认的「渲染 SQL 再执行」路径会直接 NPE，
        // 且查询条件/排序/limit/offset 需要平移到内存求值。
        return MemoryReactorWrappers.query(delegate, entityClass);
    }

    @Override
    public <T> ReactorLambdaUpdateWrapper<T> update(Class<T> entityClass) {
        return MemoryReactorWrappers.update(delegate, entityClass);
    }

    @Override
    public <T> ReactorLambdaDeleteWrapper<T> delete(Class<T> entityClass) {
        return MemoryReactorWrappers.delete(delegate, entityClass);
    }

    @Override
    public Flux<Map<String, Object>> query(String sql, Object... params) {
        return Flux.defer(() -> Flux.fromIterable(delegate.querySql(sql, params)));
    }

    @Override
    public <T> Flux<T> query(String sql, Class<T> rowType, Object... params) {
        return Flux.defer(() -> {
            var out = Wraps.<T>list();
            for (var row : delegate.querySql(sql, params)) {
                out.add(MemorySqlLex.RowAccessor.toBean(row, rowType));
            }
            return Flux.fromIterable(out);
        });
    }

    @Override
    public Mono<Integer> execute(String sql, Object... params) {
        return Mono.fromSupplier(() -> delegate.executeSql(sql, params));
    }

    @Override
    public Flux<Integer> batch(String sql, List<Object[]> batchParams) {
        return Flux.fromIterable(batchParams).map(bp -> delegate.executeSql(sql, bp));
    }

    /**
     * 响应式加载文件，通过 {@link AsynchronousFileChannel} 非阻塞读取。
     *
     * <p>JSON 文件直接解析为 {@code List<Map>} 存入内存；
     * CSV 文件按行解析，首行为列名，支持引号包裹字段；
     * 其他格式回退到同步 {@link FileEngine#load(String, String)}。</p>
     *
     * @param name     数据源名称
     * @param filePath 文件路径
     * @return 当前引擎实例的 Mono
     */
    public Mono<FileReactorEngine> load(String name, String filePath) {
        File file = new File(filePath);
        Path path = Path.of(filePath);
        String ext = FileUtils.getExtension(filePath);

        if ("json".equalsIgnoreCase(ext)) {
            return readFileAsync(path)
                    .map(bytes -> parseJson(name, bytes))
                    .flatMap(list -> Mono.fromRunnable(() ->
                            // 必须走 registerRows 而不是 store：store 不注册表元数据，
                            // 写回链路（save/persistIfAuto）会因为找不到文件路径而永不落盘
                            delegate.registerRows(name, file, "json", list)
                    ).then(Mono.just(this)));
        }

        if ("csv".equalsIgnoreCase(ext) || "tsv".equalsIgnoreCase(ext)) {
            char sep = "tsv".equalsIgnoreCase(ext) ? '\t' : ',';
            return readFileAsync(path)
                    .map(bytes -> parseCsv(name, bytes, sep))
                    .flatMap(list -> Mono.fromRunnable(() ->
                            delegate.registerRows(name, file, "csv", list)
                    ).then(Mono.just(this)));
        }

 // 其他格式回退到同步 文件engine（boundedElastic 调度阻塞 I/O）
        return Mono.fromCallable(() -> {
            delegate.load(name, filePath);
            return this;
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * 通过异步文件通道非阻塞读取文件全部内容。
     * @param path 路径
     * @return 读取文件异步的结果
     */
    private static Mono<byte[]> readFileAsync(Path path) {
        return Mono.create(sink -> {
            try {
                AsynchronousFileChannel channel = AsynchronousFileChannel.open(path, StandardOpenOption.READ);
                ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
                channel.read(buffer, 0, null, new CompletionHandler<Integer, Void>() {
                    @Override
                    public void completed(Integer result, Void attachment) {
                        buffer.flip();
                        byte[] data = new byte[buffer.remaining()];
                        buffer.get(data);
                        try {
                            channel.close();
                        } catch (IOException e) {
                            log.debug("[FileReactor] 关闭文件通道失败: {}", e.getMessage());
                        }
                        sink.success(data);
                    }

                    @Override
                    public void failed(Throwable exc, Void attachment) {
                        try {
                            channel.close();
                        } catch (IOException e) {
                            log.debug("[FileReactor] 关闭文件通道失败: {}", e.getMessage());
                        }
                        sink.error(exc);
                    }
                });
            } catch (Exception e) {
                sink.error(e);
            }
        });
    }

    /**
     * 解析Json。
     *
     * @param name 名称，不允许为 null
     * @param bytes 字节数组，不允许为 null
     * @return 结果列表，无数据时为空列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJson(String name, byte[] bytes) {
        try {
            return MAPPER.readValue(bytes, JSON_LIST_TYPE);
        } catch (Exception e) {
            try {
                Map<String, Object> map = MAPPER.readValue(bytes, Map.class);
                List<Map<String, Object>> list = Wraps.list();
                list.add(map);
                return list;
            } catch (Exception e2) {
                throw new RuntimeException("JSON 解析失败: " + name, e);
            }
        }
    }

    /**
     * 解析Csv。
     *
     * @param name 名称，不允许为 null
     * @param bytes 字节数组，不允许为 null
     * @param separator 分隔符，不允许为 null
     * @return 结果列表，无数据时为空列表
     */
    private static List<Map<String, Object>> parseCsv(String name, byte[] bytes, char separator) {
        String content = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = content.split("\n");
        if (lines.length < 2) {
            throw new RuntimeException("CSV 文件缺少数据行: " + name);
        }
        String[] headers = parseLine(lines[0], separator);
        List<Map<String, Object>> result = Wraps.list(lines.length - 1);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] values = parseLine(line, separator);
            Map<String, Object> row = Wraps.linkedMap();
            for (int j = 0; j < headers.length && j < values.length; j++) {
                row.put(headers[j].trim(), values[j].trim());
            }
            result.add(row);
        }
        return result;
    }

    /**
     * 解析Line。
     *
     * @param line 方法入参 line
     * @param separator 分隔符，不允许为 null
     * @return 字符串 对象
     */
    private static String[] parseLine(String line, char separator) {
        List<String> fields = Wraps.list();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == separator && !inQuotes) {
                fields.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields.toArray(new String[0]);
    }

    /**
     * 获取底层同步 文件engine。
     * @return 获取delegate的结果
     */
    public FileEngine getDelegate() {
        return delegate;
    }

    /**
     * 关闭引擎，释放底层数据源资源。
     */
    public void close() {
        delegate.close();
    }
}

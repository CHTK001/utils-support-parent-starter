package com.chua.common.support.datasearch.conversation.spi;

import com.chua.common.support.datasearch.conversation.ConversationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import reactor.core.scheduler.Schedulers;

/**
* JSONL 会话解析器基类 — 封装「递归列文件 + 逐行惰性读取 + 子类行解析」模板。
*
* <p>子类只需提供数据源目录、文件后缀、工具标识和单行解析逻辑，
* 即可获得背压友好的响应式流式实现；磁盘读取与解析发生在订阅线程
* （boundedElastic）上，内存占用与数据总量无关。</p>
*
* @author CH
* @since 4.0.0.42
 */
public abstract class AbstractJsonlConversationParser implements ConversationParser {

    /** 日志记录器 */
    protected final Logger log = LoggerFactory.getLogger(getClass());

    /**
    * 返回会话文件根目录。
    *
    * @return 根目录路径
     */
    protected abstract Path rootDir();

    /**
    * 返回全部待扫描根目录；默认仅 {@link #rootDir()} 单目录，
    * 多数据源变体（如国际版/国内版并存）可重写。
    *
    * @return 根目录列表
     */
    protected List<Path> rootDirs() {
        return List.of(rootDir());
    }

    /**
    * 返回会话文件后缀过滤条件。
    *
    * @return 后缀（含点号，如 {@code ".jsonl"}）
     */
    protected abstract String fileSuffix();

    /**
    * 解析单行文本为零或多条消息记录。
    *
    * <p>实现必须自行捕获异常并返回空列表，不得向上抛出。</p>
    *
    * @param line 文件中的一行
    * @return 解析出的消息列表
     */
    protected abstract List<ConversationMessage> parseLine(String line);

    /**
    * 流式解析全部会话消息：递归列出 {@link #rootDir()} 下匹配后缀的文件，
    * 逐文件惰性读取并委托 {@link #parseLine(String)}。
    * @param file 文件
    * @return 流文件的结果
     /**
      * 流消息。
      * @return 流消息的结果
      */
      * @param file 文件
     */
    @Override
    public Flux<ConversationMessage> streamMessages() {
        List<Path> files = listTranscripts();
        if (files.isEmpty()) {
            log.debug("[{}] no transcript files under {}", name(), rootDir());
            return Flux.empty();
        }
        log.info("[{}] streaming from {} transcript files", name(), files.size());
        return Flux.fromIterable(files)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(this::streamFile, 4);
    }

    private Flux<ConversationMessage> streamFile(Path file) {
        return Flux.using(
                        () -> Files.newBufferedReader(file),
                        reader -> Flux.fromStream(reader.lines())
                                .map(this::parseLineSafe)
                                .flatMapIterable(l -> l),
                        reader -> {
                            try {
                                reader.close();
                            } catch (Exception ignored) {
                                // 忽略关闭异常
                            }
                        })
                .onErrorResume(e -> {
                    log.debug("[{}] read failed {}: {}", name(),
                            file.getFileName(), e.getMessage());
                    return Flux.empty();
                });
    }

    /**
    * 解析线safe。
    * @param line 线
    * @return 解析线safe的结果
     */
    private List<ConversationMessage> parseLineSafe(String line) {
        try {
            return parseLine(line);
        } catch (Exception e) {
            log.debug("[{}] parse failed: {}", name(), e.getMessage());
            return List.of();
        }
    }

    /**
    * 列表transcripts。
    * @return 列表transcripts的结果
     */
    private List<Path> listTranscripts() {
        List<Path> files = new java.util.ArrayList<>();
        for (Path root : rootDirs()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(fileSuffix()))
                        .forEach(files::add);
            } catch (IOException e) {
                log.warn("[{}] walk failed {}: {}", name(), root, e.getMessage(), e);
            }
        }
        return files;
    }
}

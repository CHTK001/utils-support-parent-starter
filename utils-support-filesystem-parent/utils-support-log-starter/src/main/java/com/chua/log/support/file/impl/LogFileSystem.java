package com.chua.log.support.file.impl;

import com.chua.common.support.file.FileSystem;
import com.chua.common.support.file.builder.ReadBuilder;
import com.chua.common.support.file.builder.WriteBuilder;
import com.chua.common.support.spi.annotations.Spi;

import java.io.File;

/**
 * 日志文件系统 SPI 实现。
 *
 * <p>通过 SPI 机制注册为 {@code "log"} 类型的文件系统实现。
 * 专门处理 {@code .log} 日志文件的读取与写入。</p>
 *
 * <h2>读取特性</h2>
 * <ul>
 *     <li>按行读取日志文件</li>
 *     <li>{@code grep} — 关键词 / 正则表达式行过滤</li>
 *     <li>{@code tail} — 读取末尾 N 行</li>
 *     <li>{@code timeRange} — 按时间范围过滤（自动识别常见日志时间戳格式）</li>
 * </ul>
 *
 * <h2>写入特性</h2>
 * <ul>
 *     <li>追加写入（{@code append} 模式）和覆盖写入</li>
 *     <li>自动时间戳前缀</li>
 *     <li>延迟批量写入（write + finish）</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * FileSystem logFs = FileSystem.create("log");
 *
 * // 读取：grep 过滤 + tail
 * List<String> errorLines = ((LogReadBuilder) logFs.read(new File("app.log")))
 *         .grep("ERROR")
 *         .tail(50)
 *         .lines();
 *
 * // 读取：时间范围过滤
 * List<String> todayLines = ((LogReadBuilder) logFs.read(new File("app.log")))
 *         .timeRange(LocalDateTime.now().minusDays(1), LocalDateTime.now())
 *         .lines();
 *
 * // 写入：追加模式 + 时间戳
 * logFs.write(new File("app.log"))
 *         .append()              // 追加模式
 *         .withTimestamp(true)   // 自动添加时间戳
 *         .write("这是一条日志")
 *         .write("另一条日志")
 *         .finish();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("log")
public class LogFileSystem implements FileSystem {

    @Override
    /** 获取Type */
    public String getType() {
        return "log";
    }

    @Override
    /** 读取 */
    public ReadBuilder read(File file) {
        return new LogReadBuilder(file);
    }

    @Override
    /** 写入 */
    public WriteBuilder write(File file) {
        return new LogWriteBuilder(file);
    }
}

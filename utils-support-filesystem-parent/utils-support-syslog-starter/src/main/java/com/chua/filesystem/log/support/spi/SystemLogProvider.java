package com.chua.filesystem.log.support.spi;

import com.chua.filesystem.log.support.model.LogEntry;
import com.chua.filesystem.log.support.model.LogQuery;

import java.util.List;

/**
 * 系统日志提供者 SPI 接口
 * <p>
 * 各平台实现此接口以提供系统日志检索能力：
 * <ul>
 *   <li>Windows : advapi32 FFM 调用 Event Log API</li>
 *   <li>Linux   : libsystemd FFM 调用 journald + /var/log 文件回退</li>
 *   <li>macOS   : ProcessBuilder 调用 log show + /var/log 文件回退</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SystemLogProvider {

    /**
     * 搜索系统日志
     *
     * @param query 查询条件 (支持通配符、级别过滤、分页)
     * @return 匹配的日志条目列表，按时间降序排列
     */
    List<LogEntry> search(LogQuery query);

    /**
     * 获取当前平台可用的日志源列表
     *
     * @return 日志源名称列表
     */
    List<String> getSources();

    /**
     * 判断当前提供者是否适配当前运行平台
     *
     * @return true 表示当前平台支持
     */
    boolean isPlatformSupported();
}

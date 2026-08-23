package com.chua.datasource.support.engine;

import com.chua.common.support.spi.annotations.Spi;

/**
 * 文件响应式引擎，对应同步侧 {@link FileEngine}。
 *
 * <p>通过 {@link DefaultReactorEngine} 将同步 FileEngine 包装为响应式，
 * 所有阻塞调用通过 {@code boundedElastic} 调度执行。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("file")
public class FileReactorEngine extends DefaultReactorEngine {

    /**
     * 创建文件响应式引擎，内部持有同步 {@link FileEngine}。
     */
    public FileReactorEngine() {
        super(new FileEngine());
    }
}
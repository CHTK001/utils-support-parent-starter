package com.chua.test.impl;

import com.chua.ast.support.annotation.AutoSpi;
import com.chua.common.support.spi.annotations.Extension;
import com.chua.test.spi.EmbeddingClient;

/**
 * @延伸 + @autospi: runtime prefers 注解 名称, 索引 only needs a bare discovery 线
 * (no {@code extjson=...} 别名 线, avoiding N×M 重复 registration).
 *
 * @author CH
 * @since 4.0.0.42
 */
@AutoSpi
@Extension("extjson")
public class ExtensionAnnotatedClient implements EmbeddingClient {

    @Override
    /**
     * 嵌入
    */
    public float[] embedding(String text) {
        return new float[0];
    }
}


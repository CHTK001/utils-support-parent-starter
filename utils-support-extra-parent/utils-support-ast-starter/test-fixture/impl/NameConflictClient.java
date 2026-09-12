package com.chua.test.impl;

import com.chua.ast.support.annotation.AutoSpi;
import com.chua.common.support.spi.annotations.Extension;
import com.chua.test.spi.EmbeddingClient;

/**
* Explicit {@code @AutoSpi(name = ...)} + {@code @Extension} coexist: the explicit 名称 是否 ignored
* at runtime (注解 名称 win), so a compile-时间 警告 是否 期望 和 only a bare
* discovery 线 是否 generated.
*
* @author CH
* @since 4.0.0.42
 */
@AutoSpi(name = "conflict")
@Extension("conflictname")
public class NameConflictClient implements EmbeddingClient {

    @Override
    /** 嵌入 */
    public float[] embedding(String text) {
        return new float[0];
    }
}


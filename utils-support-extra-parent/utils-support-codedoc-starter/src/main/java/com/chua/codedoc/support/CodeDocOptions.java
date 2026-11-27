package com.chua.codedoc.support;

import com.chua.common.support.ai.chat.ChatClient;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 构建器生成的不可变配置快照，不对共享 ChatClient 做参数或生命周期修改。
 *
 * @author CH
 * @since 4.0.0.51
 */
record CodeDocOptions(ChatClient chatClient, Path directory, Path outputDirectory,
                      Set<String> includeTypes, Set<String> excludeTypes,
                      List<String> includes, List<String> excludes, Charset charset,
                      String title, String prompt, String instructions, String cacheNamespace,
                      boolean incremental, boolean failFast, int maxDepth,
                      int maxFiles, long maxFileBytes, int chunkChars, int retries, int concurrency,
                      Consumer<CodeDocs.Progress> progress) {
    CodeDocOptions {
        includeTypes = Set.copyOf(includeTypes);
        excludeTypes = Set.copyOf(excludeTypes);
        includes = List.copyOf(includes);
        excludes = List.copyOf(excludes);
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency 必须 > 0");
        }
    }
}

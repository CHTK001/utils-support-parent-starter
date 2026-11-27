package com.chua.codedoc.support;

import java.nio.file.Path;
import java.util.List;

/**
 * 一次文档生成的真实结果。部分文件失败时仍生成站点，并在问题清单中列出。
 *
 * @param indexFile 生成站点入口
 * @param scannedFiles 匹配扫描规则的文件数
 * @param documentedFiles 成功生成文档的文件数
 * @param cachedFiles 命中缓存的文件数
 * @param modelCalls 实际模型调用次数，包含重试
 * @param issues 跳过与失败的问题清单
 * @author CH
 * @since 4.0.0.51
 */
public record CodeDocResult(Path indexFile, int scannedFiles, int documentedFiles,
                            int cachedFiles, int modelCalls, List<Issue> issues) {
    /** 创建不可变的生成结果。 */
    public CodeDocResult {
        issues = List.copyOf(issues);
    }

    /**
     * 是否所有匹配的文件均成功生成，且扫描和缓存写入未出现问题。
     *
     * @return 无问题时为 true
     */
    public boolean complete() {
        return scannedFiles == documentedFiles && issues.isEmpty();
    }

    /**
     * 生成问题，只暴露相对路径和阶段，不保存可能携带凭据的异常原文。
     *
     * @param path 相对源码路径
     * @param stage SCAN、READ、ANALYZE 或 CACHE
     * @param message 问题说明
     */
    public record Issue(String path, String stage, String message) {
    }
}

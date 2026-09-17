package com.chua.common.support.ai.rag;

import java.util.List;

/**
* GraphRAG 上下文提供者接口（可选）。
* <p>
* 在 RAG 查询时注入知识图谱上下文到 prompt 中，
* 将检索到的文档片段与知识图谱关系结合，增强 LLM 的回答质量。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@FunctionalInterface
public interface GraphContextProvider {

    /**
    * 根据查询和命中的文档，获取知识图谱上下文。
    *
    * @param query         用户查询
    * @param documentIds   命中的文档 ID 列表
    * @param chunkContents 命中的文档片段内容列表
    * @return Markdown 格式的知识图谱上下文，为 null 则忽略
    */
    String getContext(String query, List<String> documentIds, List<String> chunkContents);
}

package com.chua.codedoc.support;

import java.util.List;

/**
 * 源码文档的结构化模型；文件路径、源码及页面 ID 由扫描器提供。
 *
 * @param id 稳定页面 ID
 * @param path 相对源码路径
 * @param language 文件类型
 * @param summary 功能概述
 * @param apis 功能和 API
 * @param notes 注意事项
 * @param source 扫描时的源码快照
 * @author CH
 * @since 4.0.0.51
 */
public record CodeDocPage(String id, String path, String language, String summary,
                          List<Api> apis, List<String> notes, String source) {
    /** 创建不可变页面。 */
    public CodeDocPage {
        apis = apis == null ? List.of() : List.copyOf(apis);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /**
     * 单个可用功能。
     *
     * @param name 功能名称
     * @param signature 源码中的声明或签名
     * @param description 功能、用途、约束说明
     * @param parameters 参数说明
     * @param returns 返回值说明
     * @param examples 示例；生成示例均未执行验证
     * @param notes 异常、边界和其他注意事项
     * @param startLine 源码起始行，1-based
     * @param endLine 源码结束行，1-based
     */
    public record Api(String name, String signature, String description,
                      List<Parameter> parameters, String returns, List<Example> examples,
                      List<String> notes, int startLine, int endLine) {
        /** 创建不可变功能说明。 */
        public Api {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
            examples = examples == null ? List.of() : List.copyOf(examples);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    /**
     * 参数元数据。不确定的类型、默认值或约束应写明“源码未明确”。
     *
     * @param name 参数名
     * @param type 参数类型
     * @param description 含义、默认值、是否可空和约束
     */
    public record Parameter(String name, String type, String description) {
    }

    /**
     * 由模型生成、未执行验证的调用示例。
     *
     * @param title 示例用途
     * @param code 示例代码
     * @param explanation 示例说明，不得伪称已执行成功
     */
    public record Example(String title, String code, String explanation) {
    }
}

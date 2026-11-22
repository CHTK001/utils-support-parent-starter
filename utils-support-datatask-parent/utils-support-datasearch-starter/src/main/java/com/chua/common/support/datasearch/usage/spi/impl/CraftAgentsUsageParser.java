package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Path;
/**
 * CraftAgentsUsageParser类，提供相关能力。
 *
 * @author CH
 * @since 1.0.0
 */
@Spi("craft")
public class CraftAgentsUsageParser extends AgentSessionUsageParser {

    /**
     * 默认构造：解析当前用户主目录。
     */
    public CraftAgentsUsageParser() {
        super();
    }

    /**
     * 指定解析根目录构造。
     *
     * @param userHomes 逗号分隔的用户主目录列表
     */
    public CraftAgentsUsageParser(String userHomes) {
        super(userHomes);
    }

    @Override
    public String name() {
        return "craft";
    }

    @Override
    protected Path sessionRoot(Path home) {
        return home.resolve(".craft-agent").resolve("sessions");
    }

    /**
     * Craft 仅识别 {@code session.jsonl} 命名的会话文件，
     * 跳过 sessions/&lt;id&gt;/ 下其他产物（日志、元数据等）。
     */
    @Override
    protected String transcriptExtension() {
        return ".jsonl";
    }
}

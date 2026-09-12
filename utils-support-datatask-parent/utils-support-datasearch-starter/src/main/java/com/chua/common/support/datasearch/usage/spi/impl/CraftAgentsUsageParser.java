package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Path;
@Spi("craft")
public class CraftAgentsUsageParser extends AgentSessionUsageParser {

    @Override
    public String name() {
        return "craft";
    }

    @Override
    protected Path sessionRoot() {
        return Path.of(System.getProperty("user.home"), ".craft-agent", "sessions");
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

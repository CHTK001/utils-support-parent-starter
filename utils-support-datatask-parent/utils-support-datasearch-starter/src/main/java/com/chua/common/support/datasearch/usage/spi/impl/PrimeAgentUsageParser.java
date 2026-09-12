package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Path;

/**
 * Prime Agent（PrimeIntellect）用量解析器。
 *
 * <p>Prime Agent 持久化与 pi 相同的元数据-only 助手用量信封，但采用扁平
 * 会话目录布局 {@code ~/.prime/agent/sessions/<session-id>.jsonl}。
 * 路径与游标命名空间与 pi 相互独立，两者共存时不会互相抑制或重复计数。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Spi("prime-agent")
public class PrimeAgentUsageParser extends AgentSessionUsageParser {

    @Override
    public String name() {
        return "prime-agent";
    }

    @Override
    protected Path sessionRoot() {
        return Path.of(System.getProperty("user.home"), ".prime", "agent", "sessions");
    }
}

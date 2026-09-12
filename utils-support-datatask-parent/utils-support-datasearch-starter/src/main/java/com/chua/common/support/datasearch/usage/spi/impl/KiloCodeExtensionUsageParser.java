package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.spi.annotations.Spi;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
* Kilo Code VS Code 扩展用量解析器。
*
* <p>Kilo Code（kilocode，Cline 派生扩展）将任务持久化到
* {@code <IDE>/User/globalStorage/kilocode.kilo-code/tasks/<task>/ui_messages.json}，
* 助手消息携带真实 {@code usage:{inputTokens,outputTokens,cacheReadInputTokens,
* cacheWriteTokens,costUSD}}。不同 IDE 安装（Code/Cursor/CodeBuddy...）共用同一布局，
* 由 {@link VscodeExtensionTaskUsageParser} 遍历各 IDE 的 globalStorage 根。</p>
*
* <p>注意与 CLI 版 Kilo（{@code @Spi("kilo")}，OpenCode-fork SQLite）区分：
* 本解析器只读 VS Code 扩展的任务文件，两者数据源不重叠。</p>
*
* @author CH
* @since 4.0.0.43
 */
@Spi("kilo-code")
public class KiloCodeExtensionUsageParser extends VscodeExtensionTaskUsageParser {

    @Override
    protected List<String> extensionIdPrefixes() {
        return List.of("kilocode");
    }

    @Override
    protected String taskDirName() {
        return "tasks";
    }

    @Override
    public String name() {
        return "kilo-code";
    }

    @Override
    public Flux<com.chua.common.support.ai.AiUsage> streamAll() {
        return fromTaskFiles();
    }
}

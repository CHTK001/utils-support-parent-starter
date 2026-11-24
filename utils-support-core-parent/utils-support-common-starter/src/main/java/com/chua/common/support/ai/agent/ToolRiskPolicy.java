package com.chua.common.support.ai.agent;

import java.util.Locale;

/**
 * 工具调用风险分级策略（底层共享契约）。
 *
 * <p>对一次「待确认的工具调用」按动作与资源做启发式分级，供两类消费方使用：</p>
 * <ul>
 *   <li>opencode 网关（{@code OpencodeServerAgent#onPermissionAsked}）——
 *       把分级结果写入 {@link ConfirmationItem#getRiskLevel()}，前端据此渲染
 *       不同颜色/图标，并对危险操作走强确认；</li>
 *   <li>ai-starter 的「测试权限」端点——只读地回答
 *       「这个工具调用是什么风险、要不要确认、是否强确认」，不执行任何工具。</li>
 * </ul>
 *
 * <p>分级口径：</p>
 * <ul>
 *   <li>{@link #RISK_LOW}：只读类（read/grep/glob/list 等），通常无需人工确认；</li>
 *   <li>{@link #RISK_MEDIUM}：普通写入与网络访问（write/edit/fetch 等），普通确认；</li>
 *   <li>{@link #RISK_HIGH}：影响面较大的操作（shell、安装/发布/迁移等），警示确认；</li>
 *   <li>{@link #RISK_DANGEROUS}：带破坏性特征的 shell/删除/系统操作（rm -rf、sudo、
 *       git push --force 等），必须强确认（二次点击），不建议纳入「全部同意」。</li>
 * </ul>
 *
 * <p>本策略是启发式：宁可把不确定的动作归入更高一级，也不放过破坏性操作。
 * 同一能力只此一份实现（上层不得重复造分级逻辑）。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public final class ToolRiskPolicy {

    /**
     * 风险等级：低（只读，通常无需确认）
     */
    public static final String RISK_LOW = "low";

    /**
     * 风险等级：中（普通写入/网络访问，普通确认）
     */
    public static final String RISK_MEDIUM = "medium";

    /**
     * 风险等级：高（shell/安装/发布等，警示确认）
     */
    public static final String RISK_HIGH = "high";

    /**
     * 风险等级：危险（破坏性操作，强确认）
     */
    public static final String RISK_DANGEROUS = "dangerous";

    /**
     * shell 类动作关键字（小写）
     */
    private static final String[] SHELL_ACTIONS = {"shell", "bash", "command", "exec", "terminal", "process"};

    /**
     * 破坏性命令特征（对资源文本做小写包含匹配）
     */
    private static final String[] DESTRUCTIVE_SIGNATURES = {
            "rm -rf", "rm -fr", "rm -r ", "rmdir", "sudo ", "sudo\t", "mkfs", "dd if",
            "shutdown", "reboot", "poweroff", "halt", "format ", "del /", "rd /",
            "git push --force", "git push -f", "git reset --hard", "drop table",
            "truncate table", "chmod 777", "chown -r", ":(){", "fork bomb",
            "shred ", "wipefs", "taskkill", "pkill ", "kill -9", "> /dev/sd",
            "--force", "credential", "private key", ".ssh", "id_rsa"
    };

    /**
     * 高风险动作关键字（非 shell，但影响面大）
     */
    private static final String[] HIGH_IMPACT_ACTIONS = {
            "install", "uninstall", "publish", "deploy", "migrate", "drop", "grant", "revoke"
    };

    /**
     * 写入类动作关键字
     */
    private static final String[] WRITE_ACTIONS = {
            "write", "edit", "patch", "create", "update", "move", "rename", "upload", "save"
    };

    /**
     * 网络类动作关键字
     */
    private static final String[] NETWORK_ACTIONS = {
            "fetch", "web", "http", "download", "search", "crawl"
    };

    /**
     * 只读类动作关键字
     */
    private static final String[] READ_ONLY_ACTIONS = {
            "read", "grep", "glob", "list", "view", "ls", "cat", "tree", "stat", "head", "tail"
    };

    private ToolRiskPolicy() {
    }

    /**
     * 是否 shell/命令执行类动作。
     *
     * <p>shell 类工具破坏面最大，审批裁决（{@code AgentApprovalGate}）对它有
     * 独立的收紧口径（非管理员尽力拒绝、缺用户上下文不放行）；判断逻辑统一在
     * 本类，避免各裁决器各自维护一份关键字清单。</p>
     *
     * @param action 工具/权限动作名，可为 null
     * @return true 表示 shell 类动作
     */
    public static boolean isShellAction(String action) {
        String a = action == null ? "" : action.toLowerCase(Locale.ROOT);
        return containsAny(a, SHELL_ACTIONS);
    }

    /**
     * 对一次工具调用做风险分级。
     *
     * @param action        工具/权限动作名（如 shell、edit、webfetch、mcp__x__y）
     * @param resourcesText 资源摘要文本（命令行、文件路径等，可为空）
     * @return {@link #RISK_LOW} / {@link #RISK_MEDIUM} / {@link #RISK_HIGH} / {@link #RISK_DANGEROUS}
     */
    public static String classify(String action, String resourcesText) {
        String a = action == null ? "" : action.toLowerCase(Locale.ROOT);
        String res = resourcesText == null ? "" : resourcesText.toLowerCase(Locale.ROOT);

        // shell/命令执行：本身即高风险；带破坏性特征升级为危险
        if (containsAny(a, SHELL_ACTIONS)) {
            return hasDestructiveSignature(res) ? RISK_DANGEROUS : RISK_HIGH;
        }
        // 安装/发布/删除类：高风险；资源带破坏性特征升级为危险
        if (containsAny(a, HIGH_IMPACT_ACTIONS)) {
            return hasDestructiveSignature(res) ? RISK_DANGEROUS : RISK_HIGH;
        }
        // 普通写入与网络访问：中风险
        if (containsAny(a, WRITE_ACTIONS) || containsAny(a, NETWORK_ACTIONS)) {
            return RISK_MEDIUM;
        }
        // 只读类：低风险
        if (containsAny(a, READ_ONLY_ACTIONS)) {
            return RISK_LOW;
        }
        // 未知动作：按中风险处理（需要普通确认），不默认放行
        return RISK_MEDIUM;
    }

    /**
     * 是否危险等级（前端据此走强确认流程）。
     *
     * @param riskLevel 风险等级
     * @return true 表示危险
     */
    public static boolean isDangerous(String riskLevel) {
        return RISK_DANGEROUS.equalsIgnoreCase(riskLevel);
    }

    /**
     * 是否需要人工确认（低风险可自动放行）。
     *
     * @param riskLevel 风险等级
     * @return true 表示需要确认
     */
    public static boolean requiresConfirmation(String riskLevel) {
        return !RISK_LOW.equalsIgnoreCase(riskLevel);
    }

    /**
     * 资源文本是否带破坏性特征。
     *
     * @param loweredRes 小写资源文本
     * @return true 表示命中破坏性特征
     */
    private static boolean hasDestructiveSignature(String loweredRes) {
        for (String signature : DESTRUCTIVE_SIGNATURES) {
            if (loweredRes.contains(signature)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 文本是否包含任一关键字。
     *
     * @param text      小写文本
     * @param keywords  关键字表（小写）
     * @return true 表示命中
     */
    private static boolean containsAny(String text, String[] keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}

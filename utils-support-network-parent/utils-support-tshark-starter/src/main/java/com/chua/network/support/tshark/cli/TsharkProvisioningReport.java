package com.chua.network.support.tshark.cli;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * tshark 命令行工具装配结果。
 *
 * <p>把"最终用哪一级、拿到什么版本、失败在何处、接下来该做什么"一次说清，
 * 避免调用方在失败时只能看到一句"未找到 tshark"。</p>
 *
 * @param available   是否成功拿到可用的 tshark
 * @param source      成功所用层级
 * @param executable  tshark 可执行文件路径，未成功时为 {@code null}
 * @param version     探测到的版本，未成功时为 {@code null}
 * @param attempts    逐级尝试的过程记录，按发生顺序排列
 * @param guidance    失败时的人工安装指引，成功时为 {@code null}
 * @author CH
 * @since 4.0.0.42
 */
public record TsharkProvisioningReport(
        boolean available,
        Source source,
        Path executable,
        String version,
        List<Attempt> attempts,
        String guidance
) {

    /**
     * 规范构造器：对尝试过程记录做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。本记录是装配结果的对外快照，
     * {@link #describe()} 会按顺序遍历该列表；直接持有调用方的可变列表会让已发布的
     * 装配报告在事后被改写。</p>
     *
     * <p>两个工厂方法的入参标注为非空且元素为 {@link Attempt} 记录，故直接使用
     * {@link List#copyOf(List)}。</p>
     *
     * @param available  是否成功拿到可用的 tshark
     * @param source     成功所用层级
     * @param executable tshark 可执行文件路径
     * @param version    探测到的版本
     * @param attempts   逐级尝试的过程记录
     * @param guidance   失败时的人工安装指引
     */
    public TsharkProvisioningReport {
        attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts 不能为 null"));
    }

    /**
     * tshark 来源层级。
     */
    public enum Source {

        /**
         * 显式路径或环境变量指定
         */
        EXPLICIT,

        /**
         * 从 PATH 或常见安装目录定位到
         */
        LOCATED,

        /**
         * 由系统包管理器安装
         */
        PACKAGE_MANAGER,

        /**
         * 由本模块下载并安装
         */
        DOWNLOADED,

        /**
         * 未获取到
         */
        NONE
    }

    /**
     * 单次尝试的记录。
     *
     * @param stage  尝试的阶段
     * @param target 尝试目标，如镜像地址或包管理器名
     * @param success 是否成功
     * @param detail  结果说明
     */
    public record Attempt(String stage, String target, boolean success, String detail) {
    }

    /**
     * 构造成功结果。
     *
     * @param source     来源层级
     * @param executable 可执行文件路径
     * @param version    版本号
     * @param attempts   过程记录
     * @return 报告
     */
    @Nonnull
    public static TsharkProvisioningReport success(@Nonnull Source source, @Nonnull Path executable,
                                                    @Nullable String version, @Nonnull List<Attempt> attempts) {
        return new TsharkProvisioningReport(true, source, executable, version, List.copyOf(attempts), null);
    }

    /**
     * 构造失败结果。
     *
     * @param attempts 过程记录
     * @param guidance 人工安装指引
     * @return 报告
     */
    @Nonnull
    public static TsharkProvisioningReport failure(@Nonnull List<Attempt> attempts, @Nonnull String guidance) {
        return new TsharkProvisioningReport(false, Source.NONE, null, null, List.copyOf(attempts), guidance);
    }

    /**
     * 汇总过程记录为可读文本，便于日志与接口直接输出。
     *
     * @return 多行文本
     */
    @Nonnull
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("tshark 装配").append(available ? "成功" : "失败")
                .append("，来源=").append(source);
        if (executable != null) {
            sb.append("，路径=").append(executable);
        }
        if (version != null) {
            sb.append("，版本=").append(version);
        }
        for (Attempt attempt : attempts) {
            sb.append(System.lineSeparator()).append("  [")
                    .append(attempt.success() ? "OK" : "--").append("] ")
                    .append(attempt.stage()).append(" -> ").append(attempt.target());
            if (attempt.detail() != null) {
                sb.append(" : ").append(attempt.detail());
            }
        }
        if (guidance != null) {
            sb.append(System.lineSeparator()).append(guidance);
        }
        return sb.toString();
    }
}

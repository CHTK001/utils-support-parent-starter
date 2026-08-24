/**
 * 轻量惰性分支工具 —— 以流式链替代 if-else / try-catch。
 *
 * <p>核心入口 {@link com.chua.common.support.task.branch.Branch}：
 * {@code of(seed)} 起链，{@code when/elseIf/otherwise} 与内置判断构成条件组，
 * {@code recover/onError} 承接异常，{@code protect} 挂接熔断器，
 * 终端 {@code get()} 触发求值、{@code afterBranch()} 以结果开启新链。</p>
 *
 * <p>与 {@code task.pipeline} 的关系：本包是独立轻量工具，不依赖流水线引擎，
 * 适合替换单方法内的分支逻辑；跨节点编排仍请使用 pipeline 框架。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
package com.chua.common.support.task.branch;

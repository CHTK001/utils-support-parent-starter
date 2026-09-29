package com.chua.starter.datasync.config;

import com.chua.starter.datasync.mapping.DataSyncFieldMapping;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认数据同步配置定义。
 *
 * <p>一条配置描述「从哪个输入源实例读、按哪些字段规则转换、以多大批量投递到哪个输出
 * 执行器再写入哪个输出 Sink 实例」这一整条链路的声明式取值，承载
 * {@link DataSyncConfigDefinition} 接口的全部访问器，并额外保留一个接口未声明的
 * {@code mappingId}。</p>
 *
 * <p>本 record 是公开配置载体，可由 YAML 解析、数据库加载或第三方
 * {@link DataSyncConfigDefinition} 实现喂入；紧凑构造器刻意不校验任何组件，
 * 因此除 {@code batch}（基本类型）外全部允许为 {@code null}，「允许为空」是既有契约
 * 的一部分，详见下方各组件说明与 {@link #DefaultDataSyncConfigDefinition} 规范构造器。</p>
 *
 * @param mappingId 映射 标识，声明这条配置归属哪条映射。本接口
 *                  {@link DataSyncConfigDefinition} 未声明该访问器，仅本 record 提供；
 *                  由 {@code YamlConfigFileParser} 构造时取自 YAML 的 {@code mappingId} 键
 *                  （缺键为 {@code null}）。取值在 {@code DefaultSyncDataSchedulerManager} 中
 *                  作为映射注册键、触发器缓存键、执行时间记录键与读取位点归属标记使用，
 *                  允许为 {@code null} 或空白，此时调度器记错误日志并跳过执行
 * @param inputId 输入 标识，声明本配置的输入端名称，用于路由与日志区分。
 *                取值来源为 YAML 的 {@code inputId} 键。允许为 {@code null}
 * @param sourceId 输入 源 实例 标识，作为 {@code DataSyncAgentServer#getSource} 的查询键定位
 *                 实际的数据源实例；解析出的源必须是 {@code Direction.INPUT} 方向，否则阻止执行。
 *                 取值来源为 YAML 的 {@code sourceId} 键。
 *                 允许为 {@code null} 或空白，此时调度器记警告并跳过本轮
 * @param outputId 输出 标识，作为 {@code ExecutorManager#getExecutor} 的查询键定位响应式执行器，
 *                 并参与构造订阅键（{@code outputId + "#" + sinkId}）以保证同一执行器只订阅一次。
 *                 取值来源为 YAML 的 {@code outputId} 键。允许为 {@code null}
 * @param sinkId 输出 Sink 实例 标识，作为 {@code DataSyncAgentServer#getSink} 的查询键定位输出端；
 *               解析出的 Sink 必须是 {@code Direction.OUTPUT} 方向，否则阻止执行。
 *               取值来源为 YAML 的 {@code sinkId} 键。
 *               允许为 {@code null} 或空白，此时调度器记警告并跳过本轮
 * @param mappings 字段 映射 列表，声明源字段到目标字段的逐列转换关系，元素为
 *                 {@link DataSyncFieldMapping}（含源字段名、目标字段名、类型转换器名）。
 *                 取值来源为 YAML 的 {@code mappings} 列表，无该键时为空列表。
 *                 紧凑构造器对其做防御性拷贝并包装为不可变列表，元素本身允许为
 *                 {@code null}（故不用拒绝空元素的 {@code List.copyOf}）。
 *                 整个列表允许为 {@code null}，调度器降级为 {@code List.of()} 跳过字段转换
 * @param batch 单批 处理的记录条数，作为响应式管线的 {@code buffer} 缓冲大小，单位为「条记录」。
 *              取值来源为 YAML 的 {@code batch} 键，缺键或非数值时解析器取默认 100。
 *              必须为正数才生效；小于等于 0 时调度器降级为默认批量
 *              {@code DEFAULT_BATCH_SIZE}，不会抛异常
 * @param cronType 定时 类型 标识，用于区分调度策略的种类；取值来源为 YAML 的
 *                 {@code cronType} 键，缺键为 {@code null}。注意：当前实现未对该值做分支分派，
 *                 实际生效的调度条件只由触发器与 {@code cron} 决定，
 *                 本组件目前仅作为配置元数据透传留存
 * @param cron 定时 表达式，为 6 字段 Cron 串（{@code 秒 分 时 日 月 周}，字段以空白分隔，
 *              支持 {@code * ? , - / L} 记号），单位为时间点而非时长。
 *              取值来源为 YAML 的 {@code cron} 键。仅在本配置没有显式触发器时被解析使用；
 *              允许为 {@code null} 或空白，此时降级为固定 1 分钟间隔的 SimpleTrigger；
 *              表达式非法时同样降级并记警告，不向调用方抛出异常
 * @param params 额外 参数 映射，以 {@code 源实例} 视角透传给 {@code DataSyncAgentSource#read}，
 *               调度器会先复制成可变映射再把持久化位点以 {@code offset} 键回填进去，
 *               因此本映射中的 {@code offset} 项会被覆盖。取值来源为 YAML 的 {@code params} 映射，
 *               缺键时为空映射。紧凑构造器做防御性拷贝并包装为不可变映射，且刻意保留迭代顺序
 *               （用 {@link LinkedHashMap} 而非 {@code Map.copyOf}），使参数顺序与配置文件保持一致；
 *               值允许为 {@code null}。整个映射允许为 {@code null}，调度器降级为空映射
 * @author CH
 * @since 4.0.0.42
 */
public record DefaultDataSyncConfigDefinition(
        String mappingId,
        String inputId,
        String sourceId,
        String outputId,
        String sinkId,
        List<DataSyncFieldMapping> mappings,
        int batch,
        String cronType,
        String cron,
        Map<String, Object> params
) implements DataSyncConfigDefinition {

    /**
     * 规范构造器：字段映射列表与参数映射做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。</p>
     *
     * <p>两个集合刻意保留 空 语义：本 record 是公开配置载体，可由 YAML 解析、数据库加载
     * 或第三方 {@link DataSyncConfigDefinition} 实现喂入，而调用方
     * {@code DefaultSyncDataSchedulerManager} 明确写过
     * {@code mapping.mappings() == null ? List.of() : ...} 与
     * {@code mapping.params() == null ? new HashMap<>() : ...} 的降级分支，
     * 改成拒绝 空 值等于把既有降级路径变成 空指针。</p>
     *
     * <p>采用 {@link ArrayList} / {@link LinkedHashMap} 快照 + {@link Collections} 包装
     * 而非 {@code List.copyOf} / {@code Map.copyOf}：后两者既拒绝 空 元素，又不保证
     * {@code params} 的迭代顺序，而参数顺序需与配置文件保持一致。</p>
     *
     * @param mappings 字段映射列表
     * @param params 参数映射
     */
    public DefaultDataSyncConfigDefinition {
        mappings = mappings == null ? null : Collections.unmodifiableList(new ArrayList<>(mappings));
        params = params == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }
}

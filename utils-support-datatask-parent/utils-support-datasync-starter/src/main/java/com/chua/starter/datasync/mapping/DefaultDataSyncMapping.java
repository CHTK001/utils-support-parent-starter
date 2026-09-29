package com.chua.starter.datasync.mapping;

import com.chua.starter.datasync.config.DataSyncConfigDefinition;
import com.chua.starter.datasync.config.DirectoryConfigDefinition;
import com.chua.starter.datasync.config.FileConfigDefinition;
import com.chua.starter.datasync.config.TextConfigDefinition;
import com.chua.starter.datasync.model.DataSyncMapping;
import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认数据同步映射。
 *
 * <p>一条映射描述「从哪个输入源实例读、按哪些字段规则转换、以多大批量投递到哪个输出
 * 执行器再写入哪个输出 Sink 实例」这一整条链路，由 {@code DatabaseMappingLoader} 从数据库
 * 载入，或由 {@code YamlConfigFileParser} 解析 YAML 后经 {@link DataSyncConfigDefinition}
 * 转译得到。</p>
 *
 * <p>本 record 是 {@code DataSyncMapping} 的公开 API 实现，构造器刻意不校验任何组件：
 * {@code DefaultSyncDataSchedulerManager#executeMapping} 会在运行期逐项判空并降级，
 * 因此这里的「允许为空」是既有契约的一部分，而非疏漏，详见下方各组件说明。</p>
 *
 * @param mappingId 映射 标识，全局唯一，作为 {@code DefaultDataSyncMappingManager} 的注册键、
 *                  触发器缓存键、执行时间记录键，以及读取位点 {@code SyncDataOffset} 的归属标记。
 *                  取值来源为数据库映射表主键或配置声明。允许为 {@code null} 或空白字符串，
 *                  但此时 {@code executeMapping} 会直接记错误日志并跳过执行，不会抛异常
 * @param inputId 输入 标识，声明本映射的输入端名称，用于路由与日志区分。
 *                  取值来源为 {@link DataSyncConfigDefinition#inputId()}（YAML 的 {@code inputId} 键）。
 *                  允许为 {@code null}
 * @param sourceId 输入 源 实例 标识，作为 {@code DataSyncAgentServer#getSource} 的查询键定位
 *                 实际的数据源实例；解析出的源必须是 {@code Direction.INPUT} 方向，否则阻止执行。
 *                 取值来源为 {@link DataSyncConfigDefinition#sourceId()}。
 *                 允许为 {@code null} 或空白，此时调度器记警告并跳过本轮
 * @param outputId 输出 标识，作为 {@code ExecutorManager#getExecutor} 的查询键定位响应式执行器，
 *                 并参与构造订阅键（{@code outputId + "#" + sinkId}）以保证同一执行器只订阅一次。
 *                 取值来源为 {@link DataSyncConfigDefinition#outputId()}。允许为 {@code null}
 * @param sinkId 输出 Sink 实例 标识，作为 {@code DataSyncAgentServer#getSink} 的查询键定位输出端；
 *               解析出的 Sink 必须是 {@code Direction.OUTPUT} 方向，否则阻止执行。
 *               取值来源为 {@link DataSyncConfigDefinition#sinkId()}。
 *               允许为 {@code null} 或空白，此时调度器记警告并跳过本轮
 * @param config 原始 配置 定义，保留映射的来源配置，供读取参数补全与排障时回查。
 *               从 {@code DataSyncConfigDefinition} 构造时由二参构造器直接透传；
 *               由 {@code DatabaseMappingLoader} 等直接调用规范构造器时可能显式传入
 *               {@code null}，因此允许为空，调用方须自行判空
 * @param mappings 字段 映射 列表，声明源字段到目标字段的逐列转换关系，元素为
 *                 {@link DataSyncFieldMapping}（含源字段名、目标字段名、类型转换器名）。
 *                 规范构造器会对其做防御性拷贝并包装为不可变列表，元素本身允许为
 *                 {@code null}（故不用拒绝空元素的 {@code List.copyOf}）。
 *                 整个列表允许为 {@code null}，调度器会降级为 {@code List.of()} 跳过字段转换
 * @param batch 单批 处理的记录条数，作为响应式管线的 {@code buffer} 缓冲大小；
 *              取值来源为 {@link DataSyncConfigDefinition#batch()}，单位为「条记录」。
 *              必须为正数才生效；小于等于 0 时调度器降级为默认批量
 *              {@code DEFAULT_BATCH_SIZE}，不会抛异常
 * @param cronType 定时 类型 标识，用于区分调度策略的种类；取值来源为
 *                 {@link DataSyncConfigDefinition#cronType()}（YAML 的 {@code cronType} 键），
 *                 允许为 {@code null}。注意：当前实现未对该值做分支分派，
 *                 实际生效的调度条件只由 {@code trigger} 与 {@code cron} 决定，
 *                 本组件目前仅作为配置元数据透传留存
 * @param cron 定时 表达式，为 6 字段 Cron 串（{@code 秒 分 时 日 月 周}，字段以空白分隔，
 *              支持 {@code * ? , - / L} 记号）。取值来源为
 *              {@link DataSyncConfigDefinition#cronType()} 同级的 {@code cron} 键。
 *              仅在 {@link #trigger} 为 {@code null} 时被解析使用；允许为 {@code null}
 *              或空白，此时降级为固定 1 分钟间隔的 SimpleTrigger；表达式非法时
 *              同样降级并记警告，不向调用方抛出异常
 * @param params 额外 参数 映射，以 {@code 源实例} 视角透传给 {@code DataSyncAgentSource#read}，
 *               调度器会先复制成可变 {@link HashMap} 再把持久化位点以 {@code offset} 键回填进去，
 *               因此本映射中的 {@code offset} 项会被覆盖。规范构造器做防御性拷贝并包装为
 *               不可变映射，且刻意保留迭代顺序（用 {@link LinkedHashMap} 而非
 *               {@code Map.copyOf}），使参数顺序与配置文件保持一致；值允许为 {@code null}。
 *               整个映射允许为 {@code null}，调度器降级为空 {@link HashMap}
 * @param trigger 触发 器，优先于 {@link #cron} 用于判断调度条件，并为 {@code nextExecutionTime}
 *                结果提供去重基准。取值来源为调用方显式注入；由
 *                {@link DataSyncConfigDefinition} 构造时因配置项尚未生成触发器而固定传
 *                {@code null}，故允许为空，此时由调度器从 {@code cron} 现场构造并缓存
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public record DefaultDataSyncMapping(
        String mappingId,
        String inputId,
        String sourceId,
        String outputId,
        String sinkId,
        DataSyncConfigDefinition config,
        List<DataSyncFieldMapping> mappings,
        int batch,
        String cronType,
        String cron,
        Map<String, Object> params,
        com.chua.common.support.task.scheduler.Trigger trigger
) implements DataSyncMapping {

    /**
     * 规范构造器：字段映射列表与参数映射做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。</p>
     *
     * <p>两个集合刻意保留 空 语义：{@code DefaultDataSyncMapping} 的规范构造器是 公开 API，
     * 既可被 {@code DatabaseMappingLoader} 直接调用，也可经
     * {@code createFromConfig} 接收第三方 {@link DataSyncConfigDefinition} 的返回值，
     * 而 {@code DefaultSyncDataSchedulerManager} 明确写过
     * {@code mapping.mappings() == null ? List.of() : ...} 与
     * {@code mapping.params() == null ? new HashMap<>() : ...} 的降级分支，
     * 改成拒绝 空 值等于把既有降级路径变成 空指针。</p>
     *
     * <p>{@code config} 与 {@code trigger} 同样不加 校验：{@code DatabaseMappingLoader}
     * 与多个探针都显式传 {@code null}，{@code DataSyncMapping#trigger()} 的契约也写明
     * 「可能为空」。</p>
     *
     * <p>采用 {@link ArrayList} / {@link LinkedHashMap} 快照 + {@link Collections} 包装
     * 而非 {@code List.copyOf} / {@code Map.copyOf}：后两者既拒绝 空 元素，又不保证
     * {@code params} 的迭代顺序，而参数顺序需与配置文件保持一致。</p>
     *
     * @param mappings 字段映射列表
     * @param params 参数映射
     */
    public DefaultDataSyncMapping {
        mappings = mappings == null ? null : Collections.unmodifiableList(new ArrayList<>(mappings));
        params = params == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    /**
     * 从配置定义构造映射。
     * @param mappingId mappingid
     * @param config 配置
     * @return 默认数据同步mapping的结果
     */
    public DefaultDataSyncMapping(String mappingId, DataSyncConfigDefinition config) {
        this(
                mappingId,
                config.inputId(),
                config.sourceId(),
                config.outputId(),
                config.sinkId(),
                config,
                config.mappings(),
                config.batch(),
                config.cronType(),
                config.cron(),
                config.params(),
                null // Config doesn't have trigger yet, so null
        );
    }

    @Override
    /**
     * Trigger
    */
    public com.chua.common.support.task.scheduler.Trigger trigger() {
        return trigger;
    }
}

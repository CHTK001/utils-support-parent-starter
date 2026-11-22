package com.chua.shardingv5.support.conversion;

import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;
import org.apache.shardingsphere.sharding.exception.data.NullShardingValueException;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.TreeMap;

/**
 * 手动 actualDataNodes 模式下的取模标准分片算法。
 *
 * <p>ShardingSphere 5.5.2 的 {@code ShardingRule#checkManualShardingAlgorithm} 禁止
 * 「手动 tables 配置（actualDataNodes）」使用自动分片算法，而内置 MOD / HASH_MOD
 * 实现了 {@code ShardingAutoTableAlgorithm}，在手动配置下初始化即抛
 * {@code AlgorithmInitializationException: tables sharding configuration can not use
 * auto sharding algorithm}。本类提供等价的取模路由，且<b>不</b>实现
 * ShardingAutoTableAlgorithm，因此可同时用于 tableShardingStrategy 与
 * databaseShardingStrategy。</p>
 *
 * <p>目标定位规则（与受限 DDL 的 {@code 前缀_0..N-1} 物理表命名对齐）：</p>
 * <ul>
 *   <li>目标名带数字后缀（如 {@code dshub_order_0}）→ 按 {@code 分片值 % 目标数}
 *       精确匹配同名后缀；</li>
 *   <li>否则（如分库名 {@code ds_main} / {@code ds_demo}）→ 目标名排序后取第
 *       {@code 分片值 % 目标数} 个，与可用目标的遍历顺序无关，结果确定。</li>
 * </ul>
 *
 * <p>通过 ShardingSphere 的 CLASS_BASED 算法包装注册（strategy=STANDARD +
 * algorithmClassName=本类），分片数沿用内置算法的 {@code sharding-count} 属性键；
 * {@code hash-mode=true} 时按分片值字符串哈希取模（对齐 HASH_MOD 语义）。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public final class ManualModShardingAlgorithm implements StandardShardingAlgorithm<Comparable<?>> {

    /** 与内置 MOD/HASH_MOD 一致的分片数属性键 */
    private static final String SHARDING_COUNT_KEY = "sharding-count";

    /** true 时对分片值字符串哈希取模（对齐 HASH_MOD 语义） */
    private static final String HASH_MODE_KEY = "hash-mode";

    /** 分片数；&lt;=0 表示运行期按可用目标数取 */
    private int shardingCount;

    /** 是否按字符串哈希取模 */
    private boolean hashMode;

    @Override
    public void init(final Properties props) {
        shardingCount = Integer.parseInt(props.getProperty(SHARDING_COUNT_KEY, "0"));
        hashMode = Boolean.parseBoolean(props.getProperty(HASH_MODE_KEY, "false"));
    }

    @Override
    public String doSharding(final Collection<String> availableTargetNames,
                             final PreciseShardingValue<Comparable<?>> shardingValue) {
        Comparable<?> value = shardingValue.getValue();
        if (value == null) {
            throw new NullShardingValueException();
        }
        int count = resolveCount(availableTargetNames);
        long idx = modIndex(value, count);
        return pick(availableTargetNames, idx);
    }

    @Override
    public Collection<String> doSharding(final Collection<String> availableTargetNames,
                                         final RangeShardingValue<Comparable<?>> shardingValue) {
        var range = shardingValue.getValueRange();
        int count = resolveCount(availableTargetNames);
        // 范围无界或跨度覆盖全部取模结果时直接全路由
        if (!range.hasLowerBound() || !range.hasUpperBound()
                || toBigInteger(range.upperEndpoint())
                .subtract(toBigInteger(range.lowerEndpoint()))
                .compareTo(BigInteger.valueOf(count - 1L)) >= 0) {
            return availableTargetNames;
        }
        Collection<String> result = new LinkedHashSet<>(availableTargetNames.size(), 1F);
        for (BigInteger i = toBigInteger(range.lowerEndpoint());
             i.compareTo(toBigInteger(range.upperEndpoint())) <= 0;
             i = i.add(BigInteger.ONE)) {
            String target = pick(availableTargetNames, i.mod(BigInteger.valueOf(count)).longValue());
            if (target != null) {
                result.add(target);
            }
        }
        return result;
    }

    @Override
    public String getType() {
        return "MANUAL_MOD";
    }

    /**
     * 分片数：未配置时按可用目标数取。
     */
    private int resolveCount(final Collection<String> availableTargetNames) {
        return shardingCount > 0 ? shardingCount : availableTargetNames.size();
    }

    /**
     * 分片值取模（BigInteger 保证长整型越界时仍与内置 MOD 结果一致）。
     */
    private long modIndex(final Comparable<?> value, final int count) {
        BigInteger big = hashMode
                ? BigInteger.valueOf(Math.abs((long) String.valueOf(value).hashCode()))
                : toBigInteger(value);
        return big.mod(BigInteger.valueOf(count)).longValue();
    }

    private BigInteger toBigInteger(final Comparable<?> value) {
        return value instanceof Number number
                ? BigInteger.valueOf(number.longValue())
                : new BigInteger(value.toString());
    }

    /**
     * 在可用目标中定位第 idx 个：数字后缀精确匹配优先，否则按名称排序取模。
     */
    private String pick(final Collection<String> targets, final long idx) {
        if (targets.size() == 1) {
            return targets.iterator().next();
        }
        // 物理分表名（前缀_0..N-1，或 db.table 形式）按数字后缀精确匹配
        var bySuffix = new TreeMap<Long, String>();
        boolean allSuffixed = true;
        for (String name : targets) {
            Long suffix = numericSuffix(name);
            if (suffix == null) {
                allSuffixed = false;
                break;
            }
            bySuffix.put(suffix, name);
        }
        if (allSuffixed && !bySuffix.isEmpty()) {
            String exact = bySuffix.get(idx);
            if (exact != null) {
                return exact;
            }
            // 分片数与目标数不一致时，按后缀升序兜底取模
            return bySuffix.values().stream()
                    .skip(Math.floorMod(idx, bySuffix.size()))
                    .findFirst()
                    .orElse(null);
        }
        // 分库名等无数字后缀场景：排序后取模，结果与遍历顺序无关
        var ordered = new java.util.ArrayList<>(targets);
        Collections.sort(ordered);
        return ordered.get((int) Math.floorMod(idx, ordered.size()));
    }

    /**
     * 取目标名末段数字后缀（如 {@code dshub_order_0} → 0）；无数字后缀返回 null。
     */
    private Long numericSuffix(final String name) {
        int dot = name.lastIndexOf('.');
        String table = dot >= 0 ? name.substring(dot + 1) : name;
        int underscore = table.lastIndexOf('_');
        if (underscore < 0 || underscore == table.length() - 1) {
            return null;
        }
        String suffix = table.substring(underscore + 1);
        if (suffix.isEmpty() || !suffix.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return Long.parseLong(suffix);
    }
}

package com.chua.skywalking.support.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkywalkingVersion} 的版本能力判定。
 * <p>
 * 这些断言锁的是「字段从哪个版本开始存在」这个事实，不是实现细节：
 * 一旦有人把 {@code AlarmMessage.name} 当成 10.0 就有，或者把
 * {@code queryAlarms} 当成 10.x 就有，这里会立刻红。
 * </p>
 *
 * @author CH
 * @since 4.0.0.51
 */
class SkywalkingVersionTest {

    @Test
    @DisplayName("解析常规版本号")
    void parsesPlainVersion() {
        assertEquals("9.7.0", SkywalkingVersion.parse("9.7.0").toString());
        assertEquals("10.2.0", SkywalkingVersion.parse("10.2.0").toString());
        assertEquals("11.0.0", SkywalkingVersion.parse("11.0.0").toString());
    }

    @Test
    @DisplayName("容忍前缀 v / 空白 / 构建后缀")
    void parsesSuffixedVersion() {
        assertEquals("11.0.0", SkywalkingVersion.parse("v11.0.0").toString());
        assertEquals("11.0.0", SkywalkingVersion.parse(" 11.0.0 ").toString());
        assertEquals("10.2.0", SkywalkingVersion.parse("10.2.0-SNAPSHOT").toString());
    }

    @Test
    @DisplayName("解析不出时按未知处理，能力取最低")
    void treatsGarbageAsUnknown() {
        assertFalse(SkywalkingVersion.parse(null).known());
        assertFalse(SkywalkingVersion.parse("").known());
        assertFalse(SkywalkingVersion.parse("unknown").known());
        SkywalkingVersion unknown = SkywalkingVersion.UNKNOWN;
        assertFalse(unknown.supportsQueryAlarms());
        assertFalse(unknown.supportsTopologyByName());
        assertFalse(unknown.supportsNodeLayers());
        assertFalse(unknown.supportsAlarmName());
    }

    @Test
    @DisplayName("queryAlarms / ByName 拓扑是 11.0.0 起")
    void queryAlarmsAndByNameAreEleven() {
        assertFalse(SkywalkingVersion.parse("9.7.0").supportsQueryAlarms());
        assertFalse(SkywalkingVersion.parse("10.2.0").supportsQueryAlarms());
        assertTrue(SkywalkingVersion.parse("11.0.0").supportsQueryAlarms());

        assertFalse(SkywalkingVersion.parse("10.2.0").supportsTopologyByName());
        assertTrue(SkywalkingVersion.parse("11.0.0").supportsTopologyByName());
    }

    @Test
    @DisplayName("Node.layers 是 10.0.0 起")
    void nodeLayersIsTen() {
        assertFalse(SkywalkingVersion.parse("9.7.0").supportsNodeLayers());
        assertTrue(SkywalkingVersion.parse("10.0.0").supportsNodeLayers());
        assertTrue(SkywalkingVersion.parse("10.2.0").supportsNodeLayers());
    }

    @Test
    @DisplayName("AlarmMessage.name 是 10.2.0 起，10.0 / 10.1 没有")
    void alarmNameIsTenTwo() {
        assertFalse(SkywalkingVersion.parse("10.0.0").supportsAlarmName());
        assertFalse(SkywalkingVersion.parse("10.1.0").supportsAlarmName());
        assertTrue(SkywalkingVersion.parse("10.2.0").supportsAlarmName());
    }

    @Test
    @DisplayName("告警 recoveryTime 单独按 11.0.0 判定")
    void alarmRecoveryTimeIsEleven() {
        assertFalse(SkywalkingVersion.parse("10.2.0").supportsAlarmRecoveryTime());
        assertTrue(SkywalkingVersion.parse("11.0.0").supportsAlarmRecoveryTime());
    }
}

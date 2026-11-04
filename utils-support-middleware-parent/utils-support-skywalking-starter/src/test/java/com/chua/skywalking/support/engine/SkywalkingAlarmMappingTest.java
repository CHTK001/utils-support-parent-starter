package com.chua.skywalking.support.engine;

import com.chua.common.support.apm.model.OtnlAlarm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 告警字段映射：{@code AlarmMessage} → {@link OtnlAlarm}。
 * <p>
 * 锁的是字段语义，不是实现：OAP 的 {@code RunningRule#buildAlarmMessage()}
 * 往 {@code name} 里写的是<b>实体名</b>（{@code AlarmEntity#getName()}），
 * 往 {@code message} 里写的是规则模板渲染后的文案。把这两者弄反，
 * 告警分析页的「实体排行」就会变成一串告警文案。
 * </p>
 * <p>
 * 覆盖三种版本形态：
 * </p>
 * <ul>
 *   <li>11.0+：{@code name} + {@code recoveryTime} 都在；</li>
 *   <li>10.2 ~ 10.x：有 {@code name}，无 {@code recoveryTime}；</li>
 *   <li>9.x ~ 10.1：两者都没有，实体名只能从 {@code Event.source.service} 反推。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.51
 */
class SkywalkingAlarmMappingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 反射调用私有方法 readAlarms(JsonNode)，避免为了测试把方法改成包可见而扩大 API
     * <p>
     * 注意入参是 <b>msgs 数组本身</b>，不是外层 {@code {"msgs": [...]}} 对象：
     * 生产代码传的是 {@code data.path("queryAlarms").path("msgs")}。
     * </p>
     */
    private static List<OtnlAlarm> read(String json) throws Exception {
        Method method = SkywalkingOtnlEngine.class.getDeclaredMethod("readAlarms", JsonNode.class);
        method.setAccessible(true);
        Object result = method.invoke(new SkywalkingOtnlEngine(), MAPPER.readTree(json));
        @SuppressWarnings("unchecked")
        List<OtnlAlarm> alarms = (List<OtnlAlarm>) result;
        return alarms;
    }

    @Test
    @DisplayName("11.0+：name 是实体名，message 是文案，两者不混用")
    void mapsEntityNameAndMessageSeparately() throws Exception {
        List<OtnlAlarm> alarms = read("""
                [{
                  "id":"a-1","name":"serviceA",
                  "message":"Successful rate of service is lower than 75%",
                  "startTime":1700000000000,"recoveryTime":1700000010000,"scope":"SERVICE",
                  "events":[{"name":"Alerting","message":"rule fired","type":"Alarm",
                             "source":{"service":"serviceA"}}],
                  "tags":[{"key":"level","value":"critical"}]
                }]""");

        assertEquals(1, alarms.size());
        OtnlAlarm alarm = alarms.get(0);
        assertEquals("serviceA", alarm.name(), "name 必须是实体名");
        assertEquals("Successful rate of service is lower than 75%", alarm.message());
        assertEquals("serviceA", alarm.serviceName());
        assertEquals("SERVICE", alarm.scope());
        assertEquals("", alarm.ruleName(), "协议不暴露 ruleName，必须是空串而不是实体名");
        assertEquals(1700000000000L, alarm.startTime());
        assertEquals(1, alarm.tags().size());
    }

    @Test
    @DisplayName("9.x ~ 10.1：没有 name，实体名从事件来源反推，实例拼成「实例 of 服务」")
    void fallsBackToEventSourceWhenNameMissing() throws Exception {
        List<OtnlAlarm> alarms = read("""
                [{
                  "id":"a-2","message":"Instance response time is over threshold",
                  "startTime":1700000000000,"scope":"SERVICE_INSTANCE",
                  "events":[{"name":"Alerting",
                             "source":{"service":"svc-b","serviceInstance":"pod-1"}}],
                  "tags":[]
                }]""");

        OtnlAlarm alarm = alarms.get(0);
        assertEquals("pod-1 of svc-b", alarm.name(), "实例作用域按「实例 of 服务」拼，与 OAP 口径一致");
        assertEquals("svc-b", alarm.serviceName(), "serviceName 只放服务名，不重复实体名");
    }

    @Test
    @DisplayName("既无 name 也无事件来源：实体名留空，不拿 message 冒充")
    void leavesEntityEmptyWithoutEvidence() throws Exception {
        List<OtnlAlarm> alarms = read("""
                [{
                  "id":"a-3","message":"something happened",
                  "startTime":1700000000000,"scope":"SERVICE","events":[],"tags":[]
                }]""");

        OtnlAlarm alarm = alarms.get(0);
        assertTrue(alarm.name().isEmpty(), "无证据时实体名应为空串，由前端回落");
        assertNull(alarm.serviceName());
    }

    @Test
    @DisplayName("事件格式化成「名称: 说明」，缺失部分不留下多余分隔符")
    void formatsEvents() throws Exception {
        List<OtnlAlarm> alarms = read("""
                [{
                  "id":"a-4","name":"svc","message":"m","startTime":1,"scope":"SERVICE",
                  "events":[
                    {"name":"Alerting","message":"fired"},
                    {"name":"Recovery"},
                    {"message":"only text"}
                  ],"tags":[]
                }]""");

        List<String> events = alarms.get(0).events();
        assertEquals(List.of("Alerting: fired", "Recovery", "only text"), events);
    }
}

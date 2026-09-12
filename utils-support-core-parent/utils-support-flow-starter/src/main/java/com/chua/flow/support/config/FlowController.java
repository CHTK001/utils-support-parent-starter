package com.chua.flow.support.config;

import com.chua.common.support.task.flow.Flow;
import com.chua.common.support.task.flow.FlowDefinition;
import com.chua.common.support.task.flow.FlowInstance;
import com.chua.common.support.task.flow.FlowNodeMetadata;
import com.chua.flow.support.FlowEngine;
import com.chua.flow.support.store.FlowDefinitionStore;
import com.chua.flow.support.store.FlowInstanceRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程编排 REST 接口。
 *
 * <p>提供流程定义的增删改查、JSON 导入导出、实例运行与恢复、
   * 节点类型清单查询能力，供前端 re流 画布对接。</p>
 *
 * <p>接口约定：</p>
 * <ul>
 *   <li>{@code POST /flow} — 保存流程定义</li>
 *   <li>{@code GET /flow/{id}} — 查询流程定义</li>
 *   <li>{@code DELETE /flow/{id}} — 删除流程定义</li>
 *   <li>{@code POST /flow/{id}/run} — 运行流程（body 传运行参数）</li>
 *   <li>{@code POST /flow/instance/{instanceId}/resume} — 恢复挂起实例</li>
 *   <li>{@code GET /flow/node/types} — 节点类型清单</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@RestController
@RequestMapping("/flow")
public class FlowController {

    /**
     * 流程定义存储
     */
    private final FlowDefinitionStore definitionStore;

    /**
     * 流程实例注册中心
     */
    private final FlowInstanceRegistry instanceRegistry;

    /**
     * 构造流程控制器。
     *
     * @param definitionStore  流程定义存储
     * @param instanceRegistry 流程实例注册中心
     */
    public FlowController(FlowDefinitionStore definitionStore,
                          FlowInstanceRegistry instanceRegistry) {
        this.definitionStore = definitionStore;
        this.instanceRegistry = instanceRegistry;
    }

    /**
     * 保存流程定义。
     *
     * @param definition 流程定义
     * @return 保存后的流程定义
     */
    @PostMapping
    public ResponseEntity<FlowDefinition> save(@RequestBody FlowDefinition definition) {
        definitionStore.save(definition);
        return ResponseEntity.ok(definition);
    }

    /**
     * 查询流程定义。
     *
     * @param flowId 流程 标识
     * @return 流程定义，不存在时返回 404
     */
    @GetMapping("/{flowId}")
    public ResponseEntity<FlowDefinition> get(@PathVariable String flowId) {
        FlowDefinition definition = definitionStore.get(flowId);
        if (definition == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(definition);
    }

    /**
     * 查询全部流程定义。
     *
     * @return 流程定义列表
     */
    @GetMapping("/list")
    public ResponseEntity<List<FlowDefinition>> list() {
        return ResponseEntity.ok(definitionStore.list());
    }

    /**
     * 删除流程定义。
     *
     * @param flowId 流程 标识
     * @return 操作结果
     */
    @DeleteMapping("/{flowId}")
    public ResponseEntity<Map<String, Object>> remove(@PathVariable String flowId) {
        boolean removed = definitionStore.remove(flowId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", removed);
        return ResponseEntity.ok(result);
    }

    /**
     * 导入流程定义 JSON。
     *
     * @param json 流程定义 JSON 字符串
     * @return 解析后的流程定义
     */
    @PostMapping("/import")
    public ResponseEntity<FlowDefinition> importJson(@RequestBody String json) {
        FlowDefinition definition = com.chua.common.support.task.flow.FlowJson.fromJson(json);
        definitionStore.save(definition);
        return ResponseEntity.ok(definition);
    }

    /**
     * 导出流程定义 JSON。
     *
     * @param flowId 流程 标识
     * @return 流程定义 JSON 字符串
     */
    @GetMapping("/{flowId}/export")
    public ResponseEntity<String> exportJson(@PathVariable String flowId) {
        FlowDefinition definition = definitionStore.get(flowId);
        if (definition == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(com.chua.common.support.task.flow.FlowJson.toJson(definition));
    }

    /**
     * 运行流程。
     *
     * <p>按流程 ID 加载定义，创建实例并执行，携带 body 中的运行参数。</p>
     *
     * @param flowId 流程 标识
     * @param params 运行参数
     * @return 运行结果摘要（实例 标识、状态、流程 标识）
     */
    @PostMapping("/{flowId}/run")
    public ResponseEntity<Map<String, Object>> run(@PathVariable String flowId,
                                                   @RequestBody(required = false)
                                                   Map<String, Object> params) {
        FlowDefinition definition = definitionStore.get(flowId);
        if (definition == null) {
            return ResponseEntity.notFound().build();
        }
        Flow flow = FlowEngine.createFlow(definition);
        FlowInstance instance = flow.createGraph().createInstance(params);
        instanceRegistry.register(instance);
        instance.run();
        return ResponseEntity.ok(buildResult(instance));
    }

    /**
     * 恢复挂起实例。
     *
     * @param instanceId 实例 标识
     * @return 恢复结果摘要
     */
    @PostMapping("/instance/{instanceId}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable String instanceId) {
        FlowInstance instance = instanceRegistry.get(instanceId);
        if (instance == null) {
            return ResponseEntity.notFound().build();
        }
        instance.resume();
        return ResponseEntity.ok(buildResult(instance));
    }

    /**
     * 查询全部节点类型。
     *
     * @return 节点类型元信息列表
     */
    @GetMapping("/node/types")
    public ResponseEntity<List<FlowNodeMetadata>> nodeTypes() {
        return ResponseEntity.ok(FlowEngine.listNodeTypes());
    }

    /**
     * 构建实例运行结果摘要。
     *
     * @param instance 流程实例
     * @return 结果摘要映射
     */
    private Map<String, Object> buildResult(FlowInstance instance) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instanceId", instance.getInstanceId());
        result.put("flowId", instance.getFlowId());
        result.put("status", instance.getStatus());
        return result;
    }
}

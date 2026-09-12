package com.chua.flow.support.config;

import com.chua.flow.support.store.FlowDefinitionStore;
import com.chua.flow.support.store.FlowInstanceRegistry;
import com.chua.flow.support.store.MemoryFlowDefinitionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;

/**
* 流程编排自动配置。
*
* <p>当类路径存在 Spring Web 时自动注册流程编排组件：
* 流程定义存储、实例注册中心与 REST 控制器。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Configuration
@ConditionalOnClass(RestController.class)
public class FlowAutoConfiguration {

    /**
    * 注册流程定义存储。
    *
    * <p>默认使用内存实现，可通过自定义 {@link FlowDefinitionStore} Bean 覆盖。</p>
    *
    * @return 流程定义存储
     */
    @Bean
    @ConditionalOnMissingBean
    public FlowDefinitionStore flowDefinitionStore() {
        return new MemoryFlowDefinitionStore();
    }

    /**
    * 注册流程实例注册中心。
    *
    * @return 流程实例注册中心
     */
    @Bean
    @ConditionalOnMissingBean
    public FlowInstanceRegistry flowInstanceRegistry() {
        return new FlowInstanceRegistry();
    }

    /**
    * 注册流程 REST 控制器。
    *
    * @param definitionStore  流程定义存储
    * @param instanceRegistry 流程实例注册中心
    * @return 流程控制器
     */
    @Bean
    @ConditionalOnMissingBean
    public FlowController flowController(FlowDefinitionStore definitionStore,
                                         FlowInstanceRegistry instanceRegistry) {
        return new FlowController(definitionStore, instanceRegistry);
    }
}

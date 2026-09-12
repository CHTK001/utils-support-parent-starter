package com.chua.redis.support.dispatcher;

import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.DispatcherDefinition;
import com.chua.common.support.concurrent.dispatcher.provider.AbstractDispatcherProvider;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.redisson.config.Config;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;


/**
* 基于 Redisson rtopic 的 Redis 发布订阅分发器提供者
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi("redis")
public class RedissonDispatcherProvider extends AbstractDispatcherProvider {

    /** Redisson客户端 */
    private final RedissonClient redissonClient;
    /** topic映射 */
    private final Map<String, RTopic> topicMap = new ConcurrentHashMap<>();
    /** definition映射 */
    private final Map<String, List<DispatcherDefinition>> definitionMap = new ConcurrentHashMap<>();
    /** 监听器id映射 */
    private final Map<String, Integer> listenerIdMap = new ConcurrentHashMap<>();
    /** closed */
    private volatile boolean closed = false;

    /**
    * 创建 redissondispatcher提供者 实例
    * @param config 配置
     */
    public RedissonDispatcherProvider(DispatcherConfig config) {
        super(config);
        var redisUri = config.getUrl() != null ? config.getUrl() : "redis://127.0.0.1:6379";
        var c = new Config();
        c.useSingleServer().setAddress(redisUri);
        this.redissonClient = Redisson.create(c);
        log.info("Redisson 客户端已创建，连接：{}", redisUri);
    }

    /**
    * 创建 redissondispatcher提供者 实例
    * @param config 配置
    * @param redissonClient redisson客户端
    * @param redissonClient redisson客户端
     */
    public RedissonDispatcherProvider(DispatcherConfig config, RedissonClient redissonClient) {
        super(config);
        this.redissonClient = redissonClient;
    }

    @Override
    /** 发布 */
    public void publish(String topic, Object body) {
        var value = body == null ? "" : body.toString();
        var rt = getOrCreateTopic(topic);
        rt.publish(value);
        log.debug("Redis 已发布消息到频道：{}", topic);
    }

    @Override
    /** 订阅 */
    public void subscribe(DispatcherDefinition definition) {
        for (var topic : definition.getTopics()) {
            definitionMap.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(definition);
            if (!listenerIdMap.containsKey(topic) && !closed) {
                var rt = getOrCreateTopic(topic);
                var listenerId = rt.addListener(String.class, new MessageListener<String>() {
                    @Override
                    /** on消息 */
                    public void onMessage(CharSequence channel, String msg) {
                        var definitions = definitionMap.get(topic);
                        if (definitions != null) {
                            for (var def : definitions) {
                                try {
                                    def.dispatch(msg);
                                } catch (Exception e) {
                                    log.warn("订阅方法执行异常，主题：{}", topic, e);
                                }
                            }
                        }
                    }
                });
                listenerIdMap.put(topic, listenerId);
                log.debug("Redis 已订阅频道：{}", topic);
            }
        }
    }

    @Override
    /** 取消订阅 */
    public void unsubscribe(DispatcherDefinition definition) {
        for (var topic : definition.getTopics()) {
            var definitions = definitionMap.get(topic);
            if (definitions != null) {
                definitions.remove(definition);
                if (definitions.isEmpty()) {
                    var listenerId = listenerIdMap.remove(topic);
                    if (listenerId != null) {
                        var rt = topicMap.get(topic);
                        if (rt != null) {
                            rt.removeListener(listenerId);
                        }
                    }
                    definitionMap.remove(topic);
                    topicMap.remove(topic);
                }
            }
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        closed = true;
        listenerIdMap.clear();
        definitionMap.clear();
        topicMap.clear();
        redissonClient.shutdown();
    }

    /**
    * 获取或创建Topic
    *
    * @param topic topic
    * @return 获取或创建topic的结果
     */
    private RTopic getOrCreateTopic(String topic) {
        return topicMap.computeIfAbsent(topic, redissonClient::getTopic);
    }
}

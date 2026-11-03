// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.ai.chat.route;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;

import java.util.List;

/**
 * 路由型 {@link ChatClient} 二级接口。
 *
 * <p>在 {@link ChatClient}「一次对话」的抽象之上，补充「按模型选择上游」的路由能力：
 * 同一个路由客户端可以承载多个模型，每个模型对应不同的 {@code baseUrl}、{@code apiKey}
 * 与认证方式，由 {@link #route(String)} 在发起请求前解析。典型实现是免费渠道聚合客户端：
 * 渠道只配置一个全局 key，模型到上游的映射交给路由表维护，后续新增免费模型只需扩展路由表，
 * 不必新增 {@link ChatClient} 实现，也不用改动调用方。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>{@link #route(String)} 命中时返回可用的 {@link RouteTarget}，未命中返回 {@code null}；</li>
 *   <li>{@link #routeModels()} 返回当前可路由的模型清单，顺序稳定，供列表与前端展示；</li>
 *   <li>路由只负责「选目标」，请求的拼装与发送仍由实现类完成；</li>
 *   <li>模型为空或未命中时，实现类可回退到 {@link #DEFAULT_ROUTE} 对应的目标。</li>
 * </ul>
 *
 * <p>后续按区域、租户、权重等其它维度路由的实现，只需实现本接口并登记到
 * {@link ChatClient} 的 SPI 清单，即可被
 * {@link ChatClient#create(ChatClientSetting)} 正常创建，无需改动既有调用方。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface RouterChatClient extends ChatClient {

    /**
     * 默认路由标识：模型为空或未命中已登记路由时使用
     */
    String DEFAULT_ROUTE = "default";

    /**
     * 按模型解析路由目标
     *
     * @param model 模型标识，可为空
     * @return 路由目标；无法路由时返回 {@code null}
     */
    RouteTarget route(String model);

    /**
     * 当前可路由的模型清单
     *
     * <p>顺序应保持稳定（如按登记顺序），供列表展示与前端渲染；路由表在构造时固定下来的
     * 实现可直接返回静态清单。默认返回空列表，表示不对外暴露模型清单。</p>
     *
     * @return 模型标识列表
     */
    default List<String> routeModels() {
        return List.of();
    }

    /**
     * 当前已选模型的路由目标
     *
     * <p>等价于 {@code route(getModel())}，便于调用方直接取本次请求的上游配置。</p>
     *
     * @return 路由目标；无法路由时返回 {@code null}
     */
    default RouteTarget currentRoute() {
        return route(getModel());
    }

    /**
     * 指定模型是否可路由
     *
     * @param model 模型标识
     * @return 命中已登记路由时返回 {@code true}
     */
    default boolean supportsRoute(String model) {
        return route(model) != null;
    }

    /**
     * 按模型解析出可直接创建上游客户端的配置
     *
     * <p>以当前客户端设置为底（沿用温度、工具、超时等公共参数），用命中的
     * {@link RouteTarget} 覆盖 provider、baseUrl、appKey、model 等路由字段；模型未命中时
     * 原样返回当前设置。实现类通常只需要：</p>
     * <pre>{@code
     * ChatClient upstream = ChatClient.create(routeSetting(getModel()));
     * }</pre>
     *
     * @param model 模型标识
     * @return 上游客户端配置
     */
    default ChatClientSetting routeSetting(String model) {
        RouteTarget target = route(model);
        return target == null ? getSetting() : target.toSetting(getSetting());
    }
}
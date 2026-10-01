package com.chua.common.support.network.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.filter.ReactiveServerFilter;
import com.chua.common.support.network.server.filter.ReactiveFilterChain;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterManager;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务器过滤器链 —— {@code addFilter} / {@code removeFilter} 必须对称。
 *
 * <p>{@code addFilter} 对同时实现 {@link ReactiveServerFilter} 的插件会同时登记到
 * 「静态阻塞列表」与「响应式列表」两处，而 JDK / 响应式服务器构建链路走的是
 * {@code getMergedReactiveFilters()}。{@code removeFilter} 原先只摘静态列表，
 * 于是热更新重建过滤器链后，旧实例仍留在响应式链上并先终结响应，新实例永不可达，
 * 外部表现为「参数已落库、实例也重建了，运行中行为却不变，只有停→起才生效」——
 * 这类插件的典型代表是 {@code FileStorageServerFilter}（逻辑全在响应式 doFilter 里）。</p>
 *
 * <p>本测试钉住「移除后响应式链里不再有该实例」，防止该不对称再次回归。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class AbstractServerFilterSymmetryTest {

    /**
     * 最小可用服务器：仅实现两个抽象方法，不做任何真实监听。
     */
    private static final class ProbeServer extends AbstractServer {

        ProbeServer(ServerSetting setting) {
            super(setting);
        }

        @Override
        protected void doStart() {
            // 测试不需要真实监听
        }

        @Override
        protected void doStop() {
            // 测试不需要真实关闭
        }

        @Override
        public ProtocolType getProtocolType() {
            return ProtocolType.HTTP;
        }
    }

    /**
     * 同时实现阻塞与响应式两个接口的探针过滤器（对齐 {@code FileStorageServerFilter} 的形态）。
     */
    private static final class DualFilter implements ServerFilter, ReactiveServerFilter {

        @Override
        public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
            chain.doFilter(request, response);
        }

        @Override
        public CompletionStage<Void> doFilter(ServerRequest request, ServerResponse response,
                                              ReactiveFilterChain chain) {
            return chain.doFilter(request, response);
        }

        @Override
        public boolean supportProtocol(ProtocolType protocol) {
            return true;
        }

        /**
         * 两个接口都提供 getOrder() 默认实现，同时继承会产生歧义，必须显式覆写。
         *
         * @return 执行顺序
         */
        @Override
        public int getOrder() {
            return 100;
        }

        /**
         * 两个接口都提供 supportPath() 默认实现，同时继承会产生歧义，必须显式覆写。
         *
         * @return 绑定路径，null 表示每次请求都触发的 Access 过滤器
         */
        @Override
        public String supportPath() {
            return null;
        }
    }

    /**
     * 反射取出 filterManager（私有字段，无公开的响应式过滤器访问器）。
     *
     * @param server 服务器
     * @return 过滤器管理器
     * @throws Exception 反射失败
     */
    private static ServerFilterManager filterManagerOf(AbstractServer server) throws Exception {
        Field field = AbstractServer.class.getDeclaredField("filterManager");
        field.setAccessible(true);
        return (ServerFilterManager) field.get(server);
    }

    /**
     * 当前响应式链中的过滤器数量。
     *
     * @param server 服务器
     * @return 数量
     * @throws Exception 反射失败
     */
    private static int reactiveFilterCount(AbstractServer server) throws Exception {
        return filterManagerOf(server).getMergedReactiveFilters().size();
    }

    /**
     * 指定实例在合并后的响应式链中出现的次数。
     *
     * <p>合并链 = 静态列表（包装为响应式）+ 响应式列表，且<b>不去重</b>：同时实现两个接口的
     * 插件会出现两份（静态包装版跑阻塞 doFilter、原生版跑响应式 doFilter）。因此判据用
     * 「出现次数」而非「是否包含」，也不依赖随 classpath 变动的绝对数量。</p>
     *
     * @param server 服务器
     * @param filter 目标实例
     * @return 出现次数
     * @throws Exception 反射失败
     */
    private static int occurrencesInMergedReactive(AbstractServer server, ServerFilter filter) throws Exception {
        int occurrences = 0;
        for (ReactiveServerFilter candidate : filterManagerOf(server).getMergedReactiveFilters()) {
            if (candidate == filter) {
                occurrences++;
            }
        }
        return occurrences;
    }

    private static ProbeServer newServer() {
        ServerSetting setting = ServerSetting.defaults();
        setting.setProtocol("http");
        setting.setHost("0.0.0.0");
        setting.setPort(0);
        return new ProbeServer(setting);
    }

    @Test
    @DisplayName("addFilter 会把双接口插件同时登记到阻塞与响应式两侧")
    void addFilterRegistersBothSides() throws Exception {
        ProbeServer server = newServer();
        ServerFilter filter = new DualFilter();

        server.addFilter(filter);

        assertTrue(server.getFilters().contains(filter), "应出现在静态阻塞列表");
        assertTrue(filterManagerOf(server).getMergedReactiveFilters().contains(filter),
                "应出现在响应式列表——JDK/响应式服务器走的就是这条链");
    }

    @Test
    @DisplayName("removeFilter 必须同时从两侧摘除（本次修复点）")
    void removeFilterRemovesFromBothSides() throws Exception {
        ProbeServer server = newServer();
        ServerFilter filter = new DualFilter();
        server.addFilter(filter);
        assertEquals(1, occurrencesInMergedReactive(server, filter),
                "前置：刚加入的实例应出现在合并后的响应式链里");

        server.removeFilter(filter);

        assertFalse(server.getFilters().contains(filter), "应从静态阻塞列表摘除");
        assertEquals(0, occurrencesInMergedReactive(server, filter),
                "必须也从响应式列表摘除，否则旧实例会继续终结请求、热更新表现为无效");
    }

    @Test
    @DisplayName("「添加→移除→再添加」不应让链上累积实例")
    void removeThenReAddDoesNotAccumulate() throws Exception {
        ProbeServer server = newServer();
        ServerFilter filter = new DualFilter();

        server.addFilter(filter);
        int afterFirstAdd = occurrencesInMergedReactive(server, filter);
        int mergedAfterFirstAdd = reactiveFilterCount(server);

        server.removeFilter(filter);
        server.addFilter(filter);

        assertEquals(afterFirstAdd, occurrencesInMergedReactive(server, filter),
                "重新加入后该实例的出现次数应与首次加入一致");
        assertEquals(mergedAfterFirstAdd, reactiveFilterCount(server),
                "重新加入后合并链长度应与首次加入一致，说明上一轮的实例确实被摘干净了");
    }

    @Test
    @DisplayName("双接口插件在合并链中出现两次是既有设计（静态包装版 + 原生响应式版）")
    void dualInterfaceFilterAppearsTwiceByDesign() throws Exception {
        ProbeServer server = newServer();
        ServerFilter filter = new DualFilter();
        int base = reactiveFilterCount(server);

        server.addFilter(filter);

        // getMergedReactiveFilters 不去重：addFilter 把插件同时登记到静态与响应式两侧，
        // 于是合并链里静态包装版（跑阻塞 doFilter）与原生版（跑响应式 doFilter）各一份。
        // 对 FileStorageServerFilter 而言阻塞版是纯透传，故功能无碍，但每请求会跑两次插件。
        // 本测试把这个既有行为钉住，避免有人误以为只有一份而写出错误的数量断言。
        assertEquals(base + 2, reactiveFilterCount(server),
                "双接口插件按设计应在合并链中占两份");
    }

    @Test
    @DisplayName("移除一个未注册的过滤器不应影响其它过滤器")
    void removingUnknownFilterKeepsOthers() throws Exception {
        ProbeServer server = newServer();
        ServerFilter kept = new DualFilter();
        server.addFilter(kept);
        int before = reactiveFilterCount(server);

        server.removeFilter(new DualFilter());

        assertTrue(occurrencesInMergedReactive(server, kept) > 0,
                "移除未注册实例不应误伤已注册实例");
        assertEquals(before, reactiveFilterCount(server), "移除未注册实例不应改变链长度");
    }
}

package com.chua.remote.support.agent.launch;

import com.chua.remote.support.agent.BaseRemoteAgent;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Constructor;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent launcher using reflection to avoid circular dependencies.

 * @author CH
 */@Slf4j
public class AgentLauncher {

    public static void main(String[] args) {
        AgentProperties props = AgentProperties.fromCommandLine(args);

        List<String> availableProtocols = filterAvailableProtocols(props.getProtocols());
        if (availableProtocols.size() < props.getProtocols().size()) {
            log.info("协议过滤: {} -> {}", props.getProtocols(), availableProtocols);
            props.setProtocols(availableProtocols);
        }

        log.info("Agent 启动配置: gateway={}:{} agentId={} protocols={}",
                props.getGatewayHost(), props.getGatewayPort(),
                props.getAgentId(), props.getProtocols());

        BaseRemoteAgent agent = new CompositeAgent(props);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("正在关闭 Agent...");
            agent.stop();
        }));

        try {
            agent.start();
            log.info("Agent 已启动，等待注册或重连");
            while (agent.isRunning()) {
                Thread.sleep(1000);
            }
            log.info("Agent 进程退出");
        } catch (Exception e) {
            log.error("Agent 启动失败", e);
            System.exit(1);
        }
    }

    private static List<String> filterAvailableProtocols(List<String> protocols) {
        List<String> result = new ArrayList<>();
        for (String proto : protocols) {
            if ("SSH".equalsIgnoreCase(proto)) {
                if (isSshAvailable()) {
                    result.add(proto);
                } else {
                    log.info("SSH 服务未检测到（端口 22 未监听），跳过 SSH 协议注册");
                }
            } else {
                result.add(proto);
            }
        }
        if (result.isEmpty()) {
            log.warn("所有协议均不可用，至少保留 DESKTOP");
            result.add("DESKTOP");
        }
        return result;
    }

    private static boolean isSshAvailable() {
        try (Socket socket = new Socket("127.0.0.1", 22)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static class CompositeAgent extends BaseRemoteAgent {
        private final Object ssh;
        private final Object desktop;
        private final Object socks5;
        private final Object rustdesk;

        CompositeAgent(AgentProperties props) {
            super(props);
            ssh = createService("com.chua.remote.support.agent.ssh.SshAgentService", this);
            desktop = createService("com.chua.remote.support.agent.desktop.DesktopAgentServiceImpl", this);
            socks5 = createService("com.chua.remote.support.agent.socks5.NettySocks5AgentService", this);
            Object rustdeskCtx = createRustDeskContext(this);
            rustdesk = createService("com.chua.remote.support.agent.rustdesk.RustDeskAgentService", rustdeskCtx != null ? rustdeskCtx : this);
        }

        private static Object createRustDeskContext(BaseRemoteAgent agent) {
            try {
                Class<?> iface = Class.forName("com.chua.remote.support.agent.rustdesk.RustDeskAgentContext");
                return java.lang.reflect.Proxy.newProxyInstance(
                        iface.getClassLoader(),
                        new Class<?>[]{iface},
                        (proxy, method, args) -> {
                            String name = method.getName();
                            if ("getAgentId".equals(name)) return agent.properties.getAgentId();
                            if ("getCapabilities".equals(name)) return agent.properties.getCapabilities();
                            if ("setCapabilities".equals(name)) { agent.properties.setCapabilities((java.util.Map) args[0]); return null; }
                            if ("sendToGateway".equals(name)) { agent.sendToGateway((String) args[0]); return null; }
                            if ("toString".equals(name)) return "RustDeskContextAdapter";
                            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                            if ("equals".equals(name)) return proxy == args[0];
                            return null;
                        });
            } catch (Exception e) {
                log.warn("无法创建 RustDeskAgentContext 代理: {}", e.getMessage());
                return null;
            }
        }

        private static Object createService(String className, Object agent) {
            try {
                Class<?> clazz = Class.forName(className);
                Class<?>[] paramTypes = candidateParamTypes(agent);
                Constructor<?> ctor = null;
                for (Class<?> pt : paramTypes) {
                    try { ctor = clazz.getConstructor(pt); break; } catch (NoSuchMethodException ignore) {}
                }
                if (ctor == null) { throw new NoSuchMethodException("no matching constructor for " + clazz.getName()); }
                return ctor.newInstance(agent);
            } catch (Exception e) {
                log.warn("无法加载 Agent 服务: {} ({})", className, e.getClass().getSimpleName() + ": " + e.getMessage());
                return null;
            }
        }

        private static Class<?>[] candidateParamTypes(Object obj) {
            java.util.LinkedHashSet<Class<?>> set = new java.util.LinkedHashSet<>();
            Class<?> concrete = obj.getClass();
            Class<?> c = concrete;
            while (c != null && c != Object.class) { set.add(c); c = c.getSuperclass(); }
            for (Class<?> iface : concrete.getInterfaces()) { set.add(iface); }
            if (java.lang.reflect.Proxy.isProxyClass(concrete)) {
                for (Class<?> iface : concrete.getInterfaces()) { set.add(iface); }
            }
            return set.toArray(new Class<?>[0]);
        }

        @Override
        protected void handleConnect(String sessionId, String protocol,
                                      Map<String, Object> target,
                                      Map<String, Object> auth) {
            if ("SSH".equalsIgnoreCase(protocol)) {
                invoke(ssh, "handleConnect", sessionId, target, auth);
            } else if ("DESKTOP".equalsIgnoreCase(protocol)) {
                sessions.put(sessionId, new AgentSessionContext(sessionId, protocol, auth));
                invoke(desktop, "handleConnect", sessionId, target, auth);
            } else if ("RUSTDESK".equalsIgnoreCase(protocol)) {
                sessions.put(sessionId, new AgentSessionContext(sessionId, protocol, auth));
                invoke(desktop, "handleConnect", sessionId, target, auth);
                Object response = invoke(rustdesk, "handleConnect", sessionId, target, auth);
                if (response != null) {
                    try {
                        sendToGateway(mapper.writeValueAsString(response));
                    } catch (Exception e) {
                        log.error("RustDesk handleConnect 序列化失败", e);
                    }
                }
            } else {
                sendToGateway("{\"type\":\"error\",\"sessionId\":\"" + sessionId + "\",\"msg\":\"unsupported protocol\"}");
            }
        }

        @Override
        protected void handleDisconnect(String sessionId) {
            invoke(ssh, "handleDisconnect", sessionId);
            invoke(desktop, "handleDisconnect", sessionId);
            sessions.remove(sessionId);
        }

        @Override
        protected void handleInput(String sessionId, String type, Map<String, Object> payload) {
            AgentSessionContext ctx = sessions.get(sessionId);
            if (ctx == null) { return; }
            String proto = ctx.getProtocol();
            if ("SSH".equalsIgnoreCase(proto)) {
                invoke(ssh, "handleInput", sessionId, type, payload);
            } else if ("DESKTOP".equalsIgnoreCase(proto) || "RUSTDESK".equalsIgnoreCase(proto)) {
                invoke(desktop, "handleInput", sessionId, type, payload);
            }
        }

        @Override
        protected boolean handleGatewayMessage(String type, Map<String, Object> payload) {
            if (socks5 == null) { return false; }
            try {
                return (Boolean) socks5.getClass().getMethod("handleGatewayMessage", String.class, Map.class)
                        .invoke(socks5, type, payload);
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        protected void onRegistered() {
            log.info("Agent 已注册到 Gateway");
            if (rustdesk != null) {
                try {
                    rustdesk.getClass().getMethod("startProcess").invoke(rustdesk);
                } catch (Exception e) {
                    log.error("RustDesk 启动失败", e);
                }
            }
        }

        @Override
        protected void onChannelInactive() {
            invoke(socks5, "closeAll");
            invoke(desktop, "handleDisconnectAll");
            invoke(ssh, "handleDisconnectAll");
            invoke(rustdesk, "close");
            sessions.clear();
        }

        private static Object invoke(Object target, String methodName, Object... args) {
            if (target == null) { return null; }
            try {
                Class<?>[] types = declaredTypes(args);
                return target.getClass().getMethod(methodName, types).invoke(target, args);
            } catch (NoSuchMethodException nsme) {
                try {
                    Class<?>[] concrete = new Class<?>[args.length];
                    for (int i = 0; i < args.length; i++) {
                        concrete[i] = args[i] != null ? args[i].getClass() : Object.class;
                    }
                    return target.getClass().getMethod(methodName, concrete).invoke(target, args);
                } catch (Exception e) {
                    log.warn("调用 {}#{} 失败 (fallback): {}", target.getClass().getName(), methodName, e.getMessage());
                    return null;
                }
            } catch (Exception e) {
                log.warn("调用 {}#{} 失败: {} -> {}", target.getClass().getName(), methodName, e.getClass().getSimpleName(), e.getMessage());
                return null;
            }
        }

        private static Class<?>[] declaredTypes(Object[] args) {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null) {
                    types[i] = commonDeclaredType(i);
                } else {
                    String actual = args[i].getClass().getName();
                    boolean resolved = false;
                    for (String opt : commonDeclaredOptions(i)) {
                        if (opt.equals(actual) || isAssignable(opt, args[i].getClass())) {
                            try { types[i] = Class.forName(opt); resolved = true; break; } catch (ClassNotFoundException ignore) {}
                        }
                    }
                    if (!resolved) { types[i] = args[i].getClass(); }
                }
            }
            return types;
        }

        private static String[] commonDeclaredOptions(int argIndex) {
            switch (argIndex) {
                case 0: return new String[]{"java.lang.String"};
                case 1: return new String[]{"java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap"};
                case 2: return new String[]{"java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap"};
                default: return new String[0];
            }
        }

        private static Class<?> commonDeclaredType(int argIndex) {
            for (String opt : commonDeclaredOptions(argIndex)) {
                try { return Class.forName(opt); } catch (ClassNotFoundException ignore) {}
            }
            return Object.class;
        }

        private static boolean isAssignable(String typeName, Class<?> from) {
            try {
                Class<?> target = Class.forName(typeName);
                return target.isAssignableFrom(from);
            } catch (ClassNotFoundException e) { return false; }
        }
    }
}

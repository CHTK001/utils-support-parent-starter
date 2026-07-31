package com.chua.remote.support.gateway.core.codec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * 编解码引擎 — 管理多种 Codec 插件的注册与调用
 * <p>通过插件机制支持 H.264、H.265、JPEG 等多种编码格式的编解码。
 * 使用 {@link ConcurrentHashMap} 保证多线程安全。
 *
 * @author CH
 */
@Slf4j
public class CodecEngine {
    /** Codec 插件映射：名称 → 插件实例（名称统一转为大写） */
    private final Map<String, CodecPlugin> plugins = new ConcurrentHashMap<>();

    /**
     * 注册编解码插件
     * @param plugin 插件实例，通过 {@link CodecPlugin#codecName()} 获取注册键
     */
    public void register(CodecPlugin plugin) {
        plugins.put(plugin.codecName().toUpperCase(), plugin);
        log.info("Codec 插件注册: {}", plugin.codecName());
    }

    /** 获取已注册的插件 */
    public CodecPlugin getPlugin(String codecName) { return plugins.get(codecName.toUpperCase()); }

    /**
     * 解码编码帧
     * @param encodedFrame 待解码的编码帧
     * @return 解码后的原始帧，若无匹配插件返回 null
     */
    public Frame decode(EncodedFrame encodedFrame) {
        if (encodedFrame == null || encodedFrame.getCodecName() == null) { return null; }
        CodecPlugin p = plugins.get(encodedFrame.getCodecName().toUpperCase());
        return p != null ? p.decode(encodedFrame) : null;
    }

    /** 获取所有已注册的编解码器名称 */
    public String[] supportedCodecs() { return plugins.keySet().toArray(new String[0]); }

    /** 已注册的插件数量 */
    public int pluginCount() { return plugins.size(); }
}

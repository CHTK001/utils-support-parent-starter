package com.chua.remote.agent.vnc;

import com.chua.remote.agent.DesktopInputInjector;
import lombok.extern.slf4j.Slf4j;

/**
 * VNC 键鼠事件注入器。
 *
 * <p>委托共享桌面注入器 {@link DesktopInputInjector}（java.awt.Robot 真实注入）——
 * RDP/VNC 桌面通道共用同一注入实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class VncInputInjector extends DesktopInputInjector {

    /**
     * 创建注入器。
     *
     * @throws RuntimeException 当环境不支持 Robot 注入时（如 headless）
     */
    public VncInputInjector() {
        super();
    }
}

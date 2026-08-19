package com.chua.desktop.support;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.spi.annotations.SpiParam;
import com.chua.common.support.task.message.MessageEnvironment;
import com.chua.common.support.task.message.MessagePush;
import com.chua.common.support.task.message.MessageRequest;
import com.chua.common.support.task.message.MessageResponse;
import com.chua.common.support.task.message.TemplateInfo;
import com.chua.desktop.support.NativeDesktopNotifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 桌面通知推送实现
 *
 * <p>通过 {@link NativeDesktopNotifier} 调用操作系统原生桌面通知能力。
 * 自动检测平台并选择对应的原生实现（Windows/macOS/Linux）。
 *
 * <h3>环境配置</h3>
 * <pre>
 *   desktop.title    通知标题（可选，默认 "通知"）
 *   desktop.icon     通知图标路径（可选）
 * </pre>
 *
 * <h3>架构</h3>
 * <pre>
 *   DesktopPush (MessagePush)
 *       → NativeDesktopNotifier (SPI 自动发现)
 *           ├── WindowsDesktopNotifier (PowerShell)
 *           ├── MacOsDesktopNotifier (osascript)
 *           └── LinuxDesktopNotifier (notify-send)
 * </pre>
 *
 * @since 2026/07/17
 */
@Spi("desktop")
@SpiDescribe(
        value = "桌面通知",
        type = "DESKTOP",
        desc = "调用操作系统原生能力发送桌面通知（Windows/macOS/Linux）",
        optional = {
                @SpiParam(value = "desktop.title", defaultValue = "通知", desc = "通知标题", type = "String"),
                @SpiParam(value = "desktop.icon", desc = "通知图标路径", type = "String")
        }
)
public class DesktopPush implements MessagePush {

    /** 环境 */
    private final MessageEnvironment environment;
    private final Map<String, TemplateInfo> templates = new ConcurrentHashMap<>();

    public DesktopPush() {
        this(new MessageEnvironment());
    }

    public DesktopPush(MessageEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public String getProvider() {
        return "desktop";
    }

    @Override
    public MessageResponse send(MessageRequest request) throws Exception {
        long start = System.currentTimeMillis();

        String title = request.getSubject() != null ? request.getSubject()
                : environment.get("desktop.title", "通知");
        String content = request.getContent();
        String icon = environment.get("desktop.icon");

        NativeDesktopNotifier notifier = NativeDesktopNotifier.create();
        if (!notifier.isSupported()) {
            return MessageResponse.builder()
                    .success(false)
                    .errorMessage("当前平台不支持桌面通知: " + notifier.getPlatform())
                    .build();
        }

        notifier.notify(title, content, icon);

        long duration = System.currentTimeMillis() - start;
        return MessageResponse.builder()
                .success(true)
                .messageId(java.util.UUID.randomUUID().toString())
                .durationMillis(duration)
                .build();
    }

    @Override
    public List<TemplateInfo> listTemplates() {
        return new ArrayList<>(templates.values());
    }

    @Override
    public TemplateInfo getTemplate(String templateId) {
        return templates.get(templateId);
    }

    public void registerTemplate(TemplateInfo template) {
        templates.put(template.id(), template);
    }
}

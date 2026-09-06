package com.chua.remote.agent;

import com.chua.remote.protocol.capability.CodecProfile;
import com.chua.remote.protocol.model.AgentInfo;
import com.chua.remote.protocol.spi.RemoteAgentSPI;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 服务模式被控端实现。
 *
 * <p>纯自研的完整系统：Native 截图 + 自研编码 + 实时传输。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class AgentService implements RemoteAgentSPI {

    /** 被控端信息 */
    private final AgentInfo agentInfo;

    /** 截图采集器 */
    private final ScreenCapture screenCapture;

    /** 编码器 */
    private final NativeEncoder nativeEncoder;

    /** 是否运行中 */
    private volatile boolean running;

    public AgentService(AgentInfo agentInfo) {
        this.agentInfo = agentInfo;
        this.screenCapture = new ScreenCapture(agentInfo);
        this.nativeEncoder = new NativeEncoder(agentInfo.getEncodingCapability());
    }

    @Override
    public void reportInfo(AgentInfo info) {
        log.info("上报被控端信息: id={}", info.getId());
    }

    @Override
    public void reportCapability(CodecProfile capability) {
        log.info("上报编码能力: agentId={}", agentInfo.getId());
    }

    @Override
    public byte[] captureScreen() {
        // 1. Native 截图
        byte[] screenshot = screenCapture.capture();
        // 2. 自研编码
        byte[] encoded = nativeEncoder.encode(screenshot);
        return encoded;
    }

    @Override
    public byte[] captureInputEvent() {
        return new byte[0];
    }

    @Override
    public boolean isServiceMode() {
        return true;
    }

    /**
     * 启动服务模式。
     */
    public void start() {
        running = true;
        // 启动截图采集循环
        new Thread(this::captureLoop, "agent-service-capture-" + agentInfo.getId()).start();
        log.info("服务模式启动: agentId={}", agentInfo.getId());
    }

    /** 截图采集循环 */
    private void captureLoop() {
        while (running) {
            try {
                byte[] screen = captureScreen();
                // 发送到网关（通过回调或队列）
                log.debug("采集并编码画面: agentId={}, size={}", agentInfo.getId(), screen.length);
                Thread.sleep(33); // ~30fps
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * 停止服务模式。
     */
    public void stop() {
        running = false;
        screenCapture.close();
        nativeEncoder.close();
    }
}

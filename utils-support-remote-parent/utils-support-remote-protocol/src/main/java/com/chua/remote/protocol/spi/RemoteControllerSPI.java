package com.chua.remote.protocol.spi;

import com.chua.remote.protocol.capability.CodecProfile;
import com.chua.remote.protocol.model.ControllerInfo;

public interface RemoteControllerSPI {

    String connect(ControllerInfo info);

    String startSession(String agentId, String verifyCode);

    void reportCapability(CodecProfile capability);

    void renderFrame(byte[] frameData);

    void injectInputEvent(byte[] eventData);
}

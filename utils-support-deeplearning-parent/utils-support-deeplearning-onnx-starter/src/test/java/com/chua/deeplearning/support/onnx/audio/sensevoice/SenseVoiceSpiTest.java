package com.chua.deeplearning.support.onnx.audio.sensevoice;

import com.chua.common.support.ai.audio.AudioClient;
import com.chua.common.support.ai.audio.AudioClientSetting;
import com.chua.common.support.spi.ServiceProvider;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SenseVoiceSpiTest {

    @Test
    public void should_resolve_sensevoice_provider() {
        AudioClient client = AudioClient.create("sensevoice", "");
        assertNotNull(client);
        assertTrue(client instanceof SenseVoiceAudioClient);
    }

    @Test
    public void should_resolve_sensevoice_aliases() {
        assertNotNull(AudioClient.create("sensevoice-small", ""));
        assertNotNull(AudioClient.create("sense-voice", ""));
    }

    @Test
    public void should_list_sensevoice_in_audio_clients() {
        Set<String> providers = ServiceProvider.of(AudioClient.class).getExtensions();
        assertTrue(providers.stream().anyMatch(p -> p.equalsIgnoreCase("sensevoice")),
                "SenseVoice must be discoverable via SPI, got: " + providers);
    }

    @Test
    public void should_resolve_through_setting() {
        AudioClientSetting setting = AudioClientSetting.builder()
                .provider("sensevoice")
                .appKey("")
                .model("sensevoice-small")
                .language("zh")
                .build();
        AudioClient client = AudioClient.create(setting);
        assertNotNull(client);
        assertTrue(client instanceof SenseVoiceAudioClient);
    }
}

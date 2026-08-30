package io.github.syncnuke.client;

import io.github.syncnuke.client.internal.net.Codec;
import io.github.syncnuke.client.internal.net.NetClient;
import io.github.syncnuke.player.internal.PlayerManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncClientTest {

    @Mock
    PlayerManager videoPlayer;
    @Mock
    NetClient<Object> netClient;
    @Mock
    Codec<Object> codec;

    TestableTimingService timingService;
    TestableSyncClient client;

    @BeforeEach
    void setUp() {
        timingService = new TestableTimingService();
        client = spy(new TestableSyncClient(0, videoPlayer, netClient, timingService));
    }

    @Test
    void connect_CallsNetClientConnectAsExpected() {
        client.connect("localhost", 8080, codec);

        verify(netClient).connect("localhost", 8080, codec);
        verify(netClient).addListener(any());
    }

    @Test
    void send_SkipsSend_WhenDataIsNull() {
        client.send(null);

        verify(netClient, never()).send(any());
    }

    @Test
    void send_DelegatesToNetClient_WhenDataIsNotNull() {
        Object payload = new Object();
        client.send(payload);

        verify(netClient).send(payload);
    }

}

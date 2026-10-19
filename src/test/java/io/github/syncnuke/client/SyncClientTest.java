package io.github.syncnuke.client;

import io.github.syncnuke.client.internal.net.Codec;
import io.github.syncnuke.client.internal.net.NetClient;
import io.github.syncnuke.player.internal.PlayerManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static io.github.syncnuke.client.ClientTestSupport.awaitIdle;
import static io.github.syncnuke.client.ClientTestSupport.runEvent;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncClientTest {

    @Mock
    PlayerManager mediaPlayer;
    @Mock
    NetClient<Object> netClient;
    @Mock
    Codec<Object> codec;

    TestableTimingService timingService;
    TestableSyncClient client;

    @BeforeEach
    void setUp() {
        timingService = new TestableTimingService();
        client = spy(new TestableSyncClient(0, mediaPlayer, netClient, timingService));
    }

    @AfterEach
    void tearDown() {
        client.close();
        awaitIdle(client);
    }

    @Test
    void connect_CallsNetClientConnectAsExpected() {
        client.connect("localhost", 8080, codec);
        awaitIdle(client);

        verify(netClient).connect("localhost", 8080, codec);
        verify(netClient).addListener(any());
    }

    @Test
    void send_SkipsSend_WhenDataIsNull() {
        runEvent(client, () -> client.send(null));
        awaitIdle(client);

        verify(netClient, never()).send(any());
    }

    @Test
    void send_DelegatesToNetClient_WhenDataIsNotNull() {
        Object payload = new Object();
        runEvent(client, () -> client.send(payload));
        awaitIdle(client);

        verify(netClient).send(payload);
    }

}

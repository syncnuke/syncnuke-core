package io.github.syncnuke.client.internal.protocol.master;

import io.github.syncnuke.client.UnsupportedSyncProtocolException;
import io.github.syncnuke.client.internal.net.Codec;
import io.github.syncnuke.client.internal.net.NetClient;
import io.github.syncnuke.client.internal.net.NetListener;
import io.github.syncnuke.client.internal.protocol.master.data.ConnectData;
import io.github.syncnuke.client.internal.protocol.master.data.JoinData;
import io.github.syncnuke.client.internal.protocol.master.data.BaseData;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasterClientTest {

    @Test
    void joinsMasterAndReturnsEndpoint() throws Exception {
        ConnectData endpoint = new ConnectData("backend.example", 8999, "datasaver", "0.1.0");
        FakeNetClient netClient = new FakeNetClient(endpoint);
        try (MasterClient client = new MasterClient(netClient)) {
            assertEquals(
                    endpoint,
                    client.join("master.example", 443, "datasaver", "0.1.0", "room", "secret")
            );
        }

        assertEquals("master.example", netClient.host);
        assertEquals(443, netClient.port);
        assertEquals(new JoinData("datasaver", "0.1.0", "room", "secret"), netClient.sent);
        assertTrue(netClient.closed);
    }

    @Test
    void rejectsEmptyEndpoint() throws Exception {
        try (MasterClient client = new MasterClient(
                new FakeNetClient(new ConnectData("", 0, "", ""))
        )) {
            UnsupportedSyncProtocolException error = assertThrows(
                    UnsupportedSyncProtocolException.class,
                    () -> client.join("master.example", 443, "datasaver", "0.1.0", "room", null)
            );
            assertEquals("No endpoint supports datasaver version 0.1.0", error.getMessage());
        }
    }

    private static class FakeNetClient implements NetClient<BaseData> {

        private final ConnectData response;
        private NetListener<BaseData> listener;
        private String host;
        private int port;
        private BaseData sent;
        private boolean closed;

        private FakeNetClient(ConnectData response) {
            this.response = response;
        }

        @Override
        public void connect(String host, int port, Codec<BaseData> codec) {
            this.host = host;
            this.port = port;
            assertTrue(codec instanceof MasterCodec);
        }

        @Override
        public void send(BaseData data) {
            sent = data;
            listener.onResponse(response);
        }

        @Override
        public void addListener(NetListener<BaseData> listener) {
            this.listener = listener;
        }

        @Override
        public boolean removeListener(NetListener<BaseData> listener) {
            return this.listener == listener;
        }

        @Override
        public void close() throws IOException {
            closed = true;
        }

    }

}

package io.github.syncnuke.client.internal.protocol.master;

import io.github.syncnuke.client.UnsupportedSyncProtocolException;
import io.github.syncnuke.client.internal.net.NetClient;
import io.github.syncnuke.client.internal.net.QuicClient;
import io.github.syncnuke.client.internal.protocol.master.data.ConnectData;
import io.github.syncnuke.client.internal.protocol.master.data.JoinData;
import io.github.syncnuke.client.internal.protocol.master.data.BaseData;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class MasterClient implements AutoCloseable {

    private final NetClient<BaseData> netClient;

    public MasterClient() {
        this(new QuicClient<>());
    }

    MasterClient(NetClient<BaseData> netClient) {
        this.netClient = Objects.requireNonNull(netClient, "netClient");
    }

    public ConnectData join(
            String host,
            int port,
            String protocol,
            String version,
            String room,
            String password
    ) {
        CompletableFuture<ConnectData> response = new CompletableFuture<>();
        netClient.connect(host, port, new MasterCodec());
        netClient.addListener(data -> {
            if (data instanceof ConnectData connect) {
                response.complete(connect);
            }
        });
        netClient.send(new JoinData(protocol, version, room, password));
        ConnectData connect;
        try {
            connect = response.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for master response", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to receive master response", e.getCause());
        }
        if (connect.getHost().isEmpty()) {
            throw new UnsupportedSyncProtocolException(
                    "No endpoint supports " + protocol + " version " + version
            );
        }
        return connect;
    }

    @Override
    public void close() throws Exception {
        netClient.close();
    }

}

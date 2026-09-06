package io.github.syncnuke.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SyncClientFactoryTest {

    @Test
    void rejectsUnsupportedProtocol() {
        UnsupportedSyncProtocolException error = assertThrows(
                UnsupportedSyncProtocolException.class,
                () -> SyncClientFactory.protocolVersion("syncplay")
        );

        assertEquals("Unsupported sync protocol: syncplay", error.getMessage());
    }

    @Test
    void rejectsUnsupportedDataSaverVersion() {
        UnsupportedSyncProtocolException error = assertThrows(
                UnsupportedSyncProtocolException.class,
                () -> SyncClientFactory.createClient(
                        "datasaver", "1.0.0", "backend.example", 8999, null
                )
        );

        assertEquals("Unsupported DataSaver version: 1.0.0", error.getMessage());
    }

}

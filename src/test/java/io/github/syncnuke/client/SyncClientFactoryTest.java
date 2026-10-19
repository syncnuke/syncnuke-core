package io.github.syncnuke.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SyncClientFactoryTest {

    @Test
    void rejectsUnsupportedProtocol() {
        UnsupportedSyncProtocolException error = assertThrows(
                UnsupportedSyncProtocolException.class,
                () -> SyncClientFactory.protocolVersion("unsupported")
        );

        assertEquals("Unsupported sync protocol: unsupported", error.getMessage());
    }

    @Test
    void rejectsUnsupportedDataSaverVersion() {
        UnsupportedSyncProtocolException error = assertThrows(
                UnsupportedSyncProtocolException.class,
                () -> SyncClientFactory.createClient(
                        "datasaver", "1.0.0", "backend.example", 65344, null
                )
        );

        assertEquals("Unsupported DataSaver version: 1.0.0", error.getMessage());
    }

    @Test
    void resolvesSyncplayVersionCaseInsensitively() {
        assertEquals("1.7.7", SyncClientFactory.protocolVersion("SyNcPlAy"));
    }

    @Test
    void rejectsUnsupportedSyncplayVersion() {
        UnsupportedSyncProtocolException error = assertThrows(
                UnsupportedSyncProtocolException.class,
                () -> SyncClientFactory.createClient("syncplay", "0.1.0", "localhost", 8999, null));
        assertEquals("Unsupported Syncplay version: 0.1.0", error.getMessage());
    }

}

package io.github.syncnuke.client.internal.protocol.syncplay;

import io.github.syncnuke.client.internal.protocol.syncplay.data.SyncplayCodec;

import com.fasterxml.jackson.databind.JsonNode;
import pl.syncplay.proto.SyncplayProto.SyncplayMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.syncnuke.client.internal.net.NetClient;
import io.github.syncnuke.client.internal.net.NetListener;
import io.github.syncnuke.player.MediaPlayer;
import io.github.syncnuke.player.NoMediaLoadedException;
import io.github.syncnuke.player.data.PlaybackState;
import io.github.syncnuke.player.data.PlayerState;
import io.github.syncnuke.player.data.PlayerStateUtils;
import io.github.syncnuke.player.internal.PlayerManager;
import io.github.syncnuke.service.TimingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static io.github.syncnuke.client.ClientTestSupport.awaitIdle;
import static io.github.syncnuke.client.ClientTestSupport.runEvent;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncplayClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Mock PlayerManager player;
    @Mock NetClient<SyncplayMessage> network;
    @Mock TimingService timing;
    private final AtomicLong now = new AtomicLong(10000);
    private final AtomicReference<PlayerState> live = new AtomicReference<>();
    private final List<JsonNode> sent = new CopyOnWriteArrayList<>();
    private SyncplayClient client;
    private NetListener<SyncplayMessage> listener;

    @BeforeEach
    void setUp() {
        live.set(state(0, true));
        lenient().when(timing.getCurrentTime()).thenAnswer(invocation -> now.get());
        lenient().when(player.getStatus()).thenAnswer(invocation -> live.get().copy());
        lenient().doAnswer(invocation -> {
            live.set(new PlayerState(invocation.getArgument(0)));
            return null;
        }).when(player).updateStatus(any());
        lenient().doAnswer(invocation -> {
            sent.add(SyncplayCodec.toJson(invocation.getArgument(0)));
            return null;
        }).when(network).send(any());
        client = new SyncplayClient("localhost", 8999, player, network, timing);
        awaitIdle(client);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<NetListener<SyncplayMessage>> capture = ArgumentCaptor.forClass(NetListener.class);
        verify(network).addListener(capture.capture());
        listener = capture.getValue();
    }

    @AfterEach
    void tearDown() {
        client.close();
        awaitIdle(client);
    }

    @Test
    void loginUsesReferenceGreetingAndServerPasswordHash() {
        client.login("alice", "movie-night", "password");
        awaitIdle(client);
        JsonNode hello = sent.getFirst().get("Hello");
        assertEquals("alice", hello.get("username").asText());
        assertEquals("movie-night", hello.path("room").path("name").asText());
        assertEquals("5f4dcc3b5aa765d61d8327deb882cf99", hello.get("password").asText());
        assertEquals("1.2.255", hello.get("version").asText());
        assertEquals(SyncplayClient.VERSION, hello.get("realversion").asText());
        assertFalse(hello.path("features").path("sharedPlaylists").asBoolean());
        assertFalse(hello.path("features").path("readiness").asBoolean());
        verify(network).connect(eq("localhost"), eq(8999), any(SyncplayCodec.class));
    }

    @Test
    void acceptsServerAssignedIdentityAndTracksOnlyCurrentRoom() throws Exception {
        login();
        receive("""
                {"List":{"room":{"alice_":{"file":{}},"bob":{"file":{}}},"elsewhere":{"eve":{}}}}
                """);
        receive("""
                {"Set":{"user":{"carol":{"room":{"name":"room"},"event":{"joined":true}},
                "eve":{"room":{"name":"elsewhere"},"event":{"joined":true}}}}}
                """);
        receive("""
                {"Set":{"user":{"bob":{"room":{"name":"elsewhere"}},"carol":{"event":{"left":true}}}}}
                """);
        assertEquals("room", client.getRoomInfo().getRoom());
        assertEquals(List.of("alice_"), client.getRoomInfo().getUsers());
        receive("{\"Set\":{\"user\":{\"alice_\":{\"room\":{\"name\":\"normalized-room\"}}}}}");
        assertEquals("normalized-room", client.getRoomInfo().getRoom());
        client.getRoomInfo().getUsers().clear();
        assertEquals(List.of("alice_"), client.getRoomInfo().getUsers());
        assertTrue(sent.stream().anyMatch(message -> message.has("List") && message.get("List").isNull()));
    }

    @Test
    void roomChangeUsesSetAndResetsPlaybackSession() throws Exception {
        login();
        receiveState(10, true, "");
        sent.clear();
        client.login("alice_", "other-room", null);
        awaitIdle(client);
        assertEquals("other-room", sent.getFirst().path("Set").path("room").path("name").asText());
        receive("{\"Set\":{\"room\":{\"name\":\"other-room\"}}}");
        receiveState(90, true, "");
        assertEquals(90, live.get().getPosition());
        assertEquals(List.of("alice_"), client.getRoomInfo().getUsers());
    }

    @Test
    void initialStateUsesServerPositionAndRepliesAfterApplyingCommand() throws Exception {
        login();
        live.set(state(100, false));
        receiveState(10, true, "");
        assertEquals(10, live.get().getPosition());
        assertEquals(PlaybackState.PAUSED, live.get().getPlaybackState());
        JsonNode reply = lastState();
        assertEquals(10, reply.path("playstate").path("position").asDouble());
        assertTrue(reply.path("playstate").path("paused").asBoolean());
        assertFalse(reply.has("ignoringOnTheFly"));
    }

    @Test
    void advancesPlayingPositionByReferenceLatencyEstimate() throws Exception {
        login();
        receive("""
                {"State":{"playstate":{"position":100,"paused":false},
                "ping":{"clientLatencyCalculation":9.8,"serverRtt":0.1,"latencyCalculation":42.5}}}
                """);
        assertEquals(100.2, live.get().getPosition(), 0.000001);
        assertEquals(0.2, lastState().path("ping").path("clientRtt").asDouble(), 0.000001);
        assertEquals(42.5, lastState().path("ping").path("latencyCalculation").asDouble());
        assertEquals(10.0, lastState().path("ping").path("clientLatencyCalculation").asDouble());
    }

    @Test
    void pausedPositionDoesNotAdvanceWithLatency() throws Exception {
        login();
        receive("""
                {"State":{"playstate":{"position":100,"paused":true},
                "ping":{"clientLatencyCalculation":9.8,"serverRtt":0.1}}}
                """);
        assertEquals(100, live.get().getPosition());
    }

    @Test
    void sendsLocalSeekAndSuppressesStaleStatesUntilMatchingAcknowledgement() throws Exception {
        login();
        receiveState(10, true, "");
        client.onStatusChange(state(40, true));
        awaitIdle(client);
        assertTrue(lastState().path("playstate").path("doSeek").asBoolean());
        assertEquals(1, lastState().path("ignoringOnTheFly").path("client").asInt());
        clearInvocations(player);
        receiveState(10, true, "");
        assertFalse(lastState().has("playstate"));
        verify(player, never()).updateStatus(any());
        live.set(state(40, true));
        receiveState(40, true, ",\"ignoringOnTheFly\":{\"client\":1}");
        assertTrue(lastState().has("playstate"));
        assertFalse(lastState().has("ignoringOnTheFly"));
    }

    @Test
    void serverTickDoesNotConsumeSeekBeforePlayerPoll() throws Exception {
        login();
        receiveState(10, true, "");
        live.set(state(40, true));
        receive("{\"State\":{\"playstate\":{\"position\":10,\"paused\":true,\"setBy\":\"alice_\"}}}");
        sent.clear();
        client.onStatusChange(live.get());
        awaitIdle(client);
        assertTrue(lastState().path("playstate").path("doSeek").asBoolean());
        assertEquals(40, lastState().path("playstate").path("position").asDouble());
        assertEquals(1, lastState().path("ignoringOnTheFly").path("client").asInt());
    }

    @Test
    void serverTickDoesNotGenerateLocalChangeEvents() throws Exception {
        login();
        receiveState(10, false, "");
        live.set(state(10, true));
        sent.clear();
        receiveState(10, false, "");
        assertEquals(1, sent.size());
        assertFalse(lastState().has("ignoringOnTheFly"));

        client.onStatusChange(state(10, true));
        awaitIdle(client);
        assertTrue(lastState().path("playstate").path("paused").asBoolean());
        assertEquals(1, lastState().path("ignoringOnTheFly").path("client").asInt());
    }

    @Test
    void unchangedServerHeartbeatLeavesLocalPauseForPlayerManagerToReport() throws Exception {
        login();
        receiveState(10, false, "");
        live.set(state(10, true));
        sent.clear();
        clearInvocations(player);
        receive("{\"State\":{\"playstate\":{\"position\":10,\"paused\":false,\"setBy\":\"bob\"}}}");
        assertEquals(PlaybackState.PAUSED, live.get().getPlaybackState());
        verify(player, never()).updateStatus(any());
        assertEquals(1, sent.size());
        assertFalse(lastState().has("ignoringOnTheFly"));

        client.onStatusChange(live.get());
        awaitIdle(client);
        assertTrue(lastState().path("playstate").path("paused").asBoolean());
        assertEquals(1, lastState().path("ignoringOnTheFly").path("client").asInt());
    }

    @Test
    void rapidLocalPauseAndUnpauseCarryLatestStateAndRejectOlderAcknowledgement() throws Exception {
        login();
        receiveState(10, false, "");
        client.onStatusChange(state(10, true));
        client.onStatusChange(state(10, false));
        awaitIdle(client);
        assertEquals(2, lastState().path("ignoringOnTheFly").path("client").asInt());
        assertTrue(lastState().has("playstate"));
        assertFalse(lastState().path("playstate").path("paused").asBoolean());
        clearInvocations(player);
        receiveState(10, true, ",\"ignoringOnTheFly\":{\"client\":1}");
        verify(player, never()).updateStatus(any());
        assertFalse(lastState().has("playstate"));
        receiveState(10, false, ",\"ignoringOnTheFly\":{\"client\":2}");
        assertFalse(lastState().has("ignoringOnTheFly"));
    }

    @Test
    void forcedServerSeekOverridesPendingLocalChangeAndAcknowledgesServerCounter() throws Exception {
        login();
        receiveState(10, true, "");
        client.onStatusChange(state(40, true));
        awaitIdle(client);
        receiveState(80, true, ",\"ignoringOnTheFly\":{\"server\":7}");
        assertEquals(80, live.get().getPosition());
        assertEquals(7, lastState().path("ignoringOnTheFly").path("server").asInt());
        assertFalse(lastState().path("ignoringOnTheFly").has("client"));
        receiveState(80, true, "");
        assertFalse(lastState().has("ignoringOnTheFly"));
    }

    @Test
    void serverPlayerCommandsDoNotEchoAsLocalChanges() throws Exception {
        login();
        receiveState(10, true, "");
        doAnswer(invocation -> {
            PlayerState desired = new PlayerState(invocation.getArgument(0));
            live.set(desired);
            client.onStatusChange(desired);
            return null;
        }).when(player).updateStatus(any());
        sent.clear();
        receiveState(80, false, ",\"ignoringOnTheFly\":{\"server\":1}");
        assertEquals(1, sent.size());
        assertFalse(lastState().path("ignoringOnTheFly").has("client"));
        client.onStatusChange(live.get());
        awaitIdle(client);
        assertEquals(1, sent.size());
    }

    @Test
    void steadyPlaybackAndSpeedChangesDoNotBecomeSeeks() throws Exception {
        login();
        receiveState(10, false, "");
        sent.clear();
        now.addAndGet(2000);
        PlayerState status = state(12, false);
        status.setPlaybackSpeed(1.25);
        client.onStatusChange(status);
        awaitIdle(client);
        assertTrue(sent.isEmpty());
        assertEquals(1.0, live.get().getPlaybackSpeed());
        assertEquals(12, live.get().getPosition());
        assertEquals(1.25, status.getPlaybackSpeed());
    }

    @Test
    void usesReferenceSlowdownAndResetThresholds() throws Exception {
        login();
        receiveState(10, false, "");
        live.set(state(12, false));
        receive("{\"State\":{\"playstate\":{\"position\":10,\"paused\":false,\"setBy\":\"bob\"}}}");
        assertEquals(0.95, live.get().getPlaybackSpeed());
        client.onStatusChange(live.get());
        awaitIdle(client);
        assertEquals(0.95, live.get().getPlaybackSpeed());
        live.set(state(10.05, false));
        live.get().setPlaybackSpeed(0.95);
        receive("{\"State\":{\"playstate\":{\"position\":10,\"paused\":false,\"setBy\":\"bob\"}}}");
        assertEquals(1.0, live.get().getPlaybackSpeed());
        live.set(state(20, false));
        receive("{\"State\":{\"playstate\":{\"position\":10,\"paused\":false,\"setBy\":\"bob\"}}}");
        assertEquals(10, live.get().getPosition());
    }

    @Test
    void ownSeekIsNotReapplied() throws Exception {
        login();
        receiveState(10, false, "");
        live.set(state(12, false));
        receive("{\"State\":{\"playstate\":{\"position\":10,\"paused\":false,\"doSeek\":true,\"setBy\":\"alice_\"}}}");
        assertEquals(12, live.get().getPosition());
        assertEquals(1.0, live.get().getPlaybackSpeed());
    }

    @Test
    void noMediaStillAcknowledgesStateAndInitializesWhenMediaArrives() throws Exception {
        login();
        when(player.getStatus()).thenThrow(new NoMediaLoadedException());
        receiveState(10, false, ",\"ignoringOnTheFly\":{\"server\":2}");
        assertFalse(lastState().has("playstate"));
        assertEquals(2, lastState().path("ignoringOnTheFly").path("server").asInt());
        doAnswer(invocation -> live.get().copy()).when(player).getStatus();
        receiveState(50, false, "");
        assertEquals(50, live.get().getPosition());
    }

    @Test
    void keepAliveWaitsForHelloAndForInitialState() throws Exception {
        runEvent(client, client::sendKeepAlive);
        awaitIdle(client);
        assertTrue(sent.isEmpty());
        login();
        runEvent(client, client::sendKeepAlive);
        awaitIdle(client);
        assertFalse(lastState().has("playstate"));
        receiveState(10, true, "");
        live.set(state(20, true));
        runEvent(client, client::sendKeepAlive);
        awaitIdle(client);
        assertEquals(20, lastState().path("playstate").path("position").asDouble());
    }

    @Test
    void errorClosesConnectionWithoutInventingLeaveMessage() throws Exception {
        login();
        sent.clear();
        receive("{\"Error\":{\"message\":\"Wrong password\"}}");
        verify(network).close();
        assertTrue(sent.isEmpty());
    }

    @Test
    void invalidGreetingClosesSessionWithoutRequestingRoomList() throws Exception {
        client.login("alice", "room", null);
        awaitIdle(client);
        sent.clear();
        receive("{\"Hello\":{\"username\":\"\",\"room\":{\"name\":\"room\"},\"version\":\"1.7.7\"}}");
        verify(network).close();
        assertTrue(sent.isEmpty());
    }

    @Test
    void fatalCommandStopsRemainingCommandsInTheFrame() throws Exception {
        login();
        receiveState(10, true, "");
        sent.clear();
        clearInvocations(player);
        receive("{\"Error\":{\"message\":\"Wrong password\"},"
                + "\"State\":{\"playstate\":{\"position\":80,\"paused\":true,\"doSeek\":true}}}");
        verify(network).close();
        verify(player, never()).updateStatus(any());
        assertEquals(10, live.get().getPosition());
        assertTrue(sent.isEmpty());
    }

    @Test
    void codecPreservesUnicodeEscapesAndConsecutiveFrames() throws Exception {
        SyncplayCodec codec = new SyncplayCodec();
        JsonNode message = JSON.readTree("{\"Hello\":{\"username\":\"é\\n\\\"猫\"}}");
        byte[] bytes = codec.encode(SyncplayCodec.fromJson(message));
        assertEquals('\r', bytes[bytes.length - 2]);
        assertEquals('\n', bytes[bytes.length - 1]);
        ByteArrayInputStream stream = input(new String(bytes, StandardCharsets.UTF_8) + "\r\n{\"List\":null}\r\n");
        assertEquals(message, SyncplayCodec.toJson(codec.decode(stream)));
        assertEquals(JSON.readTree("{\"List\":null}"), SyncplayCodec.toJson(codec.decode(stream)));
        assertThrows(EOFException.class, () -> codec.decode(stream));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]\r\n", "null\r\n", "{bad}\r\n", "{} {}\r\n", "{\"State\":{}}"})
    void codecRejectsMalformedOrTruncatedFrames(String wire) {
        assertThrows(IOException.class, () -> new SyncplayCodec().decode(input(wire)));
    }

    @Test
    void codecRejectsInvalidUtf8AndOversizedFrames() throws Exception {
        SyncplayCodec codec = new SyncplayCodec();
        assertThrows(IOException.class, () -> codec.decode(new ByteArrayInputStream(new byte[]{(byte) 0xff, '\n'})));
        assertThrows(IOException.class, () -> codec.decode(input(" ".repeat(16385) + "\r\n")));
        assertThrows(IOException.class, () -> codec.encode(SyncplayCodec.fromJson(JSON.createObjectNode().put("Chat", "x".repeat(16384)))));
    }

    @Test
    void unusedSettingsNeverChangePlaybackAndPreserveTheirPayload() throws Exception {
        login();
        receiveState(10, true, "");
        clearInvocations(player);
        String wire = "{\"Set\":{\"ready\":{\"isReady\":true},\"playlistChange\":{\"files\":[\"movie\"]},"
                + "\"file\":{\"name\":\"movie\",\"size\":\"hidden\"}}}";
        receive(wire);
        receive("{\"Chat\":{\"username\":\"bob\",\"message\":\"hi\"}}");
        verify(player, never()).updateStatus(any());
        assertEquals(JSON.readTree(wire), SyncplayCodec.toJson(SyncplayCodec.fromJson(JSON.readTree(wire))));
    }

    @Test
    void typedStatePreservesFalseZeroAndCounterPresence() throws Exception {
        var command = SyncplayCodec.fromJson(JSON.readTree(
                "{\"State\":{\"playstate\":{\"position\":0,\"paused\":false},\"ignoringOnTheFly\":{\"server\":0}}}"));
        assertTrue(command.getState().getPlaystate().hasPaused());
        assertTrue(command.getState().getPlaystate().hasPosition());
        assertTrue(command.getState().getIgnoringOnTheFly().hasServer());
        assertFalse(command.getState().getIgnoringOnTheFly().hasClient());
        assertFalse(SyncplayCodec.toJson(command).path("State").path("playstate").path("paused").asBoolean());
    }

    @Test
    void processesMultipleCommandsInOneFrameInOrder() throws Exception {
        client.login("alice", "room", null);
        awaitIdle(client);
        receive("{\"Hello\":{\"username\":\"alice_\",\"room\":{\"name\":\"room\"},\"version\":\"1.7.7\"},"
                + "\"State\":{\"playstate\":{\"position\":42,\"paused\":true}}}");
        assertEquals(42, live.get().getPosition());
    }

    @Test
    void userSeekBackToPreviousPositionIsNotSuppressedAfterServerCommand() throws Exception {
        login();
        receiveState(10, true, "");
        receiveState(80, false, ",\"ignoringOnTheFly\":{\"server\":1}");
        assertEquals(80, live.get().getPosition());
        sent.clear();

        // PlayerManager has detected a genuine user seek after the server command completed.
        client.onStatusChange(state(10, false));
        awaitIdle(client);
        assertEquals(1, sent.size());
        assertEquals(10, lastState().path("playstate").path("position").asDouble());
        assertTrue(lastState().path("playstate").path("doSeek").asBoolean());
        assertEquals(1, lastState().path("ignoringOnTheFly").path("client").asInt());
    }

    @Test
    void ordinaryRoomReportsLagRatherThanForcingCatchUp() throws Exception {
        login();
        receiveState(100, false, "");
        live.set(state(90, false));
        receive("{\"State\":{\"playstate\":{\"position\":100,\"paused\":false,\"setBy\":\"bob\"}}}");
        now.addAndGet(4000);
        live.set(state(94, false));
        receive("{\"State\":{\"playstate\":{\"position\":104,\"paused\":false,\"setBy\":\"bob\"}}}");
        assertEquals(94, live.get().getPosition());
        assertEquals(94, lastState().path("playstate").path("position").asDouble());
    }

    @Test
    void followerFastForwardUsesPythonDelayAndExtraTime() throws Exception {
        login();
        receive("{\"Set\":{\"room\":{\"name\":\"+movie:abcdefghijkl\"}}}");
        receiveState(100, false, "");
        live.set(state(90, false));
        receive("{\"State\":{\"playstate\":{\"position\":100,\"paused\":false,\"setBy\":\"bob\"}}}");
        now.addAndGet(3250);
        live.set(state(93.25, false));
        receive("{\"State\":{\"playstate\":{\"position\":103.25,\"paused\":false,\"setBy\":\"bob\"}}}");
        assertEquals(93.25, live.get().getPosition());
        now.incrementAndGet();
        receive("{\"State\":{\"playstate\":{\"position\":103.251,\"paused\":false,\"setBy\":\"bob\"}}}");
        assertEquals(103.501, live.get().getPosition(), 0.000001);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"State\":{\"playstate\":{\"position\":-1,\"paused\":true}}}\n",
        "{\"State\":{\"playstate\":{\"position\":\"NaN\",\"paused\":true}}}\n",
        "{\"State\":{\"playstate\":{\"paused\":\"false\"}}}\n",
        "{\"State\":{\"ping\":{\"serverRtt\":-1}}}\n",
        "{\"State\":{\"ignoringOnTheFly\":{\"client\":1.5}}}\n"
    })
    void rejectsMalformedStateFields(String wire) {
        assertThrows(IOException.class, () -> new SyncplayCodec().decode(input(wire)));
    }

    @Test
    void jitterUsesReferenceMovingAverageRatherThanLatestRttOnly() throws Exception {
        login();
        receive("{\"State\":{\"playstate\":{\"position\":10,\"paused\":false},"
                + "\"ping\":{\"clientLatencyCalculation\":9.8,\"serverRtt\":0.1}}}");
        now.set(11000);
        live.set(state(11.2, false));
        receive("{\"State\":{\"playstate\":{\"position\":11,\"paused\":false,\"doSeek\":true,\"setBy\":\"bob\"},"
                + "\"ping\":{\"clientLatencyCalculation\":10.6,\"serverRtt\":0.3}}}");
        // Average = .2*.85 + .4*.15 = .23; forward delay = .115 + .1.
        assertEquals(11.215, live.get().getPosition(), 0.000001);
        assertEquals(0.4, lastState().path("ping").path("clientRtt").asDouble(), 0.000001);
    }

    @Test
    void invalidClockSampleDoesNotReplaceValidLatencyEstimate() throws Exception {
        login();
        receive("{\"State\":{\"ping\":{\"clientLatencyCalculation\":9.8,\"serverRtt\":0.1}}}");
        receive("{\"State\":{\"playstate\":{\"position\":10,\"paused\":false},"
                + "\"ping\":{\"clientLatencyCalculation\":20,\"serverRtt\":0.1}}}");
        assertEquals(10.2, live.get().getPosition(), 0.000001);
        assertEquals(0.2, lastState().path("ping").path("clientRtt").asDouble(), 0.000001);
    }

    @Test
    void configurableThresholdsApplyWithoutChangingPlayerInterface() throws Exception {
        client.close();
        awaitIdle(client);
        reset(network);
        doAnswer(call -> { sent.add(SyncplayCodec.toJson(call.getArgument(0))); return null; }).when(network).send(any());
        sent.clear();
        client = new SyncplayClient("localhost", 8999, player, network, timing,
                new SyncplayClient.Thresholds(6, 7, 3));
        awaitIdle(client);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<NetListener<SyncplayMessage>> capture = ArgumentCaptor.forClass(NetListener.class);
        verify(network).addListener(capture.capture());
        listener = capture.getValue();
        login();
        receiveState(10, false, "");
        live.set(state(12, false));
        receive("{\"State\":{\"playstate\":{\"position\":10,\"paused\":false,\"setBy\":\"bob\"}}}");
        assertEquals(1, live.get().getPlaybackSpeed());
        live.set(state(15, false));
        receive("{\"State\":{\"playstate\":{\"position\":10,\"paused\":false,\"setBy\":\"bob\"}}}");
        assertEquals(15, live.get().getPosition());
        assertEquals(0.95, live.get().getPlaybackSpeed());
        assertThrows(IllegalArgumentException.class, () -> new SyncplayClient.Thresholds(Double.NaN, 5, 1.5));
    }

    @Test
    void unchangedPausedHeartbeatLeavesLocalSeekForPlayerManagerToReport() throws Exception {
        login();
        receiveState(10, true, "");
        live.set(state(40, true));
        clearInvocations(player);
        receive("{\"State\":{\"playstate\":{\"position\":10,\"paused\":true,\"setBy\":\"bob\"}}}");
        verify(player, never()).updateStatus(any());
        assertEquals(40, live.get().getPosition());
        client.onStatusChange(live.get());
        awaitIdle(client);
        assertEquals(40, lastState().path("playstate").path("position").asDouble());
        assertTrue(lastState().path("playstate").path("doSeek").asBoolean());
    }

    @Test
    void announcesRealFilenameAndRefreshesMediaWithoutRepeatingHeartbeats() throws Exception {
        login();
        live.get().setDuration(300);
        live.get().setFileName("cinéma 🎬.mkv");
        receiveState(10, true, "");
        JsonNode file = sent.stream().filter(m -> m.path("Set").has("file"))
                .findFirst().orElseThrow().path("Set").path("file");
        assertEquals("cinéma 🎬.mkv", file.path("name").asText());
        assertEquals(300, file.path("duration").asDouble());
        assertTrue(file.path("size").isNumber());
        assertEquals(0, file.path("size").asInt());
        assertFalse(file.has("path"));
        assertEquals(300, live.get().getDuration(), "Duration survives player-state copies");
        sent.clear();
        receiveState(10, true, "");
        assertEquals(1, sent.size());
        live.get().setDuration(400);
        runEvent(client, client::sendKeepAlive);
        awaitIdle(client);
        assertEquals(400, sent.get(1).path("Set").path("file").path("duration").asDouble());
        sent.clear();
        live.get().setFileName("replacement.mkv");
        client.onStatusChange(live.get());
        awaitIdle(client);
        assertEquals("replacement.mkv", sent.getFirst().path("Set").path("file").path("name").asText());
        assertEquals("replacement.mkv", client.getRoomInfo().getFiles().get("alice_").getFileName());
    }

    @Test
    void exposesPeerFilenamesWithoutLoadingTheirMedia() throws Exception {
        login();
        receive("{\"List\":{\"room\":{\"bob\":{\"file\":{\"name\":\"movie.mkv\",\"duration\":300,\"size\":\"hashed\"}}}}}");
        assertEquals("movie.mkv", client.getRoomInfo().getFiles().get("bob").getFileName());
        receive("{\"Set\":{\"user\":{\"bob\":{\"file\":{\"name\":\"other.mkv\",\"duration\":400}}}}}");
        assertEquals("other.mkv", client.getRoomInfo().getFiles().get("bob").getFileName());
        receive("{\"Set\":{\"user\":{\"bob\":{\"file\":null}}}}");
        assertFalse(client.getRoomInfo().getFiles().containsKey("bob"));
        verify(player, never()).updateStatus(any());
    }

    @Test
    void clearsUnloadedMediaAndInitializesReplacementMedia() throws Exception {
        login();
        receiveState(10, true, "");
        when(player.getStatus()).thenThrow(new NoMediaLoadedException());
        sent.clear();
        runEvent(client, client::sendKeepAlive);
        awaitIdle(client);
        assertTrue(sent.getFirst().path("Set").has("file"));
        assertTrue(sent.getFirst().path("Set").path("file").isNull());
        assertFalse(lastState().has("playstate"));
        live.set(state(0, true));
        doAnswer(invocation -> live.get().copy()).when(player).getStatus();
        sent.clear();
        receive("{\"State\":{\"playstate\":{\"position\":50,\"paused\":true,\"setBy\":\"bob\"}}}");
        assertTrue(sent.getFirst().path("Set").path("file").isObject());
        assertEquals(50, live.get().getPosition());
    }

    @Test
    void reconnectReannouncesLoadedMedia() throws Exception {
        login();
        receiveState(10, true, "");
        var connected = SyncplayClient.class.getDeclaredMethod("onConnected");
        connected.setAccessible(true);
        runEvent(client, () -> {
            try { connected.invoke(client); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        awaitIdle(client);
        receive("{\"Hello\":{\"username\":\"alice_\",\"room\":{\"name\":\"room\"},\"version\":\"1.7.7\"}}");
        sent.clear();
        receiveState(10, true, "");
        assertTrue(sent.getFirst().path("Set").path("file").isObject());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retriesFailedPlaybackCommandOnUnchangedServerState(boolean paused) throws Exception {
        login();
        receiveState(10, !paused, "");
        clearInvocations(player);
        doThrow(new IllegalStateException("Transient player failure"))
                .doAnswer(call -> { live.set(new PlayerState(call.getArgument(0))); return null; })
                .when(player).updateStatus(any());
        String heartbeat = "{\"State\":{\"playstate\":{\"position\":10,\"paused\":" + paused
                + ",\"setBy\":\"bob\"}}}";
        receive(heartbeat);
        assertEquals(paused ? PlaybackState.PLAYING : PlaybackState.PAUSED, live.get().getPlaybackState());
        runEvent(client, client::sendKeepAlive);
        awaitIdle(client);
        assertFalse(lastState().has("playstate"), "Do not publish a failed command as a local action");
        receive(heartbeat);
        assertEquals(paused ? PlaybackState.PAUSED : PlaybackState.PLAYING, live.get().getPlaybackState());
        receive(heartbeat);
        verify(player, times(2)).updateStatus(any());
    }

    @Test
    void localSeekAfterFailureSupersedesRetry() throws Exception {
        login();
        receiveState(10, true, "");
        doThrow(new IllegalStateException("Transient player failure")).when(player).updateStatus(any());
        receive("{\"State\":{\"playstate\":{\"position\":10,\"paused\":false,\"setBy\":\"bob\"}}}");
        live.set(state(40, true));
        client.onStatusChange(live.get());
        awaitIdle(client);
        assertEquals(40, lastState().path("playstate").path("position").asDouble());
        assertTrue(lastState().path("playstate").path("paused").asBoolean());
        clearInvocations(player);
        receive("{\"State\":{\"playstate\":{\"position\":40,\"paused\":true,\"setBy\":\"alice_\"},"
                + "\"ignoringOnTheFly\":{\"client\":1}}}");
        verify(player, never()).updateStatus(any());
    }

    @Test
    void failedOldCommandDoesNotInvalidateNewerLocalSeek() throws Exception {
        login();
        receiveState(10, true, "");
        doAnswer(call -> {
            live.set(state(40, true));
            client.onStatusChange(live.get());
            throw new IllegalStateException("Superseded command failed");
        }).when(player).updateStatus(any());
        receiveState(20, true, "");
        clearInvocations(player);
        receive("{\"State\":{\"playstate\":{\"position\":40,\"paused\":true,\"setBy\":\"alice_\"},"
                + "\"ignoringOnTheFly\":{\"client\":1}}}");
        // A heartbeat ahead of the new local position would only seek if recovery reset the expectation.
        receive("{\"State\":{\"playstate\":{\"position\":50,\"paused\":true,\"setBy\":\"bob\"}}}");
        verify(player, never()).updateStatus(any());
        assertEquals(40, live.get().getPosition());
    }

    @Test
    void delayedIpcObservationsDoNotPublishReverseSeeks() throws Exception {
        login();
        receiveState(10, true, "");
        client.onStatusChange(live.get());
        awaitIdle(client);
        doNothing().when(player).updateStatus(any());
        receiveState(80, false, ",\"ignoringOnTheFly\":{\"server\":1}");
        sent.clear();
        for (int i = 0; i < 3; i++) {
            now.addAndGet(200);
            client.onStatusChange(state(10, i == 0));
            awaitIdle(client);
        }
        assertTrue(sent.isEmpty(), "Old and intermediate IPC observations must not undo the room seek");
        live.set(state(80, false));
        client.onStatusChange(live.get());
        awaitIdle(client);
        assertTrue(sent.isEmpty(), "The applied correction is feedback");
        live.set(state(10, false));
        client.onStatusChange(live.get());
        awaitIdle(client);
        assertTrue(lastState().path("playstate").path("doSeek").asBoolean());
    }

    @Test
    void gradualPlaybackDriftDoesNotBecomeAUserSeek() throws Exception {
        login();
        receiveState(10, false, "");
        sent.clear();
        for (int i = 1; i <= 8; i++) {
            now.addAndGet(250);
            live.set(state(10 + i * 0.4, false));
            client.onStatusChange(live.get());
            awaitIdle(client);
        }
        assertTrue(sent.isEmpty(), "Accumulated drift is corrected by room policy, not broadcast as a user seek");
    }

    private void login() throws Exception {
        client.login("alice", "room", null);
        awaitIdle(client);
        assertFalse(sent.getFirst().path("Hello").has("password"));
        receive("{\"Hello\":{\"username\":\"alice_\",\"room\":{\"name\":\"room\"},\"version\":\"1.7.7\"}}");
    }

    private void receiveState(double position, boolean paused, String extra) throws Exception {
        receive("{\"State\":{\"playstate\":{\"position\":" + position + ",\"paused\":" + paused
                + ",\"doSeek\":true,\"setBy\":\"bob\"}" + extra + "}}");
    }

    private void receive(String json) throws Exception {
        listener.onResponse(SyncplayCodec.fromJson(JSON.readTree(json)));
        awaitIdle(client);
    }

    private JsonNode lastState() {
        return sent.getLast().get("State");
    }

    private PlayerState state(double position, boolean paused) {
        PlayerState state = new PlayerState();
        state.setPosition(position);
        state.setPlaybackState(paused ? PlaybackState.PAUSED : PlaybackState.PLAYING);
        state.setLastUpdateTime(now.get());
        return state;
    }

    private static ByteArrayInputStream input(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}

/** Optional interoperability check; run against an unmodified upstream server. */
@EnabledIfEnvironmentVariable(named = "SYNCPLAY_TEST_PORT", matches = "\\d+")
class SyncplayIntegrationTest {
    @Test
    void clientSessionSynchronizesWithReferenceServer() throws Exception {
        int port = Integer.parseInt(System.getenv("SYNCPLAY_TEST_PORT"));
        String password = System.getenv("SYNCPLAY_TEST_PASSWORD");
        SyncplayCodec codec = new SyncplayCodec();
        ObjectMapper json = new ObjectMapper();
        String room = "syncnuke-test-" + System.nanoTime();
        AtomicReference<PlayerState> status = new AtomicReference<>(new PlayerState());
        MediaPlayer player = mock(MediaPlayer.class);
        when(player.getStatus()).thenAnswer(invocation -> {
            synchronized (status) {
                return PlayerStateUtils.advance(status.get(), System.currentTimeMillis());
            }
        });
        doAnswer(invocation -> { update(status, state -> state.setPosition(invocation.getArgument(0))); return null; })
                .when(player).seek(anyDouble());
        doAnswer(invocation -> { update(status, state -> state.setPlaybackState(PlaybackState.PLAYING)); return null; })
                .when(player).play();
        doAnswer(invocation -> { update(status, state -> state.setPlaybackState(PlaybackState.PAUSED)); return null; })
                .when(player).pause();
        doAnswer(invocation -> { update(status, state -> state.setPlaybackSpeed(invocation.getArgument(0))); return null; })
                .when(player).setPlaybackSpeed(anyDouble());
        status.get().setLastUpdateTime(System.currentTimeMillis());
        try (PlayerManager manager = PlayerManager.create(player, 0);
             SyncplayClient client = new SyncplayClient("127.0.0.1", port, manager);
             Socket peer = new Socket("127.0.0.1", port)) {
            peer.setSoTimeout(5000);
            JsonNode hello = json.readTree("{\"Hello\":{\"username\":\"peer\",\"room\":{\"name\":\""
                    + room + "\"},\"version\":\"1.7.7\",\"features\":{\"persistentRooms\":false}}}");
            if (password != null) {
                ((ObjectNode) hello.get("Hello")).put("password",
                        java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5")
                                .digest(password.getBytes(StandardCharsets.UTF_8))));
            }
            peer.getOutputStream().write(codec.encode(SyncplayCodec.fromJson(hello)));
            client.login("java-client", room, password);
            boolean joined = false;
            boolean javaSeekSeen = false;
            List<JsonNode> received = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!javaSeekSeen && System.nanoTime() < deadline) {
                JsonNode message = SyncplayCodec.toJson(codec.decode(peer.getInputStream()));
                received.add(message);
                assertFalse(message.has("Error"), message.toString());
                if (message.path("Set").path("user").has("java-client")) {
                    joined = true;
                }
                if (!message.has("State")) {
                    continue;
                }
                JsonNode state = message.get("State");
                if (joined) {
                    JsonNode change = json.readTree("{\"State\":{\"playstate\":{\"position\":42,\"paused\":true,\"doSeek\":true}}}");
                    peer.getOutputStream().write(codec.encode(SyncplayCodec.fromJson(change)));
                    waitUntil(() -> Math.abs(status.get().getPosition() - 42) < 0.5);
                    update(status, local -> local.setPosition(84));
                    client.onStatusChange(status.get());
                    joined = false;
                }
                acknowledge(peer, codec, json, state);
                javaSeekSeen = "java-client".equals(state.path("playstate").path("setBy").asText())
                        && Math.abs(state.path("playstate").path("position").asDouble() - 84) < 0.5;
            }
            assertTrue(javaSeekSeen, "Reference server did not receive Java seek: " + received);
            peer.getOutputStream().write(codec.encode(SyncplayCodec.fromJson(json.readTree(
                    "{\"State\":{\"playstate\":{\"position\":84,\"paused\":false}}}"))));
            waitUntil(() -> status.get().getPlaybackState() == PlaybackState.PLAYING);
            update(status, local -> local.setPlaybackState(PlaybackState.PAUSED));
            client.onStatusChange(status.get());
            boolean javaPauseSeen = false;
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!javaPauseSeen && System.nanoTime() < deadline) {
                JsonNode message = SyncplayCodec.toJson(codec.decode(peer.getInputStream()));
                assertFalse(message.has("Error"), message.toString());
                if (message.has("State")) {
                    JsonNode state = message.get("State");
                    acknowledge(peer, codec, json, state);
                    javaPauseSeen = "java-client".equals(state.path("playstate").path("setBy").asText())
                            && state.path("playstate").path("paused").asBoolean();
                }
            }
            assertTrue(javaPauseSeen, "Reference server did not receive Java pause");
        }
    }

    private static void acknowledge(Socket peer, SyncplayCodec codec, ObjectMapper json, JsonNode state) throws IOException {
        ObjectNode reply = json.createObjectNode();
        ObjectNode response = reply.putObject("State");
        response.set("ping", state.path("ping"));
        if (state.path("ignoringOnTheFly").has("server")) {
            response.set("ignoringOnTheFly", state.get("ignoringOnTheFly"));
        }
        peer.getOutputStream().write(codec.encode(SyncplayCodec.fromJson(reply)));
    }

    private static void update(AtomicReference<PlayerState> status, java.util.function.Consumer<PlayerState> action) {
        synchronized (status) {
            PlayerState next = PlayerStateUtils.advance(status.get(), System.currentTimeMillis());
            next.setLastUpdateTime(System.currentTimeMillis());
            action.accept(next);
            status.set(next);
        }
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "Java player did not receive peer state");
    }
}

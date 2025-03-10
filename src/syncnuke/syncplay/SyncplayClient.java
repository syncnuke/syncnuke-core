package syncnuke.syncplay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import syncnuke.syncplay.commands.BaseCommand;
import syncnuke.syncplay.data.*;
import syncnuke.syncplay.state.PlaybackState;
import syncnuke.tcp.TcpClient;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class SyncplayClient extends TcpClient {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 8999;

    private final ScheduledExecutorService scheduler;
    private final BaseCommand command = new BaseCommand(this);
    private final PlaybackState state;

    private boolean loggedIn = false;
    private String username;
    private String room;

    public SyncplayClient() {
        super(SERVER_HOST, SERVER_PORT);
        scheduler = Executors.newScheduledThreadPool(1);
        state = new PlaybackState(null, 0, true, false);
    }

    private void logout() {
        if (loggedIn) {
            scheduler.shutdown();
        }
    }

    public void login(String username, String room) {
        logout();
        this.username = username;
        this.room = room;
        command.execute(new HelloData(username, room));
        loggedIn = true;
        startStateUpdates();
    }

    @Override
    protected void handleResponse(String line) {
        log.debug("Server response: {}", line);
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonNode = mapper.readTree(line);

            if (jsonNode.has("State")) {
                StateData stateData = mapper.treeToValue(jsonNode.get("State"), StateData.class);
                handleStateUpdate(stateData);
            } else if (jsonNode.has("Set")) {
                SetData setData = mapper.treeToValue(jsonNode.get("Set"), SetData.class);
                handleSetUpdate(setData);
            }
        } catch (Exception e) {
            log.error("Failed to parse server response: {}", e.getMessage());
        }
    }

    /**
     * Processes a 'State' command from the server.
     */
    private void handleStateUpdate(StateData stateData) {
        state.updateState(
                stateData.getPlaystate().getPosition(),
                stateData.getPlaystate().isPaused(),
                stateData.getPlaystate().isDoSeek()
        );

        if (stateData.getFile() != null) {
            state.updateFile(stateData.getFile());
            log.info("New file loaded: {}", stateData.getFile().getName());
        }

        log.debug("State updated - Position: {}, Paused: {}", state.getPosition(), state.isPaused());
    }

    /**
     * Processes a 'Set' command from the server.
     */
    private void handleSetUpdate(SetData setData) {
        log.debug("Server set data: {}", setData);
        FileData file = getFile(setData);
        if (file != null) {
            state.updateFile(file);
            acknowledgeFile();
            log.info("File set by server: {}", file.getName());
        }
    }

    private FileData getFile(SetData setData) {
        if (setData.getUsers() == null || setData.getUsers().isEmpty()) {
            // No user information available
            return null;
        }
        FileData file = null;

        for (String user : setData.getUsers().keySet()) {
            if (user.equals(username)) {
                // Ignore 'Set' updates sent by this client
                continue;
            }
            UserData userData = setData.getUsers().get(user);
            if (userData != null && userData.getFile() != null) {
                // A user has sent a 'Set' command with file metadata
                file = userData.getFile();
                break;
            }
        }
        return file;
    }

    private void acknowledgeFile() {
        if (state.hasFile()) {
            SetData setData = new SetData();
            setData.setFile(state.getCurrentFile());
            command.execute(setData);
            log.info("Acknowledged file: {}", setData.getFile());
        }
    }

    private void keepAlive() {
        // TODO: Find a way to stay in sync with the server without being kicked (probably requires keeping track of timers)
        StateData stateData = new StateData(
                state.getPosition(),
                state.isPaused(),
                state.isDoSeek(),
                username,
                state.getCurrentFile()
        );
        command.execute(stateData);
    }

    // Send state updates every 5 seconds
    private void startStateUpdates() {
        scheduler.scheduleAtFixedRate(this::keepAlive, 0, 5, TimeUnit.SECONDS);
    }

}

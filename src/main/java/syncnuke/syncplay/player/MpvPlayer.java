package syncnuke.syncplay.player;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class MpvPlayer implements VideoPlayer {

    private final String mpvSocketPath;
    private VideoPlayerEventListener eventListener;
    private final ExecutorService listenerExecutor = Executors.newSingleThreadExecutor();

    public MpvPlayer(String mpvSocketPath) {
        this.mpvSocketPath = mpvSocketPath;
        startListening();
    }

    @Override
    public void setEventListener(VideoPlayerEventListener eventListener) {
        this.eventListener = eventListener;
    }

    @Override
    public void play() {
        sendCommand("{\"command\": [\"set_property\", \"pause\", false]}");
    }

    @Override
    public void pause() {
        sendCommand("{\"command\": [\"set_property\", \"pause\", true]}");
    }

    @Override
    public void seek(double position) {
        sendCommand("{\"command\": [\"set_property\", \"time-pos\", " + position + "]}");
    }

    @Override
    public double getPosition() {
        String response = sendCommand("{\"command\": [\"get_property\", \"time-pos\"]}");
        return parseDoubleResponse(response);
    }

    @Override
    public boolean isPaused() {
        String response = sendCommand("{\"command\": [\"get_property\", \"pause\"]}");
        return Boolean.parseBoolean(response);
    }

    private String sendCommand(String command) {
        try {
            Process process = new ProcessBuilder("echo", command, "|", "socat", "-", "UNIX-CONNECT:" + mpvSocketPath)
                    .redirectErrorStream(true)
                    .start();
            process.waitFor();
            return new String(process.getInputStream().readAllBytes());
        } catch (IOException | InterruptedException e) {
            log.error("Failed to send command to mpv: {}", e.getMessage());
            return null;
        }
    }

    private double parseDoubleResponse(String response) {
        try {
            return Double.parseDouble(response.trim());
        } catch (NumberFormatException e) {
            log.error("Failed to parse response as double: {}", response);
            return 0.0;
        }
    }

    private void startListening() {
        listenerExecutor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new ProcessBuilder("socat", "UNIX-CONNECT:" + mpvSocketPath, "-")
                            .redirectErrorStream(true)
                            .start()
                            .getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    handleIncomingCommand(line);
                }
            } catch (IOException e) {
                log.error("Failed to listen to mpv socket: {}", e.getMessage());
            }
        });
    }

    private void handleIncomingCommand(String line) {
        log.debug("Received command from mpv: {}", line);
        if (eventListener == null) {
            return;
        }

        try {
            // Parse the incoming JSON command
            if (line.contains("\"event\":\"pause\"")) {
                boolean isPaused = line.contains("\"pause\":true");
                if (isPaused) {
                    eventListener.onPause();
                } else {
                    eventListener.onPlay();
                }
            } else if (line.contains("\"time-pos\"")) {
                double position = parseDoubleResponse(line);
                eventListener.onSeek(position);
            }
        } catch (Exception e) {
            log.error("Failed to handle incoming command: {}", e.getMessage());
        }
    }
}

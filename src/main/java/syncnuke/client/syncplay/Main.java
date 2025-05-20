package syncnuke.client.syncplay;

import lombok.Data;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import syncnuke.player.MpvPlayer;
import syncnuke.player.VideoPlayer;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

import static org.slf4j.LoggerFactory.getLogger;

public class Main {

    private static final Logger logger = getLogger(Main.class);

    @Data
    private static class ServerConfig {
        private String host = "localhost";
        private int port = 8999;
    }

    public static void main(String[] args) {
        ServerConfig config = getServerConfig(args);

        CountDownLatch latch = new CountDownLatch(1);
        String socketPath = System.getProperty("user.home") + "/.mpv-ipc/mpvsocket";

        try (VideoPlayer videoPlayer = new MpvPlayer(socketPath);
             SyncplayClient client = new SyncplayClient(config.getHost(), config.getPort(), videoPlayer)) {

            client.login("user", "room");
            videoPlayer.load(args[0]);

            // Wait for client to close before terminating
            Runtime.getRuntime().addShutdownHook(new Thread(client::close));
            latch.await();
        } catch (IOException exception) {
            logger.error("Error initializing MPV player: {}", exception.getMessage());
        } catch (Exception e) {
            logger.error("An unexpected error occurred: {}", e.getMessage());
        } finally {
            latch.countDown();
        }
    }

    private static ServerConfig getServerConfig(String[] args) {
        CommandLine cmd;
        Options options = getOptions();
        CommandLineParser parser = new DefaultParser();

        ServerConfig config = new ServerConfig();
        try {
            cmd = parser.parse(options, args);
            if (cmd.hasOption("host")) {
                config.setHost(cmd.getOptionValue("host"));
            }
            if (cmd.hasOption("port")) {
                config.setPort(Integer.parseInt(cmd.getOptionValue("port")));
            }
        } catch (ParseException e) {
            logger.error("Failed to parse command line arguments: {}", e.getMessage());
            System.exit(1);
        }
        return config;
    }

    private static Options getOptions() {
        Options options = new Options();
        options.addOption(Option.builder()
                .longOpt("host")
                .hasArg()
                .desc("Server host (default: localhost)")
                .build());

        options.addOption(Option.builder()
                .longOpt("port")
                .hasArg()
                .desc("Server port (default: 8999)")
                .type(Number.class)
                .build());
        return options;
    }

}

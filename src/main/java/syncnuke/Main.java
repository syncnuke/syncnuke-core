package syncnuke;

import lombok.Data;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import syncnuke.client.SyncClient;
import syncnuke.client.SyncClientFactory;
import syncnuke.player.MpvPlayer;
import syncnuke.player.VideoPlayer;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

import static org.slf4j.LoggerFactory.getLogger;

public class Main {

    private static final Logger logger = getLogger(Main.class);

    @Data
    private static class Environment {
        private String host = "localhost";
        private int port = 8999;
        private String filePath;
        private String protocol = "datasaver";
    }

    public static void main(String[] args) {
        Environment env = parseArguments(args);
        CountDownLatch latch = new CountDownLatch(1);

        try (VideoPlayer videoPlayer = getVideoPlayer();
             SyncClient client = getSyncClient(env, videoPlayer)) {

            client.login("user", "room");
            videoPlayer.load(env.getFilePath());

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

    private static SyncClient getSyncClient(Environment env, VideoPlayer videoPlayer) {
        return SyncClientFactory.createClient(
                env.getProtocol(),
                env.getHost(),
                env.getPort(),
                videoPlayer
        );
    }

    private static VideoPlayer getVideoPlayer() throws IOException {
        String socketPath = System.getProperty("user.home") + "/.mpv-ipc/mpvsocket";
        return new MpvPlayer(socketPath);
    }

    private static Environment parseArguments(String[] args) {
        CommandLine cmd;
        Options options = getOptions();
        CommandLineParser parser = new DefaultParser();

        Environment config = new Environment();
        try {
            cmd = parser.parse(options, args);
            if (cmd.hasOption("host")) {
                config.setHost(cmd.getOptionValue("host"));
            }
            if (cmd.hasOption("port")) {
                config.setPort(Integer.parseInt(cmd.getOptionValue("port")));
            }
            if (cmd.hasOption("protocol")) {
                config.setProtocol(cmd.getOptionValue("protocol"));
            }
            config.setFilePath(cmd.getOptionValue("file"));
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
        options.addOption(Option.builder()
                .longOpt("file")
                .hasArg()
                .desc("File path for the media to load")
                .build());
        options.addOption(Option.builder()
                .longOpt("protocol")
                .hasArg()
                .desc("Protocol to use (default: datasaver)")
                .build());
        return options;
    }

}

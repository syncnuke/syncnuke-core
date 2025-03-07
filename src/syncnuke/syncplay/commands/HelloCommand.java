package syncnuke.syncplay.commands;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import syncnuke.syncplay.SyncplayClient;
import syncnuke.syncplay.data.HelloData;

@Slf4j
@AllArgsConstructor
public class HelloCommand {
    private SyncplayClient client;

    public void execute(String username, String room) {
        HelloData data = new HelloData(username, room);
        // send data serialized using jackson
        try {
            log.atInfo().log("Sending HelloData: {}", data.serialize());
            client.send(data.serialize());
        } catch (JsonProcessingException e) {
            log.atError().log("Failed to serialize HelloData: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

}

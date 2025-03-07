package syncnuke.syncplay.commands;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import syncnuke.syncplay.SyncplayClient;
import syncnuke.syncplay.data.BaseData;

@Slf4j
@AllArgsConstructor
public class BaseCommand {
    private SyncplayClient client;

    public void execute(BaseData data) {
        try {
            log.info("Sending data: {}", data.serialize());
            client.send(data.serialize());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize data: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

}

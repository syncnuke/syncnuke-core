package syncnuke.syncplay;

import syncnuke.syncplay.commands.BaseCommand;
import syncnuke.syncplay.data.HelloData;

public class Main {

    public static void main(String[] args) {
        SyncplayClient client = new SyncplayClient();
        HelloData data = new HelloData("user", "room");
        BaseCommand command = new BaseCommand(client);
        command.execute(data);
    }

}

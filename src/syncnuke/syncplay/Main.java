package syncnuke.syncplay;

import syncnuke.syncplay.commands.HelloCommand;

public class Main {

    public static void main(String[] args) {
        SyncplayClient client = new SyncplayClient();
        HelloCommand helloCommand = new HelloCommand(client);
        helloCommand.execute("user", "room");
    }

}

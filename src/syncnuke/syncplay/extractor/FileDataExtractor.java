package syncnuke.syncplay.extractor;

import syncnuke.syncplay.data.FileData;
import syncnuke.syncplay.data.SetData;
import syncnuke.syncplay.data.UserData;

public class FileDataExtractor implements Extractor<SetData, FileData> {

    private final String username;

    public FileDataExtractor(String username) {
        this.username = username;
    }

    @Override
    public FileData extract(SetData source) {
        if (source.getUsers() == null || source.getUsers().isEmpty()) {
            // No user information available
            return null;
        }
        FileData file = null;

        for (String user : source.getUsers().keySet()) {
            if (user.equals(username)) {
                // Ignore 'Set' updates sent by this client
                continue;
            }
            UserData userData = source.getUsers().get(user);
            if (userData != null && userData.getFile() != null) {
                // A user has sent a 'Set' command with file metadata
                file = userData.getFile();
                break;
            }
        }
        return file;
    }

}

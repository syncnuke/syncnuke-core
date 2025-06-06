package io.github.syncnuke.client.internal.syncplay.service.extractor;

import pl.syncplay.proto.SyncplayProto.FileInfo;
import pl.syncplay.proto.SyncplayProto.SetCommand;

public class FileDataExtractor implements Extractor<SetCommand, FileInfo> {

    private final String username;

    public FileDataExtractor(String username) {
        this.username = username;
    }

    @Override
    public FileInfo extract(SetCommand source) {
        if (!source.hasUser()) {
            // No user information available
            return null;
        }
        FileInfo file = null;

        String user = source.getUser().getUsername();

        if (!user.equals(username)) {
            if (source.getUser().hasFile()) {
                // A user has sent a 'Set' command with file metadata
                file = source.getUser().getFile();
            }
        }

        return file;
    }

}

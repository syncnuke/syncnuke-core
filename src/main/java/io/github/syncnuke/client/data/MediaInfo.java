package io.github.syncnuke.client.data;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MediaInfo {
    String fileName;
    double duration;
}

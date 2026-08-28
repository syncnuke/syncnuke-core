package io.github.syncnuke.player;

/**
 * Indicates that an operation requires a video but none is currently loaded.
 */
public final class NoVideoLoadedException extends IllegalStateException {

    public NoVideoLoadedException() {
        super("No video is currently loaded.");
    }
}

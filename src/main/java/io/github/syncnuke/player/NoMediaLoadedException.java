package io.github.syncnuke.player;

/**
 * Indicates that an operation requires a media but none is currently loaded.
 */
public final class NoMediaLoadedException extends IllegalStateException {

    public NoMediaLoadedException() {
        super("No media is currently loaded.");
    }
}

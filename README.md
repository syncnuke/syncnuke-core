# SyncNuke Core

SyncNuke Core is the canonical Java implementation of [SyncNuke](https://syncnuke.com/) standard's library. It provides the common abstractions and contracts used by the SyncNuke ecosystem and separates synchronization logic, networking, and player integration so that each can be implemented independently, using the following model:

* **Sync Client abstraction** — a protocol-independent structure for synchronization clients. Different synchronization protocols can implement the same client contract and remain compatible with the rest of the SyncNuke ecosystem.
* **Media Player interface** — a common contract for reporting playback state and controlling a media player. Player-specific integrations implement this interface outside of SyncNuke Core.
* **Networking abstraction** — a transport-independent networking layer that keeps connection and communication details separate from synchronization logic.

These abstractions are intended to make both synchronization protocols and media players interchangeable without coupling them to one another.

For more information, see the [specification](https://syncnuke.com/spec/).

## Requirements

* JDK 21

## Using the Library

SyncNuke Core is published under the `io.github.syncnuke` group.

Add the following dependency to your Maven project's `pom.xml`:

```xml
<dependency>
    <groupId>io.github.syncnuke</groupId>
    <artifactId>syncnuke-core</artifactId>
    <version>0.4.0</version>
</dependency>
```

Or the following dependency to your Gradle project's `build.gradle`:

```gradle
dependencies {
    implementation 'io.github.syncnuke:syncnuke-core:0.4.0'
}
```

### Basic usage

Applications provide an implementation of [`MediaPlayer`](https://syncnuke.com/spec/media-player/#integration-requirements). SyncNuke Core uses that player through `SyncManager`.

```java
import io.github.syncnuke.client.SyncManager;
import io.github.syncnuke.player.MediaPlayer;

void main() {
    // Initialize with your MediaPlayer implementation
    MediaPlayer player;

    player.load("/path/to/video.mkv");

    try (SyncManager syncManager = SyncManager.getInstance(player)) {
        syncManager.start(
                "datasaver",
                "master.syncnuke.com",
                65344,
                "alice",
                "movie-night",
                null
        );

        // Run your application
    }
}
```

The `SyncManager` should remain open for the lifetime of the synchronized session. See [Lifecycle](https://syncnuke.com/spec/coordination/#lifecycle) for resource management.

This port (`65344`) is provisional and will change before the first stable release.

## Player implementations

See the [SyncNuke download page](https://syncnuke.com/download/) for current Desktop and Android player integrations, including downloads, setup instructions, and source links. To build your own integration, follow the [Media player requirements](https://syncnuke.com/spec/media-player/#integration-requirements).

## Supported Protocols

See [Protocols](https://syncnuke.com/spec/protocols/#reference-behavior) in the specification for current and planned protocol support, and the [DataSaver documentation](https://syncnuke.com/spec/data-saver/) for its behavior and message format.

## Roadmap

Check the [SyncNuke roadmap](https://syncnuke.com/roadmap/) for planned changes.

## Documentation

The [SyncNuke wiki](https://syncnuke.com/spec/) covers the standard, component contracts, and implementation behavior. See [Media player](https://syncnuke.com/spec/media-player/) for player integration requirements, [Sync client](https://syncnuke.com/spec/sync-client/) for synchronization client responsibilities, [Networking](https://syncnuke.com/spec/networking/) for transport and master-server details, and [Coordination](https://syncnuke.com/spec/coordination/) for how the components work together.

## License

SyncNuke Core is licensed under the GNU Lesser General Public License v3.0 only. See the [LICENSE](LICENSE.md) file for the full license text.

You may use SyncNuke Core in open-source or proprietary software. Modified versions of SyncNuke Core must remain licensed under LGPL-3.0-only, include the original license notices, identify the modifications, and have their corresponding source made available as required by the license.

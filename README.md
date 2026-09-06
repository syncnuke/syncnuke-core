# SyncNuke Core

SyncNuke is a standard for synchronizing video playback over the internet. It defines common expectations for synchronization clients and video players, allowing implementations to remain compatible without depending on a specific player or synchronization protocol.

SyncNuke Core is the canonical Java implementation of the SyncNuke library contracts. It contains the common abstractions and contracts used by the SyncNuke ecosystem and separates synchronization logic, networking, and player integration so that each can be implemented independently, using the following model:

* **Sync Client abstraction** — a protocol-independent structure for synchronization clients. Different synchronization protocols can implement the same client contract and remain compatible with the rest of the SyncNuke ecosystem.
* **Video Player interface** — a common contract for reporting playback state and controlling a video player. Player-specific integrations implement this interface outside of SyncNuke Core.
* **Networking abstraction** — a transport-independent networking layer that keeps connection and communication details separate from synchronization logic.

These abstractions are intended to make both synchronization protocols and video players interchangeable without coupling them to one another.

## Requirements

* JDK 21

## Using the Library

SyncNuke Core is published under the `io.github.syncnuke` group.

Add the following dependency to your Maven project's `pom.xml`:

```xml
<dependency>
    <groupId>io.github.syncnuke</groupId>
    <artifactId>syncnuke-core</artifactId>
    <version>0.3.0</version>
</dependency>
```

Or the following dependency to your Gradle project's `build.gradle`:

```gradle
dependencies {
    implementation 'io.github.syncnuke:syncnuke-core:0.3.0'
}
```

### Basic usage

Applications provide an implementation of `VideoPlayer`. SyncNuke Core uses that player through `SyncManager`, independently of the player implementation itself.

```java
import io.github.syncnuke.client.SyncManager;
import io.github.syncnuke.player.VideoPlayer;

void main() {
    // Initialize with your VideoPlayer implementation
    VideoPlayer player;

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

The `SyncManager` should remain open for the lifetime of the synchronized session.

## Supported Protocols

### DataSaver

**DataSaver** is SyncNuke's dedicated synchronization protocol.

It uses connection-oriented communication to provide reliable state synchronization while minimizing the number of packets and amount of data exchanged. Its primary goals are synchronization reliability and low bandwidth usage.

### SyncPlay

SyncPlay support is planned for SyncNuke Core. See the [roadmap](#roadmap) for more information.

## Player implementations

Player integrations live in applications that depend on this library and implement the SyncNuke video player contract.

Current and planned projects include:

* **[SyncNuke Desktop](https://github.com/if-shouldrs/syncnuke-desktop)** — connects SyncNuke to video players running on PC.
* **[SyncNuke Android](https://github.com/if-shouldrs/syncnuke-android)** — work-in-progress Just Player fork with SyncNuke support.
* **Browser extension player integration** — planned support for synchronizing browser-based video playback (not created).

Note: All SyncNuke connections are established through SyncNuke Master Server, located at `master.syncnuke.com:65344`.

This port (`65344`) is provisional and will change before the first stable release.

## Roadmap

* Implement SyncPlay protocol support.
* Support cross-protocol server synchronization.
* Create a browser extension player integration.

## Documentation

Detailed documentation for the SyncNuke standard, its interfaces, and the DataSaver protocol will be maintained separately in the SyncNuke documentation, to be published soon.

## License

SyncNuke Core is licensed under the GNU Lesser General Public License v3.0 only. See the [LICENSE](LICENSE.md) file for the full license text.

You may use SyncNuke Core in open-source or proprietary software. Modified versions of SyncNuke Core must remain licensed under LGPL-3.0-only, include the original license notices, identify the modifications, and have their corresponding source made available as required by the license.

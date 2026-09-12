# Plexora

Plexora is a modern, lightweight Plex music client for **Android Automotive OS** and **Android mobile**.

## Product flavors

| Flavor | Target | UI |
|--------|--------|-----|
| **automotive** | Play Store / AAOS head units | System media browser and player (Media3 `MediaLibraryService`) |
| **mobile** | Phones and tablets | Full Jetpack Compose app with launcher, browse, and immersive player |

On Ford, GM, and other AAOS vehicles, the **automotive** build appears in the vehicle's native media app — not as a standalone Compose launcher. Browsing, artwork, shuffle, and playback are driven by `PlexMediaLibraryService` and the host system's UI.

The **mobile** flavor includes `MainActivity` with the custom Compose interface shown in the screenshots below.

## Features

- **System media integration**: Media3 implementation for AAOS media center, steering wheel controls, and instrument cluster metadata.
- **Expanded browse tree**: Artists, Albums, Recently Added, Playlists, plus contextual shuffle at each level.
- **Artwork via ContentProvider**: Plex images are proxied as local `content://` URIs for AAOS compatibility.
- **Contextual shuffle**: Library, artist, album, playlist, and section shuffles via Plex play queues.
- **Search**: Artists, albums, and tracks via the system media search UI.
- **Plex linking**: PIN auth at `plex.tv/link` through Sign In (automotive) or in-app setup (mobile).

## Screenshots (mobile flavor)

1. **Server Setup**  
   ![Setup](screenshots/setup.png)  
   *Linking a Plex server using the secure PIN-based authentication flow.*

2. **Music Library**  
   ![Library](screenshots/library.png)  
   *Browsing the artist library in the mobile Compose UI.*

3. **Playlist & Shuffle**  
   ![Playlist](screenshots/playlist.png)  
   *Playlist track list with contextual shuffle.*

4. **Now Playing**  
   ![Player](screenshots/player.png)  
   *Immersive playback screen with large controls and blurred background artwork.*

## Build

```bash
# Automotive (Play Store / AAOS)
./gradlew bundleAutomotiveRelease

# Mobile
./gradlew bundleMobileRelease
```

Version codes: mobile uses `appVersionCode`; automotive adds `1_000_000` (e.g. 8 → 1000008).

### Signed release (Android Studio)

1. Select the **automotiveRelease** or **mobileRelease** build variant.
2. **Build → Generate Signed App Bundle / APK** and sign with your release keystore (Play App Signing upload key for Play uploads).
3. Upload the `.aab` to Play Console (automotive track) or sideload the APK for local testing.

## Play Store (automotive)

Plexora requires a linked Plex account with a music library. Reviewers and new users see a **Sign in to Plex** prompt in the vehicle media browser until setup is complete.

Add the following under **Play Console → App content → App access** (or release notes):

> Plexora is a Plex music client. A Plex account and music library are required.
> 1. Open Plexora from the vehicle media app.
> 2. Tap **Sign in to Plex** when prompted.
> 3. On another device, visit https://plex.tv/link and enter the PIN shown on screen.
> 4. Select a Plex server that has a music library.
> 5. Return to the media browser — Artists, Albums, Playlists, and playback should work.

Playback from an album or playlist queues remaining tracks in order (or shuffled via the Shuffle items). Large libraries are loaded in pages so artists and albums beyond the first screen remain browsable.

## Technical stack

- **UI (mobile)**: Jetpack Compose
- **Media**: Media3 / ExoPlayer
- **Network**: OkHttp 4 & Plex API
- **Images**: Coil 2 (mobile), `ArtworkProvider` ContentProvider (automotive)
- **Persistence**: SharedPreferences for session and playback state recovery

## Getting started

1. Clone the repository.
2. Open the project in Android Studio.
3. Select the **mobile** or **automotive** build variant.
4. Deploy to a device or AAOS emulator.
5. Link your Plex account (Sign In on automotive; in-app setup on mobile).

## License

This project is licensed under the MIT License.

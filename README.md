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

Version codes: mobile uses `appVersionCode`; automotive adds `1_000_000`.

| Flavor | `versionCode` | `versionName` | `targetSdk` |
|--------|---------------|---------------|-------------|
| **mobile** | 10 | 1.0 | 36 (Play phone/tablet updates) |
| **automotive** | 1000010 | 1.0 | 35 (AAOS) |

### Google Play target API levels

Phone and tablet builds (**mobile**) must target **API 36+** for Play updates. **automotive** stays on **API 35**, which matches Android Automotive OS requirements. Both flavors share `compileSdk = 37` in Gradle; only `targetSdk` differs by flavor.

### Signed release (Android Studio)

1. Sync Gradle after any version bump (`appVersionCode` in `app/build.gradle.kts`).
2. Select the **mobileRelease** or **automotiveRelease** build variant.
3. **Build → Generate Signed App Bundle / APK** → **Android App Bundle**, sign with your Play upload keystore.
4. Upload the `.aab` to the matching Play Console app / track (phone vs automotive are separate listings if you use both).

Unsigned CI-style bundles (debug-signed) land under:

- `app/build/outputs/bundle/mobileRelease/app-mobile-release.aab`
- `app/build/outputs/bundle/automotiveRelease/app-automotive-release.aab`

Release builds use R8 minify and resource shrinking; run `./gradlew bundleMobileRelease bundleAutomotiveRelease` locally to catch ProGuard issues before signing in Studio.

## Play Store (automotive)

Plexora requires a linked Plex account with a music library. Reviewers and new users see a **Sign in to Plex** prompt in the vehicle media browser until setup is complete.

Add the following under **Play Console → App content → App access** (and in **Android Auto** / store testing notes):

> Plexora is a Plex **music** client. Review and testing require a Plex account with at least one **Music** library on an online Plex Media Server (movies-only or empty servers will not work).
>
> **Phone (launcher app):**
> 1. Open Plexora → **Link with Plex.tv** → complete PIN at https://plex.tv/link
> 2. Choose a Plex server → **Select Music Section** → **Connect and Import**
> 3. Browse Artists / Albums / Playlists and play a track
>
> **Android Auto (projected):**
> 1. Complete phone setup above first (music library must be selected).
> 2. Connect the phone to Android Auto / DHU, open Plexora in the media app list.
> 3. Browse and play from Artists or Playlists; use Shuffle items where shown.
>
> If setup fails with “No music library”, add a Music library in Plex Media Server or choose a different server.

Playback from an album or playlist queues remaining tracks in order (or shuffled via the Shuffle items). Large libraries are loaded in pages so artists and albums beyond the first screen remain browsable.

## Release notes (version 10)

Copy into Play Console for **mobileRelease** (version code **10**) and **automotiveRelease** (version code **1000010**).

### Mobile (version code 10)

- Android Auto: register setup activity on phone builds; clearer “no music library” setup errors
- Library browse cache (stale-while-revalidate) for faster tab switching
- Browse polish: section titles in the top bar, square grid tiles, compact landscape chrome
- Full-screen now playing and Up next queue with slide-to-dismiss (modal sheets)
- Square artwork on mini player and full player
- Dependency refresh (Gradle version catalog, Media3 1.11, Compose BOM 2026.09)
- Fix playback after Media3 upgrade (session commands, stream URI resolution)

### Automotive (version code 1000010)

- Same shared playback and Plex client fixes as mobile (Media3 session connect, play queues, stream URIs)
- Dependency updates aligned with mobile; AAOS browse UI unchanged (system media browser)

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

# Prism

**A fast, clean IPTV player for Android TV and Google TV, built as a modern alternative to STB Emu.**

Prism connects to the same **Stalker / Ministra portals** that MAG boxes and STB Emu use (portal URL
plus MAC address). It also supports **Xtream Codes** logins and plain **M3U** playlists. It's built
around the TV remote: no mouse-style menus, no ads, and everything opens fast.

> Prism is only a player. It doesn't include or sell any channels or content. Use it with a service
> you subscribe to, or one that is legally free.

---

## Features

### Home
- **Continue watching:** movies and episodes you haven't finished, with progress bars. OK resumes right away.
- **Favorites:** movies and shows you've saved, plus your favorite channels.
- An info area at the top describes whichever card the remote is on: artwork, episode and time left.
- Built only from what's stored on the TV, so it opens instantly without contacting your provider.

### Live TV
- Channels **grouped by language** (English, Hindi, Telugu…) and theme (Sports…). Open a language to
  see its categories, or pick "All English" to see every channel in it at once.
- **List or card view**, plus search across all channels.
- **Live preview**: pause on a channel and it starts playing beside the list. OK goes fullscreen instantly, with no re-buffering.
- **Now / next guide** with progress bars (Stalker and Xtream).
- Fullscreen: ▲▼ or CH+/CH− change channels, number keys jump to a channel, OK shows the info bar.
- Favorites (hold OK on a channel). Prism reopens on the last channel you watched.

### Video Club (movies and shows)
- Categories grouped by language, a poster grid or list view, and search.
- **Date added** on every title, plus a **NEW** badge on anything added in the last 7 days.
- Detail pages with description, year, rating and length. For shows: **seasons and episodes**, with a
  **Continue S4 · E12** button that remembers where you are in each show.
- Movie player: pause, skip back 10 s or forward 30 s (hold to go faster), next/previous episode, and
  **resume where you left off**.

### Under the hood
- One shared video player for previews and fullscreen, so switching never re-buffers.
- Fast channel start (1 s buffer), an automatic HLS → MPEG-TS fallback, and automatic recovery from network hiccups.
- The channel list is cached on the TV and refreshed at most every 6 hours, which keeps providers from
  rate-limiting you (HTTP 429). Press ⟳ to refresh sooner.
- About 2.5 MB, black theme, light enough for TVs with 1 GB of RAM.

---

## Install on your TV

Prism isn't on the Play Store. You install ("sideload") the APK file yourself. Pick **one** of the
two methods below.

### Method 1: Downloader app (no computer needed)

1. On the TV, install **Downloader** by AFTVnews from the Google Play Store.
2. Allow it to install apps: **Settings → Apps → Security & restrictions → Unknown sources → turn on
   Downloader**. On some TVs this is under **Settings → Device Preferences → Security & restrictions**.
3. Open Downloader and enter the link to the latest APK from this repository's
   [**Releases**](../../releases/latest) page (`prism-<version>.apk`).
4. When the download finishes, choose **Install**, then **Open**.

### Method 2: ADB from a computer

1. On the TV: **Settings → Device Preferences → About** (or **System → About**), then press OK on
   **Build** 7 times to unlock Developer options.
2. Go back to **Developer options** and turn on **USB debugging**. Also turn on **Network debugging**
   or **ADB debugging** if your TV has it.
3. Find the TV's IP address under **Settings → Network & Internet**, then on your computer (with
   [platform-tools](https://developer.android.com/tools/releases/platform-tools) installed):

   ```bash
   adb connect <tv-ip>:5555
   adb install -r prism-<version>.apk
   ```

4. Accept the **Allow USB debugging?** prompt on the TV the first time.
5. When you're done, turn **USB debugging off** again. An open debugging port lets anyone on your
   network control the TV.

Prism appears in the TV's app row. To update, install a newer APK over the old one; your sources,
favorites and watch history are kept.

---

## First-time setup

Open Prism and choose how your provider connects:

| Type | What you need |
|---|---|
| **Portal (MAC)** | Portal URL, e.g. `http://example.com/c/` or `http://example.com:8080`, and a MAC address |
| **Xtream login** | Server address, username and password |
| **M3U playlist** | A link to an `.m3u` / `.m3u8` playlist |

### Portal (MAC) tips: matching an existing box or STB Emu

Most portals only accept a device they already have registered. If you're moving from a MAG box or
STB Emu, copy its settings exactly. In STB Emu they're under **Settings → Profiles → (your profile) →
STB configuration**:

- **MAC address:** type the 12 characters; Prism adds the colons.
- **Serial number:** copy it from STB Emu. Leave it blank only if your portal doesn't check it.
- **STB model:** Prism defaults to **MAG424**. If STB Emu shows a different model, pick the same one.
- **Device ID:** leave blank unless STB Emu has *Send Device ID* turned on.

If the portal still says *"Please contact your provider to register this device"*, one of these
values doesn't match what the provider has on file.

Hold OK on a saved source (on the Sources screen) to edit it later.

---

## Remote controls

| Where | Button | Action |
|---|---|---|
| Anywhere | Back | Go back; Live TV / Video Club → Home; press twice on Home to exit |
| Tabs | OK | Switch between Home, Live TV and Video Club |
| Lists and grids | OK / hold OK | Open or play / add or remove favorite |
| Live fullscreen | ▲▼, CH+/CH− | Change channel |
| Live fullscreen | 0–9 | Jump to channel number |
| Live fullscreen | OK, ◀ ▶ | Show info bar |
| Movie player | OK / ⏯ | Pause / play |
| Movie player | ◀ / ▶ (hold = faster) | Back 10 s / forward 30 s |
| Movie player | CH+/CH− | Next / previous episode |

---

## Troubleshooting

| Problem | What it means |
|---|---|
| "The portal is limiting requests" | The provider is rate-limiting your account (HTTP 429). Wait a few minutes. Avoid repeatedly refreshing or reinstalling. |
| A stream stops when another app starts | Many providers allow **one device at a time** per MAC. Close STB Emu or your MAG box while using Prism, and the other way round. |
| "Portal returned no stream" | The provider has no playable file for that title right now. Try another quality or episode, or try again later. |
| Remote does nothing | Rarely, the TV's keyboard app gets stuck. Restart the TV (hold Power → Restart). |
| Live channels play but show a black screenshot | Normal on many TVs: protected video isn't captured in screenshots. |

---

## Build from source

Requirements: **JDK 17** and the **Android SDK** (platform 34, build-tools 34). Android Studio
includes both.

```bash
git clone <this-repo-url>
cd prism-tv
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # path to your Android SDK
./gradlew testDebugUnitTest        # unit tests
./gradlew assembleRelease          # APK -> app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the local debug key so it can be sideloaded straight away. To
publish your own builds, add a signing config with your own keystore in `app/build.gradle.kts`.

### Project layout

```
app/src/main/java/tv/prism/app/
├── data/            Providers (Stalker, Xtream, M3U), caching, history, category grouping
├── player/          PlayerManager: the shared Media3 ExoPlayer, buffering, fallbacks
└── ui/              Compose for TV screens: Home, Live TV, Video Club, players, setup
```

Built with Kotlin, Jetpack Compose for TV, Media3 ExoPlayer, OkHttp and Coil. Minimum Android 6.0 (API 23).

---

## Privacy

Prism has no accounts, analytics or ads. Your sources, favorites and watch history are stored only on
your TV. Prism connects only to the providers you add, plus the artwork links they supply.

---

## License

[MIT](LICENSE) © 2026 vichu259

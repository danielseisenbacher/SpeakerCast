# SimpleAudioStream

A tiny Android app that streams whatever your phone is playing (YouTube Music, Spotify, podcasts, …) to a Sonos speaker or any other UPnP/DLNA renderer. You don't need the Sonos app, and there is **no time limit**.

It is a minimal, open-source alternative to the *Audio Cast* feature of BubbleUPnP. The free version of BubbleUPnP stops the stream after about 15 minutes.

## Usage

1. Install the APK from the [latest release](../../releases/latest).
2. Open the app once, grant the permissions and tap your speaker in the list.
3. Add the **Audio cast** tile to Quick Settings (pull down twice → edit ✏️ → drag the tile in).
4. Play music in any app, then tap the tile. Android asks *"Start recording or casting?"*. Tap **Start**.
5. Tap the tile again to stop.

The tile always casts to the last speaker you picked. Long-press the tile to open the app and pick a different one.

While casting:
- The phone's own speaker is silenced: its media volume is set to 0. It is restored when you stop casting.
- The phone's **volume keys** control the speaker volume. The notification also has *Vol −/+* buttons.
- If the speaker drops the stream (for example after a Wi-Fi hiccup), the app **restarts it automatically** within about 10 seconds.
- If you switch the speaker to another source in the Sonos app, the app stops casting.

**Casting a single app:** in the app, set **Audio from** to one app (for example YouTube Music). Then only that app's sound is cast, and sounds from other apps are left out. Android's sharing dialog always shares the *entire screen*. The dialog only grants the app permission to capture audio, and the app never records the screen image. Android's own "single app" sharing mode is turned off because Android delivers no audio in that mode.

## How it works

The same approach as BubbleUPnP's Audio Cast:

```
Other apps' audio ──► AudioPlaybackCapture (needs MediaProjection = the "screen cast" consent)
                  ──► 44.1 kHz / 16-bit stereo PCM
                  ──► tiny HTTP server on the phone:  http://<phone-ip>:<port>/stream.wav
Speaker  ◄── UPnP AVTransport SetAVTransportURI + Play (SOAP) ── the app
Speaker  ──► HTTP GET /stream.wav  (endless WAV stream)
```

- **Discovery:** SSDP M-SEARCH for `MediaRenderer:1` and `ZonePlayer:1`.
- **Control:** plain SOAP calls (`SetAVTransportURI`, `Play`, `Stop`, `GetTransportInfo`, `GetMediaInfo`, `Get/SetVolume`). The app has no UPnP library dependency.
- **Staying alive:** a foreground service (type `mediaProjection`) with a partial wake lock and Wi-Fi locks. If your phone vendor kills background apps aggressively, tap *Allow running in background* in the app.

## Limitations

- **Two taps on Android 14+.** Android shows the capture consent dialog every time a capture session starts. No app can skip it.
- **Not every app can be captured.** Apps can opt out of playback capture (some DRM-protected video apps do). YouTube Music, Spotify and most music players allow it.
- **Delay of about 1 second.** Sonos needs a small buffer, so the speaker plays slightly behind the phone. It's fine for music, but not for lip-synced video. Starting a cast can take up to about 10 seconds, because Sonos probes a new stream before it starts playing.
- **Grouped Sonos speakers:** cast to the group coordinator.

## Problems? Send a log

Open the app, tap the **⋮** menu in the top-right corner and choose **Share log**. This creates a text file containing:
- the phone model and Android version
- the selected speaker
- the app's own log, which keeps the last ~1 MB across restarts and records crashes
- the app's system log lines

Attach that file to a [GitHub issue](../../issues), or send it however you prefer. The log contains no personal data beyond your speaker's name and its local IP address.

## Building

Every push is built by GitHub Actions (`.github/workflows/build.yml`), and tags `v*` publish a release with the APK. To build locally you need the Android SDK:

```
./gradlew assembleDebug
```

## License

MIT

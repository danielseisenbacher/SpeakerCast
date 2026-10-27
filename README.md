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
- The phone's **volume keys** control the speaker volume. The notification also has *Vol −/+* buttons.
- If the speaker drops the stream (for example after a Wi-Fi hiccup), the app **restarts it automatically** within about 10 seconds.
- If you switch the speaker to another source in the Sonos app, the app stops casting.

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
- **About 2–5 seconds of delay.** Sonos buffers HTTP streams, so expect this latency. It's fine for music, but not for video.
- **The phone keeps playing.** Android doesn't mute the phone while capturing. You can enable *Mute phone while casting*. If the speaker goes silent with that switch on, your device mutes the captured audio as well, so turn the switch off and lower the phone volume instead.
- **Grouped Sonos speakers:** cast to the group coordinator.

## Building

Every push is built by GitHub Actions (`.github/workflows/build.yml`), and tags `v*` publish a release with the APK. To build locally you need the Android SDK:

```
./gradlew assembleDebug
```

## License

MIT

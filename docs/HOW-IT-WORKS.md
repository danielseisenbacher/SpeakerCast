# How SpeakerCast works

SpeakerCast uses the same technique as BubbleUPnP's *Audio Cast*: it captures what other apps play, serves it from a tiny web server on the phone, and asks the speaker over UPnP to play that stream.

```
Other apps' audio ──► AudioPlaybackCapture (needs MediaProjection = the "screen sharing" consent)
                  ──► 44.1 kHz / 16-bit stereo PCM
                  ──► HTTP server on the phone:  http://<phone-ip>:<port>/stream.wav
Speaker  ◄── UPnP AVTransport: SetAVTransportURI + Play (SOAP) ── SpeakerCast
Speaker  ──► HTTP GET /stream.wav   (endless WAV stream)
```

## Building blocks

| Part | What it does | Code |
|---|---|---|
| Discovery | SSDP M-SEARCH for `MediaRenderer:1` and `ZonePlayer:1`, then reads each device description | [`upnp/SsdpDiscovery.kt`](../app/src/main/java/at/seisi/simpleaudiostream/upnp/SsdpDiscovery.kt), [`upnp/DeviceDescription.kt`](../app/src/main/java/at/seisi/simpleaudiostream/upnp/DeviceDescription.kt) |
| Control | Plain SOAP calls: `SetAVTransportURI`, `Play`, `Stop`, `GetTransportInfo`, `GetMediaInfo`, `Get/SetVolume`. No UPnP library. | [`upnp/UpnpControl.kt`](../app/src/main/java/at/seisi/simpleaudiostream/upnp/UpnpControl.kt) |
| Capture | `AudioRecord` with `AudioPlaybackCaptureConfiguration` (media, game and unknown usages) | [`audio/CaptureEngine.kt`](../app/src/main/java/at/seisi/simpleaudiostream/audio/CaptureEngine.kt) |
| Stream | Raw-socket HTTP server serving an endless WAV (sizes set to `0xFFFFFFFF`) | [`stream/StreamServer.kt`](../app/src/main/java/at/seisi/simpleaudiostream/stream/StreamServer.kt) |
| Session | Foreground service (`mediaProjection`) with wake and Wi-Fi locks, auto-reconnect watchdog, volume keys | [`CastService.kt`](../app/src/main/java/at/seisi/simpleaudiostream/CastService.kt) |
| One-tap start | Quick Settings tile, then a transparent activity that shows the consent prompt | [`CastTileService.kt`](../app/src/main/java/at/seisi/simpleaudiostream/CastTileService.kt), [`ConsentActivity.kt`](../app/src/main/java/at/seisi/simpleaudiostream/ConsentActivity.kt) |

## Details worth knowing

- **Capture ignores the phone's volume.** The audio is captured before the phone's volume is applied. SpeakerCast therefore sets the phone's media volume to 0 when casting starts and restores it afterwards. The speaker still gets full-level audio.
- **Volume keys.** A remote-playback `MediaSession` routes the volume keys to the speaker. Android gives the keys to the most recently started media session, so SpeakerCast re-asserts its session every few seconds. While a media app is open on screen, that app handles the keys itself.
- **Silence keeps the stream alive.** When nothing is playing, silence is sent, but only up to real time. That way the padding never builds up extra delay.
- **Auto-reconnect.** Every 10 s the app checks the speaker's state. If the state is `STOPPED`, the stream is restarted with a fresh URL. If the speaker has switched to another source, the session ends without stopping the speaker.

## Sonos quirks (measured on a Play:1, firmware 86.x)

- For a URL it hasn't seen before, Sonos opens a probe connection and replies to `Play` only after about **10 s**. The app therefore allows 20 s for `Play`.
- Sonos sometimes opens **two stream connections** a few milliseconds apart and keeps one of them. The server feeds every open connection, and Sonos closes the one it doesn't need.
- After that, Sonos keeps only about **0.5 s** of audio buffered.

## Android quirks

- Android 14+ asks for capture consent **every session**. No app can skip it.
- **Single-app** screen sharing delivers *no* playback audio (verified on a Pixel 7, Android 17), so the consent prompt offers *entire screen* only.
- Apps can opt out of playback capture. Most music apps allow it, but some DRM-protected video apps don't.
- On Android 17, a release build's `Log` output may not appear in logcat. The app therefore also writes its own rotating log file, which **⋮ → Share log** exports.

## Building

GitHub Actions builds, tests and lints every push ([`build.yml`](../.github/workflows/build.yml)). Tags named `v*` publish a release. Releases are signed with a key stored in the repository secrets `KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`. Without those secrets the build falls back to the debug key.

To build locally (requires the Android SDK):

```
./gradlew assembleDebug testDebugUnitTest
```

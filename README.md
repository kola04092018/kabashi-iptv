# KABASHI IPTV 1.4.0 — Fire TV / Android TV

Personal IPTV player for authorized Xtream Codes services.

## Improvements in 1.4.0

- True fullscreen playback: the top controls automatically disappear while video plays.
- D-pad **Up / Down** channel switching inside the current live channel list.
- Compact list-style channel browser.
- New dashboard after login with **Live TV**, **Movies/VOD**, and **TV Series**.
- Newly added movies section.
- Subscription expiration and connection status display when supplied by the provider.
- Settings screen:
  - Automatic / MPEG-TS / HLS live stream mode
  - Embedded subtitles on/off
  - Player controls auto-hide on/off
  - Compact interface on/off
- Login remains saved after closing and reopening the app.
- Embedded subtitle toggle in the player.
- Faster native Media3 playback with shorter live-TV buffers and decoder fallback.
- Automatic HLS retry and an **Audio Fix** button for streams with missing audio.
- Branding note: **Created by MHILL KABASHI**.

## Provider-dependent limits

- Audio can only play if Fire TV or the selected stream mode supports the provider's codec. Use **Audio Fix** or External Player for unsupported tracks.
- Subtitles appear only when the movie, series episode, or channel includes an embedded subtitle track.
- Expiration date and newly added dates appear only when the Xtream server supplies those fields.

## Build the APK

Upload every item in this folder to the root of a GitHub repository, preserving the `.github` and `app` folders.

Open **Actions → Build Fire TV APK**. When the run succeeds, download the artifact named:

`KABASHI-IPTV-FireTV-APK`

Use only with IPTV services and content you are authorized to access.

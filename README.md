# ZineCamera

An Android camera project targeting Samsung Galaxy devices, with the Galaxy A52s 5G (SM-A528B) as the first test device.

## Current build

The current development build includes:
- Live CameraX preview, photo capture, video recording, and front/rear camera switching.
- Photo, video, portrait, night, and pro mode selectors. Portrait/night/pro are currently UI placeholders; their dedicated imaging pipelines are not implemented yet.
- Flash toggle and 3/10-second photo timer.
- Video quality selection (UHD/FHD/HD) using CameraX's supported quality fallback.
- Native camera zoom, plus experimental software crop for saved photos up to 1000x. Digital crop magnifies existing pixels and cannot restore missing detail.
- A 4K60 diagnostic that checks camera stream sizes, AE frame-rate ranges, and available video encoders. It does not force 4K60 recording.

## Important limitations

A capability appearing in diagnostics does not guarantee that every camera session can use that mode. 4K60 recording needs a compatible camera stream, FPS configuration, encoder, and recording pipeline. The current CameraX video pipeline does **not** yet force or guarantee 4K60. Extreme software zoom is currently applied to still photos only; video remains within the camera's native zoom range.

## Build

Open in Android Studio with JDK 17, or run the GitHub Actions workflow to build a debug APK. Test on the actual target device and firmware before treating a feature as supported.

## Target device

- Samsung Galaxy A52s 5G (SM-A528B)
- Android 8.0+ baseline

## License

MIT. See LICENSE.

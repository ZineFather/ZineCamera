# ZineCamera

An open-source Android camera project, initially targeting the Samsung Galaxy A52s 5G (SM-A528B).

## Current status

Early development. The first milestone provides a CameraX preview, photo capture, video recording, lens switching, a native zoom slider, and basic camera capability reporting.

## Goals

- Camera-style interface with photo and video modes
- Smooth zoom for preview and recorded output, within the camera's reported range
- Pro controls and camera capability diagnostics
- Investigate high-frame-rate recording modes, including 1080p120 and 4K60 where the device camera HAL exposes compatible streams
- Test rooted-device integrations only when safe, reproducible, and device-specific

## Hardware limitations

The app cannot create sensor, ISP, encoder, or Camera HAL capabilities that the device does not expose. A displayed resolution/FPS option is not proof that the resulting file was recorded at that mode. High-speed modes require compatible camera stream configurations and recording pipelines. Unsupported combinations may fail or fall back.

Extreme digital zoom enlarges/crops existing pixels; it does not create optical detail. The app reports supported limits rather than claiming unsupported performance.

## Build

Open this repository in Android Studio with JDK 17 and build the app module.

## Device target

- Samsung Galaxy A52s 5G (SM-A528B)
- Android 8.0+ baseline; test on the actual target firmware.

## License

MIT. See LICENSE.

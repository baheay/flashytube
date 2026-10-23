# FlashyTube

A lightweight YouTube client for Android: browse, search, play and download
videos, audio and subtitles — no ads, no account required, everything runs
on-device.

This repository is the free/open-source edition published on F-Droid: no
activation lock and no remote configuration.

## Build

    ./build-fdroid.sh

Requires JDK 17 and an Android SDK (platform 34, build-tools 34). The script
downloads nothing itself; the dependency jars listed in the F-Droid metadata
(NewPipeExtractor, jsoup, jsr305, rhino, protobuf-javalite, nanojson) go in
`libs/`.

## License

GPL-3.0-only (see LICENSE). Built on the NewPipe Extractor.

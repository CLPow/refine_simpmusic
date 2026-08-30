# Refine Music

A personal fork of [SimpMusic](https://github.com/maxrave-dev/SimpMusic) — a FOSS YouTube Music
client for Android and Desktop, built with Compose Multiplatform.

This repository is **not** the upstream project. It is a modified build maintained by
[CLPow](https://github.com/CLPow), carrying fixes and changes that suit one person's use and have
not been submitted upstream. If you are looking for SimpMusic itself, go to
[maxrave-dev/SimpMusic](https://github.com/maxrave-dev/SimpMusic).

## What differs from upstream

**Desktop**

- Windows-style window controls — minimize / maximize / close on the right, flat caption buttons,
  red close hover; square corners while maximized, 8px radius while floating
- Maximizing respects the taskbar. An undecorated window otherwise covers it and buries the player
  bar
- Mouse wheel over the volume control changes volume
- The window title shows the playing track, so the taskbar and alt-tab identify it
- The stored YouTube session is encrypted at rest with Windows DPAPI rather than written in clear
- Home shelves fit the window instead of sizing each row to the full width, and carry a scrollbar
- A stalled home load can no longer pin the refresh spinner and leave stale shelves on screen
- Seeking during a track load is applied when the load finishes rather than silently dropped
- Volume no longer jumps to full when a track changes — a fresh mpv handle was pushing its default
  onto the shared system mixer

**Android Auto**

- Browse tiles carry artwork: content-style hints are declared, without which the host renders
  every level as plain text
- Albums in a shelf no longer share one cover — they were colliding on an identical media id
- Album subtitles name the artist rather than the shelf they came from
- Playback starts immediately instead of staying silent until the next track
- Playlists lists the account's YouTube playlists and mixes, not only local ones
- Home leads with Recently played and Your artists
- Video is never requested while a car is attached, so playback cannot stall behind a
  "Video focus is off" panel

**Both**

- Followed artists can be imported from the signed-in YouTube account. Upstream only ever pushed
  local follows *to* YouTube; nothing read them back
- Updates are checked against this repository, not upstream — an official release carries none of
  the above and would replace this build rather than update it

## Building

Requires JDK 21 and the Android SDK (compileSdk 37).

    ./gradlew :desktopApp:createDistributable                    # desktop app image
    ./gradlew :androidApp:assembleRelease -PisFullBuild=false     # unsigned APK

`-PisFullBuild=false` skips the Sentry and Last.fm credentials, which live in `local.properties`
and are not in this repository.

Note that `:composeApp:mpvSetupAll` and `:desktopApp:createDistributable` must be run as **separate
invocations**: `prepareAppResources` consumes the former's output without declaring a dependency on
it, and Gradle fails the build over the undeclared ordering if both run at once.

## Licence and credit

GPL-3.0, inherited from upstream. The overwhelming majority of this code is the work of
**maxrave-dev** and the SimpMusic contributors; this fork is a comparatively small set of changes on
top of it. Their copyright notices and the licence are retained as GPL-3.0 requires of a modified
version, and this file is the notice that the work has been changed.

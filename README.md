# Refine Music

A personal fork of [SimpMusic](https://github.com/maxrave-dev/SimpMusic): a YouTube Music client
for Android and Windows, built with Compose Multiplatform.

This repository is **not** the upstream project. It is maintained by [CLPow](https://github.com/CLPow)
for one person's use, and its changes have not been submitted upstream. If you are looking for
SimpMusic itself, go to [maxrave-dev/SimpMusic](https://github.com/maxrave-dev/SimpMusic).

**Current version: 2.4.2**

## Install and updates

- **Android:** download `RefineMusic-<version>-arm64-v8a.apk` from
  [Releases](https://github.com/CLPow/refine_simpmusic/releases). From 2.4.1 on, the app checks
  this repository for new releases at start-up and in Settings › About › Check for updates. It
  downloads the APK, checks that it is signed with the same key, and opens the Android installer.
  The first time, Android asks you to allow "Install unknown apps" for Refine.
- **Windows:** download `RefineMusic-<version>.msi` from the same release and run it. It installs
  for your user account only (no admin prompt) and adds Start menu and desktop shortcuts. To update,
  run the newer MSI: it replaces the installed version. The desktop app does not check for updates
  by itself.

An official SimpMusic release is signed with a different key. It cannot update this build, and
this build never offers upstream releases.

## What it is

**A YouTube Music look-alike.** Every screen follows YouTube Music: Home, Explore and Library,
the player with Up next / Lyrics / Related, the mini player and the widgets. Features that YouTube
Music does not have were removed: the equalizer and audio enhancement, crossfade, lyrics
translation, Spotify / Last.fm / Discord, podcasts, other themes and the non-English UI. SponsorBlock
is kept as an option and is off by default.

**Your YouTube account.** Home and recommendations come from your account. Listens are reported
to your YouTube history, so recommendations follow what you play (Settings › Privacy shows the last
report). It plays songs only: when a result is a music video, Refine looks for the song version.
Timed lyrics come from YouTube Music first, then other sources.

**Like the YouTube Music app:** comments under the player (read, reply and like when signed in),
YouTube's own Home background artwork, and song search on Android: tap the sound-wave button in
an empty search field and Refine names the song playing nearby.

**Refine Control (2.4.0+).** Move music between your phone and computer, or control one from the
other, like Spotify Connect but over your own Wi-Fi with no server.

- Pair the two devices once with a 6-digit code. Traffic is encrypted end to end.
- Open the devices button and tap a device: the song moves there and keeps playing from the same
  moment. Tap "This phone" or "This computer" to bring it back.
- If you open Refine on one device while the other is playing, it shows that music and its
  controls work right away. Starting a song yourself plays it on the device in your hand.
- It is on by default and can be switched off in Settings › General.

**Android Auto.** Browse Home, your library and playlists with artwork. Next/Previous starts
playback even when paused, and the album art follows the song.

**Windows.** Global media keys, taskbar thumbnail buttons and progress, start on boot, start
minimized and hide to tray, Windows-style title bar that works with FancyZones, and volume on the
mouse wheel. Your YouTube session is encrypted at rest with Windows DPAPI.

**Playback.** The best stream your account can get (Opus about 160 kbps on a free account), with
recovery from network drops: playback resumes when the connection comes back.

## Building

Requires JDK 21 and the Android SDK (compileSdk 37).

    ./gradlew :desktopApp:createDistributable                    # Windows app image
    ./gradlew :desktopApp:packageMsi                             # Windows installer
    ./gradlew :androidApp:assembleRelease -PisFullBuild=false     # unsigned APK

`-PisFullBuild=false` skips Sentry, Crashlytics and Cast, whose credentials live in
`local.properties` and are not in this repository. Sign the APK with your own key. An APK signed
with a different key cannot update an installed Refine, and the in-app updater refuses it.

`:composeApp:mpvSetupAll` and `:desktopApp:createDistributable` must be run as **separate
invocations**: `prepareAppResources` uses the first one's output without declaring a dependency on
it, and Gradle fails the build when both run at once.

## Licence and credit

GPL-3.0, inherited from upstream. Most of this code is the work of **maxrave-dev** and the
SimpMusic contributors. Their copyright notices and the licence are kept as GPL-3.0 requires of a
modified version, and this file is the notice that the work has been changed.

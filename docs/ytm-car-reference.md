# YouTube Music 9.41.52: car and phone reference notes (clean-room)

These are behavioral notes taken from a static look at
`com.google.android.apps.youtube.music_9.41.52-94152240` (arm64-v8a). The sources were the manifest
(aapt2), the dex string inventory, and a jadx 1.5.6 decompile of `MusicBrowserService` only.
**No Google code is copied into this project.** This file records structure and behavior only.

## Car surfaces
| Component | Kind | Notes |
|---|---|---|
| `mediabrowser.MusicBrowserService` | `android.media.browse.MediaBrowserService` + MEDIA_BUTTON | Classic browse service. Thin shell over obfuscated loaders. |
| `…carapp.MusicCarAppService` | `androidx.car.app.CarAppService`, category **MEDIA** | Templated car UI. Obfuscated. |
| `mediabrowser.androidautoprojected.AndroidAutoCarAppServices` | CarAppService, category **SETTINGS** | In-car settings screen, `minCarApiLevel=3`. |
| `mediabrowser.waze.MusicWazeBroadcastReceiver` | Waze audio SDK | Not relevant here. |

## How the browse service behaves
- `onGetRoot` answers `__EMPTY_ROOT_ID__` for callers that aren't allowed. Allowed callers get a real
  root. Root IDs seen: `__MY_RECENTS_ROOT_ID__`, `__OFFLINE_ROOT_ID__`, `__SETTINGS_ROOT_ID__`,
  `__LOCAL_CONTENT_PARENT_ROOT_ID__`.
- **Tabs.** Offline mode exposes `__OFFLINE_MODE_HOME_TAB_MEDIA_ID__`, `…LIBRARY_TAB…` and `…PODCASTS_TAB…`. The
  car root is therefore **Home | Library (| Podcasts)**, opted in with `android.media.browse.AUTO_TABS_OPT_IN_HINT`.
- Children are **server-driven**. `onLoadChildren` hands the parent id plus the extras (locale,
  `caller_package_name`, `display_configuration`, `force_refresh`) to a loader that calls InnerTube
  browse IDs such as `FEmusic_home`, `FEmusic_library_landing`, `FEmusic_liked` and `FEmusic_offline_songs`.
- `onLoadItem` returns nothing (`result.sendResult(null)`). The car never resolves single items.
- Per-item extras flag `IS_LAST_PLAYED`, `IS_RECOMMENDED` and `IS_PROMOTION`. Items carry **custom browse actions**
  (`…mediabrowser.custom_actions`).
- Hints used: `CONTENT_STYLE_SUPPORTED`, `CONTENT_STYLE_{PLAYABLE,BROWSABLE}_HINT`,
  `CONTENT_STYLE_GROUP_TITLE_HINT` (shelf titles on a flat list), `CONTENT_STYLE_SINGLE_ITEM_HINT`,
  `SEARCH_SUPPORTED`, `android.media.browse.extra.PAGE_SIZE` (paged children, never unbounded).
- Metadata during playback is the plain title, artist and art: `DISPLAY_TITLE`/`DISPLAY_SUBTITLE`/`DISPLAY_ICON_URI`,
  plus `DOWNLOAD_STATUS`. **The dex has no car-lyrics strings at all**, so YT Music doesn't show lyrics in Android Auto.
- Its service `onStartCommand` calls `startForeground` **once**, behind a flag. Its `onDestroy` disposes every
  subscription it opened in `onCreate`. Nothing is left running.

## Music video types (`MUSIC_VIDEO_TYPE_*`)
- **Audio (a song):** `ATV`, `GATV`, `OFFICIAL_SOURCE_MUSIC`, `PRIVATELY_OWNED_TRACK`.
- **Not a song:** `OMV` (official MV, which has an ATV counterpart), `UGC`, `SHOULDER`, `LIVE_STREAM`, `PODCAST_EPISODE`.

## Phone layout (behavioral, from using the app)
- Bottom nav: Home · Samples · Explore · Library. Search is in the top bar.
- Now Playing: big artwork, title/artist, like/dislike, slider, transport, and a bottom sheet with
  **Up next · Lyrics · Related**. The Song/Video switch is left out here because this app plays songs only.
- Library: filter chips (Playlists · Songs · Albums · Artists · Downloads) with sort and grid/list.

## What Refine takes from this
- Car root = tabs Home · Library, with Recent and Liked/Downloaded/Playlists under Library. Shelves become a flat list
  with group-title hints. Children are paged. There are no lyrics in the car.
- One `startForeground` path, owned by Media3. Every service-owned job is disposed in `onDestroy`.

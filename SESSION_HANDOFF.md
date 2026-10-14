# Refine Music — Complete Session Handoff

**Purpose:** One file a new chat/model can read to continue this fork with zero lost context.  
**Session window:** 2026-08-30 → 2026-09-15 (fork work; upstream history predates this)  
**Repo root:** `C:\Users\USER\src\SimpMusic`  
**Fork remote:** `https://github.com/CLPow/refine_simpmusic.git` (origin)  
**Upstream:** `https://github.com/maxrave-dev/SimpMusic.git` (upstream)  
**Shipped:** tag `v2.1.15` · `version-name = 2.1.15` · `version-code = 122` (2026-10-14)  
**Working tree:** clean after 2.1.15. §12's WIP was folded into 2.1.15 (the duplicate lyrics-style radio is fixed).  

> **2.1.15 (2026-10-14) — read this first; it supersedes the AA-lyrics material below.**
> Songs only (no MV/video anywhere), AA lyrics **removed** (YouTube Music shows none either — see
> `docs/ytm-car-reference.md`), AA browse = Home · Recent · Library with grouped Home shelves,
> service lifecycle no longer kills singletons, `PlaybackReporter` sends watch history to YouTube
> (default ON), one Now Playing style, YTM-style nav/chips/mini player, Settings split into
> categories, widgets rebuilt on a single `WidgetUpdater`. Details: CLAUDE.md changelog entry
> "Refine 2.1.15". Still to verify on the phone + Toyota: a full drive (no disconnects), history
> appearing at music.youtube.com › History, widgets after a service restart.

**Product:** Personal FOSS YouTube Music client (“Refine Music” branding). Android + Desktop (KMP). GPL-3.0.  
**applicationId:** still `com.maxrave.simpmusic` (own-package experiment was **reverted**; display name/icon are Refine).

---

## 0. New-chat bootstrap (paste this first)

```
Continue development on the Refine Music fork at C:\Users\USER\src\SimpMusic.
Read C:\Users\USER\src\SimpMusic\SESSION_HANDOFF.md in full before changing anything —
it has version rules, ship pipeline, AA/lyrics history, uncommitted WIP, and open work.
Shipped: 2.1.14 / code 121 / HEAD de622aa2. Working tree may have uncommitted Apple Music UI work.
Never change version-name after assembling. Always aapt2-verify versionName before shipping.
User style: implement → build → aapt2 verify → sign → drop APK to
C:\Users\USER\Desktop\SimpMusic apk\Version X.Y.Z\ → git commit + tag vX.Y.Z → push main + tags.
User is terse. Prefer shipping over discussion. Do not invent product requirements.
Priority: (1) finish/verify uncommitted Apple Music defaults, (2) install 2.1.14+ on phone and
test Toyota AA (lyrics full drive + shuffle/repeat icons + notification title/artist),
(3) Last.fm library import if still wanted, (4) public GitHub Release with APK asset,
(5) rebuild desktop Apps\RefineMusic from current tree.
```

---

## 1. User preferences (non-negotiable)

| Preference | Detail |
|------------|--------|
| Delivery | Terse. Status → ship. No essays. |
| Versioning | Personal = upstream **A.B.C + 1**. After upstream 2.1.0 → 2.1.1, 2.1.2, … |
| APK drop path | `C:\Users\USER\Desktop\SimpMusic apk\Version X.Y.Z\RefineMusic-<ver>-arm64-v8a.apk` |
| Signing | Same cert as all prior RefineMusic APKs (`refine-simpmusic.jks`) |
| Truth check | `aapt2 dump badging` must print the **new** versionName before ship |
| Git | Commit + tag `vX.Y.Z` + push origin main + tags. Never commit secrets. |
| Desktop | `C:\Users\USER\Apps\RefineMusic\` — rebuild from current tree when shipping desktop |
| Do not | Fake version by renaming APK; invent YT shelves; leave `runCatching` around collect |

---

## 2. Ship pipeline (ALWAYS follow, in order)

```text
1. Bump version-name / version-code in gradle/libs.versions.toml FIRST
   - version-code must also rise or Android refuses install as downgrade
2. JAVA_HOME = C:\Users\USER\.jdks\jdk-21
3. cd C:\Users\USER\src\SimpMusic
4. .\gradlew.bat :androidApp:assembleRelease -PisFullBuild=false --no-daemon
5. aapt2 dump badging <unsigned apk>  → MUST print the versionName you just set
   - build-tools: C:\Users\USER\AppData\Local\Android\Sdk\build-tools\37.0.0
6. Sign:
   ks      = C:\Users\USER\.simpmusic-signing\refine-simpmusic.jks
   alias   = refine-simpmusic
   pw file = C:\Users\USER\.simpmusic-signing\keystore-password.txt
   zipalign then apksigner (same cert as every prior RefineMusic APK)
7. Copy to: C:\Users\USER\Desktop\SimpMusic apk\Version <ver>\RefineMusic-<ver>-arm64-v8a.apk
8. git add relevant sources → commit "Release X.Y.Z: <why>" → tag vX.Y.Z → push origin main --tags
9. Optional desktop: :desktopApp:createDistributable → copy to C:\Users\USER\Apps\RefineMusic → relaunch
```

**Why aapt2:** BuildKonfig/versionName is baked at assemble time. Renaming the file does nothing.

**isFullBuild:** `false` for personal APK (no Sentry / Crashlytics / Cast / optional Last.fm server bits). Full build enables those when the property is true.

---

## 3. Versioning rule (user’s rule)

| Situation | Next personal build |
|-----------|---------------------|
| Personal build already on A.B.C | A.B.C+1 |
| Upstream releases A.B.C | A.B.C+1 (restarts the +1 count on the new base) |

Upstream SimpMusic **2.1.0** (≈2026-09-07) → this fork: **2.1.1 … 2.1.14**.

Current catalog (`gradle/libs.versions.toml`):

```toml
version-name = "2.1.14"
version-code = "121"
```

---

## 4. Environment / paths

| Thing | Path / value |
|-------|----------------|
| Project | `C:\Users\USER\src\SimpMusic` |
| JDK 21 | `C:\Users\USER\.jdks\jdk-21` |
| Android SDK | `C:\Users\USER\AppData\Local\Android\Sdk` |
| adb | `…\Sdk\platform-tools\adb.exe` |
| aapt2 / apksigner / zipalign | `…\Sdk\build-tools\37.0.0` (34–36 also present) |
| DHU | `…\Sdk\extras\google\auto\desktop-head-unit.exe` |
| Keystore | `C:\Users\USER\.simpmusic-signing\refine-simpmusic.jks` |
| Keystore password | `C:\Users\USER\.simpmusic-signing\keystore-password.txt` |
| APK archive | `C:\Users\USER\Desktop\SimpMusic apk\Version X.Y.Z\` |
| Desktop install | `C:\Users\USER\Apps\RefineMusic\RefineMusic.exe` |
| Desktop data | `C:\Users\USER\.simpmusic\` (`settings.preferences_pb`, `Music Database`, `db\`) |
| Phone | Xiaomi 13T Pro · serial `SS6DR45TIVYDHQ5H` · model `23078PND5G` · Android 16 |
| Latest shipped APK | `C:\Users\USER\Desktop\SimpMusic apk\Version 2.1.14\RefineMusic-2.1.14-arm64-v8a.apk` (27,472,838 bytes, 2026-09-15 22:27 local) |

**Secrets:** never commit. `local.properties` is gitignored. Do not paste keystore password into chat/commits.

---

## 5. Product requirements (from user, this fork session)

1. **Booming Music** theme as a 4th theme option (alongside System / Dark / Light).  
2. When Booming is selected → force default **Now Playing = M3 Expressive** + **Lyrics = Apple Music**.  
3. Booming Home must use the **same YouTube Music shelves** as classic (no invented or duplicated sections).  
4. **Remove India** from location list **and** chart country list.  
5. **Sync backup** from phone into desktop (`~/.simpmusic/`).  
6. **Lyrics phone:** keep lyrics in **normal** Now Playing **and** **fullscreen** (tabs: All / Original / Translation / Romanized).  
7. **Lyrics Android Auto:** title + real artist visible; lyric in artist/subtitle row; **must stay alive for the entire drive** (not die after ~1.5 songs).  
8. **Shuffle / repeat** icons on AA must reflect and stick to actual state when tapped.  
9. **Notification / widget** must show **title + artist**, not the lyric as artist.  
10. Defaults: car lyrics lead **1000 ms**, lyrics sync offset **1000 ms**.  
11. Import lyrics from pasted text; Last.fm library download; scrobbling.  
12. Self-update via GitHub Releases once the repo is public.  
13. **Correction from user:** “lyrics only fullscreen” referred to **Android Auto**, not the phone. Phone must keep lyrics in normal + fullscreen.  
14. **Latest ask (uncommitted):** default Now Playing + lyrics style toward **Apple Music**; song title + artist **more prominent** (Apple-like type scale).

---

## 6. Chronological timeline (fork work)

### A. Pre-fork baseline
- Upstream SimpMusic 2.0.0 era. Merge `ec674de1` (PR #2413).

### B. Fork foundation (2026-08-30)
| Commit | Note |
|--------|------|
| `f1d39ae9` | Windows-native chrome, scrollable volume, YouTube follow import |
| `fc32345a` | **Vendor core modules into this repo** (stop depending on unpublished core submodule) |
| `d823359e` | Take updates from **this fork**, not upstream |
| `b1265cb3` | AA: artwork everywhere; Home from local listening |
| `3ad20eb1` | Fix AA playing silence until next track |
| `94d70700` | Remove build logs committed by mistake |
| `15c1b0bb` | Deferred seeks, encrypted desktop credentials, fork attribution |
| `3afbf614` | Import follows on sign-in; desktop title shows track |
| `78c2901f` | Stalled home load no longer pins spinner / stale shelves |
| `5ba5fd73` | Do **not** import followed artists on sign-in |
| `92dac601`…`b8f556f3` | AA content styles, browse artwork, YT playlists, square artist tiles |
| `9e1cf211` | AA: albums no longer share one cover |
| Desktop UI | shelves fit window, Quick picks scrollbar, quieter single scrollbar |
| `4794b65f` | Update dialog Download button → this fork |
| `32833001` | Rebrand display as Refine Music |
| `540a0ed3` → `cff3796f` | Own icon/appId/data dir **reverted** (keep `com.maxrave.simpmusic`) |

### C. Spotify / sync / quality (2026-08-31 → 09-02)
- Spotify playlist import from share link; Client-Token; private-playlist errors; rate-limit stand-down.  
- Full Spotify library sync; stop prompt-on-launch; web-player playlist endpoint.  
- Sync groundwork: change tracking, deletion tombstones, tested merge.  
- Crossfade timing/smoothness; exhaustive lyrics; limiter; diagnostics (“Release A”).  
- Best free audio tier; local shelves; drop dead setting.  
- Empty home fix + stream fallback hardening + full code-review pass.

### D. Android Auto lyrics + security (2026-09-09 → 09-11)
| Version | Commit | What |
|---------|--------|------|
| (pre) | `8cb01471` | Show synced lyrics on Android Auto |
| (pre) | `b49b9ea2` | Match lyrics to the **recording playing**, not first search hit |
| 2.0.37 | `6c82a981` / `8c32086f` | Harden credentials/network; `allowBackup=false` wins merge |
| 2.0.39 | `77c73789` | Per-secret keychain slots; desktop loudnessDb; larger lyrics |
| 2.0.41 | `9410a8a9` | Tunable car lyrics; playback guards; update toggle |
| 2.0.42 | `31f53aca` | Semantic update compare; APK asset download |
| 2.0.43 | `e1c0584f` | Glance widgets redesign (Now Playing / Shelf / Insights) |

### E. Booming Music theme (2026-09-13)
| Version | Commit | What |
|---------|--------|------|
| 2.0.44 | `21549c00` | Booming theme (indigo M3) + force M3E player + Apple lyrics when selected |
| 2.0.45 | `616e3197` | Visible on Home (solid indigo surface, accent title) |
| 2.0.46 | `2af79a39` | Full Booming home (For you, Shuffle mosaic, Listen now) |
| 2.0.47–48 | | Settings gear, full shelves, single wordmark, chart artists with photos |
| 2.0.49 | `67626067` | Listen Together join-confirm dialog |
| 2.0.50 | `de1b4888` | HTTPS-only setting; lyrics provider order; desktop tray transport |

**Revert:** `321cbe9d` Listen Together client identity needs core `AppIdentity` not in tree → `9ad0e8ec` revert.

### F. Upstream 2.1.0 cherry-picks + home (2026-09-13)
| Version | Commit | What |
|---------|--------|------|
| 2.1.1 | `52689a70` | Upstream 2.1.0: unsynced lyrics blur, queue growth from artwork pager cancel, settings reset |
| 2.1.2 | `8290806e` | Booming home: **one pass** over YT shelves, no duplicate sections |
| 2.1.3 | `05ab87c0` | Remove IN location/charts; adaptive Booming tiles desktop |
| 2.1.4–6 | | Home shelves independent; For you grid fills width (weighted tiles) |
| 2.1.7 | `1ac5afec` + `aa99219c` | India out of chart list; AA shuffle/repeat/share player commands; **set toml version** |
| 2.1.8 | `4237fd15` | Home load: **no `runCatching`** around collect (swallowed CancellationException); aapt2-truth versionName |
| 2.1.9 | `d7f6b13d` | Lyrics sync offset default **1000ms**; album/playlist header polish |
| 2.1.10 | `8dac24a5` | Car lyrics lead default **1000ms** |
| 2.1.11 | `61b7e137` | Settings radios visible when message+selectOne both set; AA lyrics no mid-drive restart |
| 2.1.12 | `aded9419` | Restore phone lyrics; AA keeps title+artist; fullscreen lyrics tabs |
| 2.1.13 | `f492513d` | Import text lyrics from Now Playing |
| 2.1.14 | `de622aa2` | AA lyrics **poller**; shuffle/repeat **not** intercepted |

### G. Uncommitted after 2.1.14 (see §12)
Apple Music style defaults + louder title/artist typography. **Not shipped. Not tagged.**

---

## 7. Implementation detail (what is actually in the tree)

### 7.1 Security / network
- HTTPS-by-default; cleartext only `suggestqueries.google.com`, `i.ytimg.com`.  
- Artwork fallbacks → `https://i.ytimg.com/...`. Never `http://` in MediaSession metadata.  
- `allowBackup=false`; backup_rules exclude DataStore/DB.  
- **SecretCodec** purpose-named keys (`session`, `spotify.sp_dc`, tokens):  
  - Windows DPAPI **fail-closed** (empty encode → keep previous ciphertext)  
  - macOS Keychain per-purpose account under `org.simpmusic.secret`  
  - Linux AES-GCM file key `~/.simpmusic/.secret-key` (0600)  
- `isProtected()` for Settings status row.  
- Spotify secretDict: structure-check + last-good cache (**not** hard SHA pin — rotates).  
- Room v29: `queue.playlistType` (album queue survives restart for crossfade).  
- Desktop loudness: static `loudnessDb` via `setLoudnessGainDb`, not `dynaudnorm` (pumped).  
- `*.log` gitignored.

### 7.2 Booming Music theme
- Constant: `DataStoreManager.THEME_MODE_BOOMING = "BOOMING"`.  
- Fixed M3 scheme from mardous/BoomingMusic (indigo primary, blue-black surface). Always dark; ignores wallpaper/custom seed.  
- Selecting Booming **forces** `NOW_PLAYING_STYLE_M3_EXPRESSIVE` + `LYRICS_STYLE_APPLE_MUSIC` (`SharedViewModel` ~1882).  
- `LocalIsBoomingTheme` composition local; `Theme.kt` / `App.kt` gate.  
- **Home:** `BoomingHomeScreen` — header “Refine Music” + ⚙ Settings + Search/Insights/History; For you Shuffle card + weighted cover grid; **every** `homeData` shelf once with real YT title; chart artists with photos.  
- Wide desktop For you: shuffle ~200dp square + `weight(1f)` covers fill full width.

### 7.3 Home loading (critical)
`HomeViewModel.getHomeItemList()`:
- Local shelves immediately  
- Keep previous successful feed  
- YouTube `getHomeData` primary — **no `runCatching`** (swallows `CancellationException`)  
- Chart / new-release / mood in parallel, never block home  
- Timeout 30s only flags failure if list empty  

Trap that caused empty/stuck home: `combine()` waiting for all sources; `runCatching { collect }` killing the coroutine.

### 7.4 Android Auto
**File:** `core/media/media3/.../carapp/CarLyricsOverlay.kt`  
- **Poller** architecture (2.1.14): poll videoId every 250ms; followLines every 100ms. **No** `collectLatest` per track (that died ~1.5 songs).  
- Mode default: `AA_LYRICS_TITLE_AND_LYRIC` (`"aa_lyrics_title_and_lyric"`) — title = real song title; lyric goes in artist/subtitle slot.  
- Lead default 1000ms + sync offset 1000ms (Settings adjustable).  
- Network lyric failure **must not** be cached as null.  
- `openNowPlayingOrStay`: **no** `popToRoot()` on track pick (that bounced to home).  
- `getProgress()` uses **live** ExoPlayer position (secondary during crossfade).

**Session callback:** `core/media/media3/.../service/callback/SimpleMediaSessionCallback.kt`  
- **Do not intercept** `COMMAND_SET_SHUFFLE_MODE` / `COMMAND_SET_REPEAT_MODE` — session writes adapter state; intercept raced and froze car icons.  
- Still available: custom LIKE, REPEAT, RADIO, SHUFFLE, SHARE, GET_PLATFORM_TOKEN (player commands).  
- Chart `items[]` and `itemsData[]` must stay **index-aligned** (IN removal desynced once).

### 7.5 Phone player / lyrics
- Lyrics card + inline line **restored** in `NowPlayingContentSpotify` (user: phone needs lyrics).  
- Fullscreen: `LyricsTabBar` All / Original / Translation / Romanized.  
- Import text lyrics: DownloadForOffline icon → dialog → `SharedViewModel.importTextLyrics` → `LyricsEntity(UNSYNCED)`.  
- Album/Playlist desktop headers: 320dp art, `headlineLarge` bold title.  
- Apple Music renderer: `AppleMusicLyricsView` / `AppleMusicShared` / `NowPlayingContent` applemusic package.  
- `isLyricsBlurSupported()` gates Apple lyrics style (Android 12+). Restored DataStore can still hold APPLE_MUSIC on unsupported devices — UI must re-check support.

### 7.6 Settings that exist
- Theme: System / Dark / Light / **Booming Music**  
- Now Playing style: Spotify / M3 Expressive / Apple Music  
- Lyrics style: Classic / Apple Music  
- Car lyrics timing (ms), Lyrics sync offset (ms), Lyrics size %  
- Lyrics provider order (CSV presets)  
- Skip short tracks (s), Crossfade prep buffer (ms)  
- Listen Together confirm join, HTTPS only, Check for updates  
- Normalize volume, EQ, AA lyrics mode (OFF / TITLE_AND_LYRIC / …), import lyrics  
- **Bug fixed 2.1.11:** dialog used `if (message != null) else if (selectOne)` and hid radios when both set → show message **and** list.

### 7.7 India removed
- `SUPPORTED_LOCATION.items`: no `"IN"`  
- `CHART_SUPPORTED_COUNTRY.items` **and** `itemsData`: `"India"` removed **together**  
- First-run default never falls back to IN (default US / other)

### 7.8 Backup sync (phone → desktop)
1. Android `.backup` zip → extract.  
2. Copy `settings.preferences_pb` + `Music Database` into `C:\Users\USER\.simpmusic\` and `~/.simpmusic\db\`.  
3. **Quit desktop fully first.** Delete WAL/SHM/lck. Copy **only** the main DB file.  
4. Restore used `settings.preferences_pb` from backup (example location MY).

### 7.9 Widgets (2.0.43)
Glance: Now Playing (art+title+artist+FAB), Shelf (recent tiles), Insights (stat cards + covers). Not the focus after 2.0.43.

---

## 8. Architecture map

```
UI:       composeApp (commonMain + androidMain + jvmMain)
DI:       Koin
Domain:   core/domain   (repos, MediaPlayerInterface, DataStoreManager + Values constants)
Data:     core/data      (Room, DataStoreManagerImpl, security/SecretCodec*, UpdateRepository)
Media:    core/media/media3 (ExoPlayer + Android Auto)
          core/media/media-jvm (desktop libmpv)
Scrape:   core/service/kotlinYtmusicScraper, spotify, lyricsService, lastfm, kizzy, …
Entries:  androidApp/MainActivity, composeApp App.kt, desktopApp
Build:    gradle/libs.versions.toml (version-name/code + dep catalog)
Variant:  isFullBuild gates Sentry / Last.fm / Cast / Crashlytics
```

**Key constants** (`core/domain/.../DataStoreManager.kt` `Values`):
- `THEME_MODE_BOOMING = "BOOMING"`
- `NOW_PLAYING_STYLE_APPLE_MUSIC = "APPLE_MUSIC"`
- `LYRICS_STYLE_APPLE_MUSIC = "APPLE_MUSIC"`
- `AA_LYRICS_TITLE_AND_LYRIC = "aa_lyrics_title_and_lyric"`

**applicationId:** `com.maxrave.simpmusic` (`androidApp/build.gradle.kts`); debug suffix `.dev`.

---

## 9. Known bugs / gotchas (hard-won — do not re-learn)

| Trap | Fix |
|------|-----|
| Version baked at assemble → APK shows old name | Set toml **first**, verify with aapt2 |
| `combine()` waits for all sources → empty home | Independent collectors |
| `runCatching { collect }` swallows CancellationException | try/catch + rethrow CancellationException |
| AA lyrics `collectLatest` per song → dies ~1.5 songs | Poller loop, no per-track cancel |
| Intercept COMMAND_SET_SHUFFLE/REPEAT → frozen AA icons | Let session write adapter |
| Settings `if (message != null) else if (selectOne)` hides radios | Show message **and** select list |
| Chart `items` vs `itemsData` misaligned → scrambled countries | Edit both arrays together |
| Desktop DB restore while app running → malformed | Quit app; delete WAL; copy only main DB |
| `popToRoot()` on AA track pick → back to home | `openNowPlayingOrStay` |
| Notification artist overwritten by lyric | TITLE_AND_LYRIC keeps real title |
| Hard SHA pin on secretDict → Spotify breaks every few weeks | Structure-check + last-good |
| `http://i.ytimg.com` in media session | Always https |
| Signing cert mismatch | Same `refine-simpmusic.jks` |
| MIUI “Install canceled by user” | Enable Install via USB; or share APK over USB |
| “Package appears corrupted” | Incomplete file / signature mismatch — re-copy, uninstall, install |
| `ColorScheme()` ctor needs surfaceTint | Use `darkColorScheme()` builder |
| Compose Resources need per-import for `Res.string.X` | Add import after new string |
| Listen Together identity without core AppIdentity | Keep reverted until submodule/core bumped |
| DHU exits instantly | Start Head unit server on phone **first** |
| Xiaomi AOAP `-u` never seen by DHU | Use `adb forward tcp:5277 tcp:5277` + ADB mode |
| Desktop not seeing latest music | Rebuild from current tree; last full refresh was 2.1.6 era |

---

## 10. Device / DHU / install notes

**Phone:** Xiaomi 13T Pro (`SS6DR45TIVYDHQ5H`, `23078PND5G`, Android 16)

**DHU order (required):**
1. Phone: enable Head unit server (Wireless debugging / Auto developer options as used previously).  
2. PC: `adb forward tcp:5277 tcp:5277`  
3. Start `desktop-head-unit.exe` in ADB mode.  
4. Do **not** rely on `-u` USB AOAP on this Xiaomi.

**Install:** side-load the signed arm64-v8a APK. If MIUI blocks, enable “Install via USB” / USB debugging (security settings).

**Car (Toyota) AA still untested for 2.1.14:** shuffle/repeat icons + multi-song lyrics + notification title/artist.

---

## 11. Shipped artifact status

| Item | Status |
|------|--------|
| 2.1.14 APK on Desktop folder | Yes, verified 2.1.14 / 121 |
| Git tag `v2.1.14` + origin push | Yes (`de622aa2`) |
| Phone install / Toyota AA test | **Not done** |
| Desktop `Apps\RefineMusic` refresh | **Stale** (last full 2.1.6-era) |
| Public GitHub Release + APK asset | **Blocked** while repo private (`releases/latest` 404) |
| Last.fm full library download | **Not implemented** |
| Uncommitted Apple Music UI work | In working tree only |

To publish release after repo is public:
```bash
gh release create v2.1.14 "C:\Users\USER\Desktop\SimpMusic apk\Version 2.1.14\RefineMusic-2.1.14-arm64-v8a.apk"
```
Or upload in the GitHub **Releases → Attach binaries** box (not markdown notes).

---

## 12. Uncommitted WIP after 2.1.14 (IMPORTANT)

**Goal (user):** Apple Music as the default player/lyrics look; title + artist more prominent (Apple-like).

**Modified (uncommitted):**
| File | Change |
|------|--------|
| `composeApp/.../ui/component/LyricsView.kt` | collectAsState default CLASSIC → APPLE_MUSIC (inline + fullscreen) |
| `composeApp/.../ui/screen/home/SettingScreen.kt` | lyricsStyle initial default → APPLE_MUSIC |
| `composeApp/.../ui/screen/player/NowPlayingScreen.kt` | nowPlayingStyle initial default → APPLE_MUSIC |
| `composeApp/.../ui/screen/player/content/applemusic/AppleMusicLyricsView.kt` | lyricsStyle default → APPLE_MUSIC |
| `composeApp/.../ui/screen/player/content/applemusic/AppleMusicShared.kt` | `mainTitle` = `headlineMedium` + Bold; `mainArtist` = `titleSmall` (was titleMedium/bodyMedium) |
| `core/data/.../DataStoreManagerImpl.kt` | DataStore fallbacks: NOW_PLAYING_STYLE → APPLE_MUSIC; LYRICS_STYLE → APPLE_MUSIC |

**Known defect in WIP — fix before ship:**
`SettingScreen.kt` lyrics style radio list currently has **both** entries as `LYRICS_STYLE_APPLE_MUSIC` (first row still labeled Classic). Restore the Classic option as `LYRICS_STYLE_CLASSIC to …classic`, keep Apple Music as the second entry. Do not ship with two identical values.

**Also decide before 2.1.15:**
- Keep Classic selectable (user still wants the option) — only change **defaults**, not remove Classic.  
- Apple lyrics still require `isLyricsBlurSupported()` (Android 12+).  
- Existing installs keep stored DataStore prefs; defaults only affect fresh/missing keys. If user wants forced switch on upgrade, that is a **migration**, not a default change — ask only if they complain after install.  
- Booming theme still forces M3 Expressive + Apple lyrics on select — independent of these defaults.

**Not yet:** version bump, build, aapt2, sign, tag. Next ship = **2.1.15 / 122** once WIP is clean and verified.

---

## 13. Open work (priority order)

1. **Finish WIP (§12):** fix SettingScreen Classic radio; compile; visual check Apple title/artist scale.  
2. **Ship 2.1.15** if user wants defaults in an APK: bump toml → build → aapt2 → sign → Desktop folder → commit/tag/push.  
3. **Install on phone + Toyota AA:** shuffle/repeat icons; lyrics for full multi-song drive; notification title+artist.  
4. If lyrics still die: log `CarLyrics` tag; check `CarConnectionState` flaps; ensure `CarLyricsSource` does not cache null network failures.  
5. If shuffle/repeat still stuck: verify adapter `shuffleModeEnabled`/`repeatMode` notify session; command buttons from Media3Ext.  
6. Rebuild/reinstall desktop `Apps\RefineMusic` from current tree.  
7. Make repo **public** → `gh release create` with APK asset (enables in-app update).  
8. Last.fm library import (`user.getRecentTracks` → `playback_event`) if still wanted.  
9. Server-side Last.fm scrobble verify (exists in full build; untested this session).  
10. Deferred: desktop offline downloads, tray transport polish, device pickers, HTTPS-only force, lyrics provider drag-reorder, upstream Canvas fallback / delay-reverb / playlist cover crop, Listen Together `AppIdentity` after core bump.

---

## 14. Files most edited (this fork session)

```
composeApp/.../ui/screen/home/BoomingHomeScreen.kt          (new)
composeApp/.../ui/screen/home/SettingScreen.kt
composeApp/.../ui/screen/home/HomeScreen.kt
composeApp/.../viewModel/HomeViewModel.kt
composeApp/.../viewModel/SharedViewModel.kt
composeApp/.../ui/component/LyricsView.kt
composeApp/.../ui/component/AppleMusicLyricsLines.kt
composeApp/.../ui/screen/player/NowPlayingScreen.kt
composeApp/.../ui/screen/player/content/NowPlayingContentSpotify.kt
composeApp/.../ui/screen/player/content/applemusic/*
composeApp/.../ui/screen/other/AlbumScreen.kt / PlaylistScreen.kt
composeApp/.../ui/theme/Theme.kt
composeApp/.../App.kt
core/domain/.../manager/DataStoreManager.kt
core/data/.../dataStore/DataStoreManagerImpl.kt
core/data/.../security/SecretCodec*.kt / DesktopFileSecretCodec.kt
core/data/.../repository/UpdateRepositoryImpl.kt
core/media/media3/.../carapp/CarLyricsOverlay.kt
core/media/media3/.../service/callback/SimpleMediaSessionCallback.kt
core/media/media3/.../exoplayer/CrossfadeExoPlayerAdapter.kt
core/common/.../Config.kt                                   (IN removed)
androidApp/build.gradle.kts / AndroidManifest.xml
gradle/libs.versions.toml
desktopApp/* (chrome, scrollbars, tray, loudness)
```

---

## 15. Working agreement for the next model

1. Read this file **before** any edit.  
2. Check `git status` — uncommitted §12 work may still be present.  
3. Do not bump version until the user asks to ship.  
4. When shipping: follow §2 exactly. aapt2 is the gate.  
5. Prefer small, verifiable commits with `Release X.Y.Z: <why>` message style.  
6. Keep user messages short.  
7. If AA behavior is in question and no car is attached, say so — do not claim car-verified.  
8. Never re-introduce: `runCatching` around collect, shuffle/repeat intercept, `popToRoot` on AA pick, http artwork, India in only one of the two chart arrays.

---

*End of handoff. If anything here conflicts with live `git status` / toml, trust the working tree and update this file in the same change.*

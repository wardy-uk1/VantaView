# VantaView


**Download the latest version:** https://github.com/wardy-uk1/VantaView/releases/latest/download/VantaView.apk

**IPTV & VOD player for Android phones, tablets, Android TV and Fire OS.**

VantaView is only a player. It does not include, host or recommend any channels, films or series.
You add your own playlists.

> Early test build. This is the first slice of the app, not the finished design.

## What this test build does

- Terms screen on first launch (Agree / Disagree)
- Add a playlist: Smart Add (paste your provider's message), M3U link, or Xtream Codes login
- Syncs channels into a local database; Xtream passwords are encrypted with the Android Keystore
- Live TV: categories, search (names or numbers), favourites, now and next on each channel; side-by-side layout on TV and tablets
- TV Guide: timeline from yesterday to a week ahead, from the playlist's own guide (Xtream or the M3U's url-tvg)
- Optional free channels from [Free TV](https://github.com/Free-TV/IPTV), added only if you choose
- Player: Media3 ExoPlayer, automatic reconnect when a stream drops, channel up/down
- Works with touch and with a TV remote

Not in yet: reminders, catch-up, profiles, recording, downloads, Stalker/MAC, add-ons, settings.

See [docs/FEATURES.md](docs/FEATURES.md) for the feature checklist: what is built and what is still to come.

## Download

Get `VantaView-test.apk` from the [test-latest release](../../releases/tag/test-latest).
Every push to `main` rebuilds it with GitHub Actions.

## Build it yourself

Open the folder in Android Studio and run, or with Gradle 8.11+ and JDK 17:

    gradle assembleDebug

## Privacy

VantaView has no server of its own: your playlists and logins stay on your device and go straight to your provider. See [docs/PRIVACY.md](docs/PRIVACY.md) for exactly what connects where.

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE).

"VantaView" and the VantaView logo are not covered by that licence. Please don't use the name or
logo for modified builds.

## Credits

- Reddit user u/CrazyDowntown5881: quite a few suggestions and bug reports. Thank you!
- Reddit user u/Turbulent-Invite2673: picture sizes, full screen past the notch, player controls, stream choice, external players and subtitle timing. Thank you!
- [Free TV](https://github.com/Free-TV/IPTV): the optional free channels list, fetched from its GitHub page. Thanks to its maintainers and contributors.
- [epgshare01](https://epgshare01.online): the TV guide used by the Free TV list, fetched only for the countries you pick.
- [whisper.cpp](https://github.com/ggerganov/whisper.cpp) and OpenAI's Whisper models (MIT): the optional AI subtitles, run on the device.
- [Stream Goat](https://github.com/sherazi5110/stream-goat) by Syed Ali Hamza (GPL-3.0): the M3U
  parser and Xtream login check are adapted from its `LiveTv.kt`.
- [CloudStream](https://github.com/recloudstream/cloudstream) (GPL-3.0), upstream of Stream Goat.
- [Stalker-Portal](https://github.com/Jitendraunatti/Stalker-Portal) by Jitendra Unatti (GPL-3.0): how a MAG box signs in to a Stalker portal (the full profile details, hardware codes and logo folder), used as a reference for VantaView's own Stalker client.
- [IntroDB](https://introdb.app) and [SkipDB](https://skipdb.tv): intro and end-credits times for Skip intro and the Next episode countdown. Contains data from SkipDB, made available under the [ODbL](https://opendatacommons.org/licenses/odbl/1-0/).
- [iptv-org database](https://github.com/iptv-org/database) (public domain): channel countries, categories, other spellings and regional feeds behind Live TV's topic groups, Importance order and channel versions. Downloaded on the device when one of those is used.
- [Cloudflare speed test](https://speed.cloudflare.com): the server the internet speed test downloads from.
- [Trakt](https://trakt.tv): optional per-profile sync of the Watchlist, watched history, Continue watching, recommendations and lists. Not made or endorsed by Trakt. Builds get the app's Client ID from `local.properties` or a `TRAKT_CLIENT_ID` Actions secret (a `TRAKT_CLIENT_SECRET` is only needed for older Trakt apps that still have one); without it Settings › Trakt says it isn't set up.
- [TMDB](https://www.themoviedb.org): films and series behind the Movies and TV Series tiles. This product uses the TMDB API but is not endorsed or certified by TMDB. Builds get the key from `local.properties` or a `TMDB_ACCESS_TOKEN` Actions secret; without one the tiles use Cinemeta.
- [Tabler Icons](https://github.com/tabler/tabler-icons) (MIT) and [flag-icons](https://github.com/lipis/flag-icons) (MIT): drawn into the app's own tile pictures by `tools/browse-art/make.py`.
- [Bebas Neue](https://github.com/google/fonts/tree/main/ofl/bebasneue) by Ryoichi Tsunekawa (SIL Open Font License 1.1, `tools/browse-art/BebasNeue-OFL.txt`): tile titles.
- AndroidX, Jetpack Compose, Media3, Room, Hilt, OkHttp, Coil, Kotlin (Apache-2.0).

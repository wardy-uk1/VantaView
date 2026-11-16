# VantaView feature checklist

Every agreed feature, grouped by page. `[x]` means it's built in the test build; it still needs testing on a phone and a TV. Items marked **(later)** were planned for after the first release. Notes in *italics* say what's still missing from a part-built item.

This file is ticked off in the same commit that finishes a feature.

## Setup and first launch

- [x] Splash screen with logo, loading bar, and version and build number
- [x] Terms: Agree or Disagree only (Disagree closes the app)
- [x] First launch order: Terms, Create profile, Add Playlist (Smart Add or Free TV), Films and series add-ons, Home (the last two can be skipped)
- [x] Skip Choose profile when there's only one profile
- [x] Update notice on launch (checks every time the app starts)

## Profiles

- [x] Create, edit and delete profiles
- [x] Built-in neon avatars
- [x] Optional 4-digit PIN
- [x] Kids profiles with an age limit and no Settings
- [x] Separate favourites, watchlist, history, Continue watching and settings per profile
- [x] Copy sources from the main profile
- [x] Share a title to another profile (goes to their Library, with a message)

## Sources (Add Playlist)

- [x] Xtream Codes
- [x] M3U link or file
- [x] Stalker / MAC portal
- [x] Smart Add (paste a provider message)
- [x] Optional EPG link per source
- [x] Free channels (Free TV list, country picker, daily refresh, YouTube/Twitch/Dailymotion removed)
- [x] Addon links **(later)**
- [ ] Local files **(later)**
- [ ] Add from phone by QR code **(later)**
- [ ] Jellyfin server **(later)**

## Home

- [x] Clock and date
- [ ] Live TV, Movies, TV Series and Sports tiles *(TV Guide tile instead of Sports)*
- [x] Mini player of last channel, muted, with unmute
- [x] Continue watching row
- [x] Favourite channels row
- [x] NEW SEASON and COMING SOON badges, new season message
- [x] Shared with you message
- [x] Weather by the clock with 5-day forecast **(later)**

## Live TV and guide

- [x] Categories, channel list and live preview
- [x] Now, Next and After
- [x] Timeline guide view
- [x] Full programme descriptions and scrolling long titles
- [x] Search, sort and edit favourites
- [x] Reminders for programmes not yet started (1/5/10/15 min, default 5)
- [x] Category order and visibility per source (Live TV › Groups)

## Catch-up

- [x] Catch-up page: groups, channels, days, past programmes (opened from its own Home tile, or Live TV)
- [x] Watch from start in the guide
- [x] Search past programmes

## Player

- [x] Live: previous/next channel, remote Up/Down changes channel
- [x] Live: pause and rewind with Go live (catch-up channels rewind through the archive; others record on the device from the moment you pause, up to 30 minutes)
- [x] Live: Now/Next bar and quick channel list
- [x] Audio, subtitles and picture size
- [x] Smart anti-buffering with Medium buffer by default (adjusts itself after stalls; small devices stay at Medium or below)
- [x] Auto-reconnect with "Reconnecting" message
- [x] VOD: ±10s, speed, Episodes, resume
- [x] Skip intro (IntroDB, then SkipDB) and Next episode countdown
- [x] Controls hide after 5 seconds (adjustable)
- [x] Phone: swipe gestures, lock button, picture-in-picture
- [ ] Record from the player **(later)**

## Movies and TV Series

- [x] Recently added banner (Nuvio style)
- [x] Genre tiles
- [x] Sort, Genre, Year and Source filters
- [x] Posters-only grid (Stremio style)
- [x] Info on focus on TV
- [x] TMDB artwork and details (Bearer token)

## Details pages

- [x] Movie details: Play/Resume, Trailer, Watchlist, Watched
- [x] Trailer auto-play after 20s with sound and mute
- [x] Auto-pick best source, Choose source in the menu
- [x] Cast row and More like this (at the bottom, under the episodes)
- [x] Actor page with Available and All tabs, sort and filters
- [x] Series details: season tabs, episode cards, Resume S2 E4
- [x] Coming soon seasons
- [ ] Download episode or whole season **(later)**

## Search

- [x] Channels, films, series, programmes and people
- [x] On-screen keyboard on TV, recent searches
- [x] Voice search where available

## Library

- [x] Shared with you
- [x] Watchlist (also a row on Home, with "New episode" badges on series)
- [ ] Downloads **(later)**
- [ ] Recordings **(later)**

## Sports (later)

- [ ] On now, later today and this week from the guide
- [ ] Event details with Remind me and Record
- [ ] Follow teams and leagues
- [ ] Channels listed per game

## Settings

- [x] Two panes on TV, list on phone, search settings; each page's sections are drop-downs, one open at a time
- [x] Playback: buffer, trailers, reminders, controls, mini player, gestures
- [x] Appearance: Dark/Light/Follow device, accent colour, language
- [x] Subtitles and audio
- [x] Sources and storage, Free channels settings
- [x] Local backup and restore
- [x] Parental control and clear history, plus a shortcut to add, change or remove this profile's PIN
- [x] Check for updates (downloads the APK and opens the installer)
- [x] Speed test: download, upload, ping and jitter on a live dial (Cloudflare), or a download from your own playlist
- [x] App tour and support
- [x] Ambient mode (screensaver on menus)
- [x] Terms, Credits, About
- [x] Trakt sync: per-profile sign-in with a code; Watchlist, watched films and episodes, Continue watching (scrobbling) and recommendations and lists rows
- [ ] Connected servers: Jellyfin, Sonarr, Radarr **(later)**

## Suggested for the first release (not yet agreed)

- [x] Type a channel number on the remote
- [x] Last channel button
- [x] Guide time offset per source
- [x] Open in another player (VLC, MX Player)
- [x] Sleep timer
- [x] Auto-refresh sources on a schedule
- [x] Stream info overlay
- [x] Start on last channel option
- [x] Export a log file

## Built that wasn't on the original list

- [x] Playlist films and series show the country or language from the provider's group (a "DE" tag on posters, the group on the details page)
- [x] Providers' tiny decorative letters (superscripts, small capitals) shown as ordinary text
- [x] After an in-app update, VantaView opens again by itself (Android 9 and older, including Fire TV), or offers a tap-to-open notification
- [x] Open in browser: when add-ons only offer web-page links (Stremio's externalUrl) for a title, buttons open them in the device's browser
- [x] Playlists page shows each playlist's server address, username (Xtream), when it was added and last loaded; new playlists without a name are named after their server; playlists can be switched off (hidden everywhere and not refreshed) and on again
- [x] Sports TV page (tile on Home when an add-on has them): add-on rows of Stremio's "tv" and "channel" types; add-on links also accepted as a "…/configure" page address or a nuvio:// link
- [x] TV remote and text boxes: up and down move straight past a text box, and the keyboard only opens when OK is pressed on it
- [x] Screen size (Settings › Appearance): Auto fits the layout to the device and screen (scales up very wide TVs, down on very narrow phones, keeps huge system fonts in check), or Compact, Normal or Large
- [x] Refresh on Home: pull down on a phone, or the Refresh button; reloads playlists, TV guide, weather and Home rows
- [x] Live TV preview: first press plays a channel in a small window with now and next, second press goes full screen (switch in Settings › Playback)
- [x] Channel up and down follow the list a channel was opened from (Favourites, a category, search, the Home favourites row)
- [x] Add a playlist from a phone: QR code on Sources and in setup opens a form on the phone (provider message, M3U link or file, Xtream Codes) that sends it to the device over the home network
- [x] Picture-in-picture switch (Settings › Playback): turns off the PiP button and shrinking on Home
- [x] M3U links encrypted on the device; logins in them hidden in saved channel, film and guide addresses, and kept in the locked part of backups
- [x] Privacy page (docs/PRIVACY.md): where playlists and logins go, and every service the app connects to
- [x] Films and series from M3U playlists and Xtream Codes: their own rows on Movies and TV Series, matched on TMDB for details, and offered as "From your playlist" on matching titles
- [x] Play torrents without a debrid account (built-in engine, debrid tried first; shares only while playing and deletes the file on close; switch in Settings › Playback)
- [x] Languages: Spanish, French, German, Italian, Portuguese (Brazil), Dutch, Polish and Turkish. Follows the device, with a picker per profile in Settings › Appearance; film and series details from TMDB in the same language

- [x] TV Guide page in the sidebar and a TV Guide tile on Home, with an On now row
- [x] Tap a guide programme to play the channel with its Now playing info
- [x] Now and next under each channel in Live TV
- [x] TV guide for the Free TV channels (only the countries you pick)
- [x] Countries filter for big playlists, with divider and event-only channels handled
- [x] Back button goes back a page; press Back twice on Home to exit
- [x] Sidebar scrolls when the screen is short (phone on its side)
- [x] Follows the device's orientation, with portrait player and channel list on phones
- [x] Double tap the player or a channel for full screen
- [x] Greeting that follows the time of day, weather glance, location asked on first launch
- [x] Backup password, send to phone by QR code, automatic backups (keeps 5)
- [x] Debrid accounts for add-on streams
- [x] Playback settings: mini player on Home on/off, remember picture size
- [x] Parental control: hide adult categories everywhere, mark your own, lock Sources, PIN unlocks until the app closes
- [x] Light ripple on the loading screen, and a ripple burst from the profile you pick (still with animations off)
- [x] Appearance extras: Black (OLED) theme, custom accent colour, text size, sidebar auto-hide switch, loading screen switch
- [x] Updates can offer test builds (switch in Settings › Updates)
- [x] AI subtitles (beta): on-device captions or English translation with whisper.cpp, model downloaded when chosen
- [x] Storage: space used by type; clear pictures, TV guide and update downloads; TV guide refresh setting
- [x] Search page in the sidebar and on Home; Library page (Watchlist, Continue watching, Watched, Reminders); films marked watched when finished
- [x] Reminder pop-ups in the app, notifications when it is closed
- [x] Favourite star on the left of each channel
- [x] TV: number keys jump to a channel in the player; Fire TV steps for allowing updates; no location prompt on TVs
- [x] Release 0.7.0 published
- [x] Release 1.0 published, with a permanent download link (releases/latest/download/VantaView.apk)
- [x] Checked on Android TV 9 (Fire TV base) and Google TV 14 emulators with the remote
- [x] Looks (Settings › Appearance): VantaView, Glass, Broadcast, Wave and Classic, with previews; each changes colours, background, menu (Broadcast and Classic have a top menu on wide screens) and the TV Guide; appearance is saved per profile
- [x] What's new pop-up once after an update (from docs/WHATS-NEW.md, built into the app), with an on/off switch and a preview in Settings › Updates
- [x] Restore NuvioSync backups: add-ons, Watchlist, watched films and Continue watching (series carry on from the next episode)
- [x] Live TV Radio tab: radio stations (found by name, group or the playlist's radio mark) kept apart from TV channels
- [x] Movies and TV Series tiles: Watch now (Trending, Latest, Top rated), Genres, Decades, Themed and International, each opening a full page of titles; the app's own tile pictures
- [x] Console-style looks: Blades, Stack, Tiles, Fluent, Cross and Spotlight, each with its own Home dashboard on wide screens, plus its colours, background, menu, card corners and headings on every page
- [x] Stalker portals: films and series on the Movies and TV Series pages, the portal's own TV guide, and Smart Add spots a portal address with a MAC address; full MAG sign-in, portal logos, optional box details (credit: Stalker-Portal by Jitendra Unatti)
- [x] Release 1.0.1: fast non-debug builds (about 3 s to open on the TV emulators), logo shown on launch
- [x] Press and hold a poster (or the remote's Menu key) for Watchlist and Watched; an episode's "⋯" marks it or its whole season as watched

## Before release

- [ ] Works without Google Play Services (Fire TV)
- [ ] Tested on a 1 GB Fire TV Stick with a 50,000+ channel playlist
- [ ] Signing key backed up
- [ ] Privacy page published
- [ ] Credits complete (Free TV, TMDB, TheSportsDB, Open-Meteo, libraries) *(all credited except TheSportsDB, which comes with Sports)*

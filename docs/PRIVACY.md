# Privacy: where your playlist and logins go

VantaView has no server of its own. Your playlists, logins and watch history stay on your device. Nothing is sent to the developer, and there are no accounts, analytics, adverts or crash reports.

## Your playlists and logins

- **Stored on your device only**, in VantaView's private storage, which other apps can't read.
- **Encrypted logins.** Xtream Codes and portal passwords, and M3U playlist links, are encrypted with Android's own secure key store before they're saved. Playlists added before this was in place are encrypted the first time the updated app starts.
- **Hidden in saved addresses.** When an M3U link contains a username and password, they're also replaced with placeholders in every saved channel, film and TV guide address, and filled back in only when something plays.
- **No cloud copy.** Android's automatic cloud backup is switched off for VantaView, so none of this is copied to Google.

## Who VantaView connects to

VantaView connects straight from your device to each service below. Nothing passes through a VantaView server, because there isn't one.

| Service | When | What it's sent |
|---|---|---|
| Your playlist provider | Loading channels, films and series, and playing them | Your login for that provider, as any IPTV app sends it |
| TMDB (themoviedb.org) | Movies and TV Series pages, and opening a title | Film and series titles or IDs, never your login |
| Cinemeta (Stremio) | Film details when there's no TMDB key | Film and series IDs |
| Open-Meteo | Weather on Home, if it's on | Your town, or the device's rough location |
| IntroDB and SkipDB | Skip intro | The film's or episode's IMDb ID |
| Free TV list and epgshare01 | Only if you add the free channels | Nothing personal |
| GitHub | Checking for updates | Nothing personal |
| Cloudflare | Only when you run the internet speed test | Nothing personal |
| Add-ons, debrid services and Trakt | Only once you add or link them yourself | What each one needs to work (for example a debrid key or Trakt sign-in) |

Your playlist provider login is only ever sent to that provider.

## Backups and log files

- **Backups** are saved as a file on your device, or sent to your phone over your home Wi-Fi. Logins and M3U links in a backup are locked with a password you choose. Without it, the backup restores everything except logins and M3U links.
- **Log files** are only made when you choose Save a log file. Logins, keys and MAC addresses are blanked out before it's saved, and it's only shared if you attach it to a problem report yourself.

## Adding a playlist from your phone

The QR code on Sources opens a page served by VantaView itself, on your home network. What you type or pick on your phone goes straight from the phone to your TV over your Wi-Fi, never through the internet. The page only answers its own secret address, and stops after 15 minutes or when you leave the page.

## Reading a screenshot

When Smart Add reads a screenshot or photo of your provider's message, the text is read on your device (with Tesseract, an open-source text reader). The picture isn't sent anywhere, and it isn't kept.

## Two things to know

- **Plain `http://` providers.** Many IPTV providers use plain `http://` addresses. On those, the login travels unencrypted between your device and the provider, as it would in any app, so people on the same network or your internet provider could in principle see it. Ask your provider for an `https://` address if they have one.
- **Torrents.** If you play a torrent without a debrid account, other people sharing that torrent can see your IP address while it plays. VantaView stops sharing and deletes the file when you close the player. This can be turned off in Settings › Playback.

## Check for yourself

VantaView is free, open-source software (GPL-3.0). Anyone can read the code to see exactly what it does.

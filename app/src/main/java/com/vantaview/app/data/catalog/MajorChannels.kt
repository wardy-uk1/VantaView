// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.catalog

import com.vantaview.app.data.ChannelMerge

/**
 * The best-known channels of each country, most important first, for "Importance" order (our own short lists:
 * the main national channels, then the big news, sport and film ones). Matched by name like merging channels.
 * Channels not listed follow in their usual order.
 */
object MajorChannels {
    private val lists: Map<String, List<String>> = mapOf(
        "UK" to listOf(
            "BBC One", "BBC Two", "ITV1", "Channel 4", "Channel 5", "BBC Three", "BBC Four", "ITV2", "ITV3", "ITV4", "E4", "More4", "Film4",
            "Sky One", "Sky Showcase", "Sky Atlantic", "Sky Max", "Sky Witness", "Dave", "Gold", "W", "Comedy Central",
            "BBC News", "Sky News", "GB News", "CNN", "Sky Sports Main Event", "Sky Sports Premier League", "Sky Sports Football",
            "Sky Sports F1", "Sky Sports Cricket", "TNT Sports 1", "TNT Sports 2", "Premier Sports 1", "Eurosport 1",
            "Sky Cinema Premiere", "Sky Cinema Hits", "CBBC", "CBeebies",
        ),
        "IE" to listOf("RTÉ One", "RTÉ2", "Virgin Media One", "Virgin Media Two", "TG4", "RTÉ News", "Virgin Media Three"),
        "US" to listOf(
            "ABC", "CBS", "NBC", "FOX", "PBS", "The CW", "CNN", "Fox News", "MSNBC", "ESPN", "ESPN2", "FS1", "TNT", "TBS", "USA Network",
            "AMC", "FX", "HBO", "Showtime", "Discovery Channel", "History", "Nickelodeon", "Disney Channel", "Cartoon Network",
        ),
        "CA" to listOf("CBC", "CTV", "Global", "Citytv", "Radio-Canada", "TVA", "CBC News Network", "CTV News Channel", "TSN", "Sportsnet"),
        "AU" to listOf("ABC TV", "Seven", "Nine", "10", "SBS", "ABC News", "7mate", "9Go!", "Fox Sports", "Kayo"),
        "NZ" to listOf("TVNZ 1", "TVNZ 2", "Three", "Prime", "Sky Sport 1"),
        "DE" to listOf(
            "Das Erste", "ZDF", "RTL", "Sat.1", "ProSieben", "VOX", "kabel eins", "RTLZWEI", "3sat", "arte", "ZDFneo", "ONE",
            "n-tv", "WELT", "tagesschau24", "phoenix", "Sky Sport Bundesliga", "Sky Sport Top Event", "DAZN 1", "Sport1", "KiKA", "Super RTL",
        ),
        "AT" to listOf("ORF 1", "ORF 2", "ORF III", "ServusTV", "ATV", "PULS 4", "ORF Sport+"),
        "CH" to listOf("SRF 1", "SRF zwei", "SRF info", "RTS 1", "RTS 2", "RSI LA 1", "3+"),
        "FR" to listOf(
            "TF1", "France 2", "France 3", "Canal+", "France 5", "M6", "Arte", "C8", "W9", "TMC", "TFX", "NRJ 12", "France 4",
            "BFM TV", "CNews", "LCI", "franceinfo", "beIN Sports 1", "Canal+ Sport", "RMC Sport 1",
        ),
        "BE" to listOf("VRT 1", "Canvas", "VTM", "Play4", "La Une", "Tipik", "RTL-TVI", "Club RTL", "VRT NWS"),
        "NL" to listOf("NPO 1", "NPO 2", "NPO 3", "RTL 4", "RTL 5", "SBS6", "RTL 7", "Veronica", "Net5", "RTL 8", "NOS", "ESPN 1", "Ziggo Sport", "Viaplay TV"),
        "IT" to listOf(
            "Rai 1", "Rai 2", "Rai 3", "Rete 4", "Canale 5", "Italia 1", "LA7", "TV8", "Nove", "Rai News 24", "Sky TG24", "TGCOM24",
            "Sky Sport Uno", "DAZN", "Rai Sport", "Sky Cinema Uno",
        ),
        "ES" to listOf(
            "La 1", "La 2", "Antena 3", "Cuatro", "Telecinco", "laSexta", "24h", "Movistar Plus+", "Teledeporte", "DAZN LaLiga",
            "Movistar LaLiga", "Neox", "Nova", "Mega", "FDF", "Energy",
        ),
        "PT" to listOf("RTP1", "RTP2", "SIC", "TVI", "RTP3", "SIC Notícias", "CNN Portugal", "Sport TV1", "Benfica TV", "Eleven 1"),
        "PL" to listOf(
            "TVP1", "TVP2", "Polsat", "TVN", "TV4", "TV Puls", "TVN24", "Polsat News", "TVP Info", "TVP Sport", "Polsat Sport",
            "Canal+ Sport", "Eleven Sports 1", "TVN7", "TTV",
        ),
        "TR" to listOf("TRT 1", "ATV", "Show TV", "Star TV", "Kanal D", "FOX", "TV8", "NOW", "TRT Haber", "CNN Türk", "NTV", "beIN Sports 1", "TRT Spor", "S Sport"),
        "SE" to listOf("SVT1", "SVT2", "TV4", "Kanal 5", "TV3", "Sjuan", "TV6", "TV4 Sport", "Viaplay Sport"),
        "NO" to listOf("NRK1", "NRK2", "TV 2", "TVNorge", "TV3", "TV 2 Sport 1", "V Sport"),
        "DK" to listOf("DR1", "DR2", "TV 2", "TV3", "Kanal 5", "TV 2 News", "TV3 Sport"),
        "FI" to listOf("Yle TV1", "Yle TV2", "MTV3", "Nelonen", "Sub", "Yle Teema Fem", "Jim", "MTV Sport 1"),
        "GR" to listOf("ERT1", "ERT2", "Mega", "ANT1", "Alpha", "Star", "Skai", "Open", "Cosmote Sport 1", "Novasports 1"),
        "RO" to listOf("TVR 1", "TVR 2", "Pro TV", "Antena 1", "Kanal D", "Prima TV", "Digi24", "Antena 3 CNN", "Digi Sport 1", "Pro Arena"),
        "HR" to listOf("HRT 1", "HRT 2", "Nova TV", "RTL", "HRT 3", "N1", "Arena Sport 1", "HNTV"),
        "RS" to listOf("RTS 1", "RTS 2", "Pink", "Prva", "B92", "N1", "Arena Sport 1", "Sport Klub 1"),
        "BA" to listOf("BHT 1", "FTV", "RTRS", "OBN", "Hayat", "Face TV", "N1", "Arena Sport 1"),
        "SI" to listOf("TV SLO 1", "TV SLO 2", "POP TV", "Kanal A", "Planet TV", "Šport TV 1"),
        "BR" to listOf("Globo", "SBT", "Record", "Band", "RedeTV!", "TV Cultura", "GloboNews", "CNN Brasil", "SporTV", "ESPN"),
        "MX" to listOf("Las Estrellas", "Canal 5", "Azteca Uno", "Azteca 7", "Imagen TV", "Canal Once", "ESPN", "TUDN", "Fox Sports"),
        "AR" to listOf("Telefe", "El Trece", "América TV", "TV Pública", "Canal 9", "TN", "C5N", "TyC Sports", "ESPN", "TNT Sports"),
        "IN" to listOf("DD National", "Star Plus", "Zee TV", "Sony Entertainment Television", "Colors", "Star Sports 1", "Aaj Tak", "NDTV 24x7", "Republic TV"),
    )

    private val ranks: Map<String, Map<String, Int>> = lists.mapValues { (_, l) ->
        HashMap<String, Int>().also { m -> l.forEachIndexed { i, n -> m.putIfAbsent(ChannelMerge.nameKey(n), i) } }
    }

    /** Where a channel comes in its country's list (0 = most important), or null when it isn't listed. */
    fun rank(country: String?, name: String): Int? {
        val key = ChannelMerge.nameKey(TimeShift.strip(name))
        if (country != null) ranks[country]?.get(key)?.let { return it }
        return null
    }

    /** Countries that have a list (for the country order screen). */
    val countries: Set<String> get() = lists.keys
}

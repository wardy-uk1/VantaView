// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.ui

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.util.Locale

/**
 * The app's translations. The English text in the code is the key: assets/i18n/<language>.json maps each
 * English line to its translation. A key can hold placeholders ({0}, {1}…) that stand for names, numbers
 * and times, so "Season {0}" also translates "Season 3". Every piece of text shown with [Text] goes
 * through [tr]; text without a translation (channel names, titles) is shown as it is.
 */
object I18n {
    /** The languages VantaView is translated into, with their own names. */
    val languages = listOf(
        "en" to "English", "es" to "Español", "fr" to "Français", "de" to "Deutsch", "it" to "Italiano",
        "pt" to "Português (Brasil)", "nl" to "Nederlands", "pl" to "Polski", "tr" to "Türkçe",
        "ar" to "العربية",
    )

    /** The language in use, as a two-letter code. Reading it in a composable redraws on change. */
    var lang by mutableStateOf("en")
        private set

    /** The chosen language as a Locale, for day and month names. */
    val locale: Locale get() = if (lang == "pt") Locale("pt", "BR") else if (lang == "en") Locale.UK else Locale(lang)

    /** The language TMDB is asked for, or null for English. */
    val tmdbLanguage: String?
        get() = when (lang) {
            "en" -> null
            "pt" -> "pt-BR"
            // The generic form would give "ar-AR" (Argentina); TMDB's Arabic is "ar-SA".
            "ar" -> "ar-SA"
            else -> lang + "-" + lang.uppercase()
        }

    /** Whether the language in use reads right to left (Arabic, for now). */
    val isRtl: Boolean get() = lang == "ar"

    /** The device's own language, whatever the app is set to. */
    val deviceLang: String get() = Resources.getSystem().configuration.locales[0].language

    /** The language a profile's setting means: its own choice, or the device's if VantaView has it. */
    fun resolve(choice: String): String {
        val want = choice.ifBlank { deviceLang }
        return if (languages.any { it.first == want }) want else "en"
    }

    private class Tpl(val regex: Regex, val slots: List<Int>, val out: String)

    private var exact: Map<String, String> = emptyMap()
    /** The same lines in capitals, for headings the code shows upper-cased. */
    private var upper: Map<String, String> = emptyMap()
    private var byStart: Map<String, List<Tpl>> = emptyMap()
    private var byEnd: Map<String, List<Tpl>> = emptyMap()
    private var loose: List<Tpl> = emptyList()
    private var loaded: String? = null
    private val original: Locale = Locale.getDefault()
    private val cache = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 4000
    }

    /** Switches to the language for [choice] ("" follows the device). */
    @Synchronized
    fun use(context: Context, choice: String) {
        val l = resolve(choice)
        if (l == loaded) return
        val map = HashMap<String, String>()
        if (l != "en") runCatching {
            val o = JSONObject(context.assets.open("i18n/$l.json").bufferedReader().use { it.readText() })
            o.keys().forEach { k -> o.optString(k).takeIf { it.isNotEmpty() }?.let { map[k] = it } }
        }
        setTable(l, map)
    }

    /** Puts [map] (English → translation) in use as language [l]. Separate from [use] so tests can call it. */
    @Synchronized
    internal fun setTable(l: String, map: Map<String, String>) {
        val start = HashMap<String, MutableList<Tpl>>()
        val end = HashMap<String, MutableList<Tpl>>()
        val rest = ArrayList<Tpl>()
        // {0} stands for any text; {0#} only for a number, so "Channel {0#}" leaves a channel called "Channel One" alone.
        val slot = Regex("""\{(\d+)(#?)\}""")
        for ((k, v) in map) {
            if (!k.contains('{') || !slot.containsMatchIn(k)) continue
            val parts = slot.split(k)
            val found = slot.findAll(k).toList()
            val slots = found.map { it.groupValues[1].toInt() }
            val pattern = buildString {
                append('^')
                parts.forEachIndexed { i, p ->
                    append(Regex.escape(p))
                    if (i < found.size) append(if (found[i].groupValues[2] == "#") "(\\d+)" else "(.+?)")
                }
                append('$')
            }
            val t = Tpl(Regex(pattern, RegexOption.DOT_MATCHES_ALL), slots, v)
            val head = parts.first()
            val tail = parts.last()
            when {
                head.length >= 2 -> start.getOrPut(head.take(2)) { ArrayList() } += t
                tail.length >= 2 -> end.getOrPut(tail.takeLast(2)) { ArrayList() } += t
                else -> rest += t
            }
        }
        exact = map; byStart = start; byEnd = end; loose = rest
        val loc = if (l == "pt") Locale("pt", "BR") else Locale(l)
        upper = map.entries.filter { !it.key.contains('{') }.associate { it.key.uppercase(Locale.ROOT) to it.value.uppercase(loc) }
        synchronized(cache) { cache.clear() }
        loaded = l
        lang = l
        Locale.setDefault(if (l == "en") original else locale)
    }

    private fun core(s: String): String? {
        exact[s]?.let { return it }
        upper[s]?.let { return it }
        val cands = (byStart[s.take(2)].orEmpty()) + (byEnd[s.takeLast(2)].orEmpty()) + loose
        for (t in cands) {
            val m = t.regex.matchEntire(s) ?: continue
            val vals = HashMap<Int, String>()
            t.slots.forEachIndexed { i, n -> vals[n] = m.groupValues[i + 1] }
            return Regex("""\{(\d+)\}""").replace(t.out) { r -> vals[r.groupValues[1].toInt()] ?: r.value }
        }
        return null
    }

    private val edges = Regex("""^([^\p{L}\p{N}]*?)(\p{L}.*?)([\s.:…]*)$""", RegexOption.DOT_MATCHES_ALL)
    private val joins = Regex("""(\s*·\s*|\n+|\s*;\s*)""")

    /** [s] in the chosen language, or [s] itself when there's no translation. */
    fun tr(s: String): String {
        if (lang == "en" || s.length < 2) return s
        synchronized(cache) { cache[s] }?.let { return it }
        val r = piece(s) ?: run {
            val parts = joins.split(s)
            if (parts.size < 2) return@run null
            val seps = joins.findAll(s).map { it.value }.toList()
            var changed = false
            buildString {
                parts.forEachIndexed { i, p ->
                    val t = if (p.isBlank()) null else piece(p)
                    if (t != null) changed = true
                    append(t ?: p)
                    if (i < seps.size) append(seps[i])
                }
            }.takeIf { changed }
        } ?: s
        synchronized(cache) { cache[s] = r }
        return r
    }

    /** One piece of text: as it is, or without symbols and spaces around it. */
    private fun piece(s: String): String? {
        core(s)?.let { return it }
        val m = edges.matchEntire(s) ?: return null
        val (pre, mid, post) = m.destructured
        if (pre.isEmpty() && post.isEmpty()) return null
        core(mid + post)?.let { return pre + it }
        return core(mid)?.let { pre + it + post }
    }

    /** [template] translated, with {0}, {1}… filled from [args]. */
    fun tr(template: String, vararg args: Any?): String {
        val t = if (lang == "en") template else exact[template] ?: template
        return Regex("""\{(\d+)\}""").replace(t) { r -> args.getOrNull(r.groupValues[1].toInt())?.toString() ?: "" }
    }
}

/** Shorthand for [I18n.tr]. */
fun tr(s: String): String = I18n.tr(s)

/** Shorthand for [I18n.tr] with placeholders. */
fun tr(template: String, vararg args: Any?): String = I18n.tr(template, *args)

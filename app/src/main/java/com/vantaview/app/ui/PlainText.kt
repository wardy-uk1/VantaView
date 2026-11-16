// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.ui

/**
 * Providers often decorate group and title names with "tiny" letters and numbers (superscripts like ⁴ᴷ ³⁸⁴⁰ᴾ,
 * subscripts and small capitals). Many TV fonts draw those badly or at odd sizes, so they're shown as ordinary
 * letters and numbers: "DE - ²¹⁶⁰ᵖ ᴰⱽ ᴴᴰᴿ¹⁰/ᴾᴸᵁˢ" reads "DE - 2160p DV HDR10/PLUS".
 */
object PlainText {
    private const val FROM =
        "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾" + "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎" +
            "ᴬᴮᴰᴱᴳᴴᴵᴶᴷᴸᴹᴺᴼᴾᴿᵀᵁⱽᵂ" + "ᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐⁿᵒᵖʳˢᵗᵘᵛʷˣʸᶻ" +
            "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘʀꜱᴛᴜᴠᴡʏᴢ"
    private const val TO =
        "0123456789+-=()" + "0123456789+-=()" +
            "ABDEGHIJKLMNOPRTUVW" + "abcdefghijklmnoprstuvwxyz" +
            "ABCDEFGHIJKLMNOPRSTUVWYZ"
    private val map: Map<Char, Char> = FROM.indices.associate { FROM[it] to TO[it] }

    private val capitals: Set<Char> = (FROM.substring(30, 49) + FROM.substring(74)).toSet()

    fun of(s: String): String {
        if (s.none { it in map }) return s
        // Runs of tiny letters keep one case: providers mix them up ("ᴾᴸᵁˢ" has a small "s"), so a run with any
        // capital in it is shown in capitals ("PLUS").
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] !in map) { out.append(s[i]); i++; continue }
            var j = i
            while (j < s.length && s[j] in map) j++
            val run = s.substring(i, j)
            val plain = run.map { map.getValue(it) }.joinToString("")
            out.append(if (run.any { it in capitals }) plain.uppercase() else plain)
            i = j
        }
        return out.toString()
    }
}

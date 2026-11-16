// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.ui

/**
 * Providers often decorate group and title names with "tiny" letters and numbers (superscripts like ᴬᴷ ³⁸⁴⁰ᴾ,
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

    fun of(s: String): String {
        if (s.none { it in map }) return s
        return buildString(s.length) { s.forEach { c -> append(map[c] ?: c) } }
    }
}

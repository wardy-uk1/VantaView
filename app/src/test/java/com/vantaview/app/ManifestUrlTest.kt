// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import com.vantaview.app.data.addon.AddonRepository
import com.vantaview.app.data.addon.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestUrlTest {
    @Test fun configurePageBecomesManifest() {
        assertEquals("https://example.org/manifest.json", Manifest.normalizeUrl("https://example.org/configure"))
        assertEquals("https://example.org/abc/manifest.json", Manifest.normalizeUrl("https://example.org/abc/configure/"))
    }

    @Test fun appSchemes() {
        assertEquals("https://example.org/x/manifest.json", Manifest.normalizeUrl("stremio://example.org/x/manifest.json"))
        assertEquals("https://example.org/x/manifest.json", Manifest.normalizeUrl("nuvio://example.org/x/manifest.json"))
    }

    @Test fun plainAddressesUnchanged() {
        assertEquals("https://example.org/manifest.json", Manifest.normalizeUrl("example.org"))
        assertEquals("https://example.org/x/manifest.json", Manifest.normalizeUrl("https://example.org/x/manifest.json"))
    }

    @Test fun sportsTvTakesTvAndChannelRows() {
        assertTrue(AddonRepository.typeOk("tv", AddonRepository.TV))
        assertTrue(AddonRepository.typeOk("channel", AddonRepository.TV))
        assertFalse(AddonRepository.typeOk("movie", AddonRepository.TV))
        assertFalse(AddonRepository.typeOk("channel", "series"))
    }
}

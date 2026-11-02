package dev.omalauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechLanguageTest {
    // As Google's recognizers list them.
    private val google = listOf("en-US", "de-DE", "cmn-Hant-TW", "yue-Hant-HK", "cmn-Hans-CN")

    @Test fun mandarinIsListedUnderItsOwnCode() {
        assertEquals("cmn-Hans-CN", closestLanguage("zh", "", "zh-CN", listOf(google)))
    }

    @Test fun mandarinPickedAsCmnIsStillMandarin() {
        assertEquals("cmn-Hans-CN", closestLanguage("cmn", "", "zh-CN", listOf(google)))
    }

    @Test fun aChinesePhoneInTaiwanGetsItsRegion() {
        assertEquals("cmn-Hant-TW", closestLanguage("zh", "TW", "zh-CN", listOf(google)))
    }

    @Test fun cantoneseIsNotMandarin() {
        assertNull(closestLanguage("zh", "", "zh-CN", listOf(listOf("en-US", "yue-Hant-HK"))))
    }

    @Test fun plainTagsAndUnderscoresStillMatch() {
        assertEquals("zh-CN", closestLanguage("zh", "", "zh-CN", listOf(listOf("zh-TW", "zh-CN"))))
        assertEquals("zh_CN", closestLanguage("zh", "", "zh-CN", listOf(listOf("zh_TW", "zh_CN"))))
    }

    @Test fun anInstalledModelGoesBeforeTheMainRegion() {
        assertEquals("cmn-Hant-TW", closestLanguage("zh", "", "zh-CN", listOf(listOf("cmn-Hant-TW"), google)))
    }

    @Test fun thePhonesRegionGoesBeforeInstalledModels() {
        assertEquals("en-GB", closestLanguage("en", "GB", "en-US", listOf(listOf("en-US"), listOf("en-GB"))))
    }

    @Test fun anUnknownRegionGetsTheMainOneThenUsThenAny() {
        assertEquals("de-DE", closestLanguage("de", "CH", "de-DE", listOf(listOf("de-AT", "de-DE"))))
        assertEquals("en-US", closestLanguage("en", "DE", "en-US", listOf(listOf("en-GB", "en-US"))))
        assertEquals("es-US", closestLanguage("es", "", "es-ES", listOf(listOf("es-MX", "es-US"))))
        assertEquals("fr-CA", closestLanguage("fr", "", "fr-FR", listOf(listOf("fr-CA"))))
    }

    @Test fun nullWhenNoRecognizerHasTheLanguage() {
        assertNull(closestLanguage("zh", "", "zh-CN", listOf(listOf("en-US"), emptyList())))
    }

    @Test fun mandarinIsTriedUnderEverySpelling() {
        assertEquals(listOf("zh-CN", "cmn-Hans-CN", "zh-Hans-CN", "zh"), languageTags("zh-CN"))
    }

    @Test fun otherLanguagesHaveTheirMainRegionThenTheLanguage() {
        assertEquals(listOf("de-DE", "de"), languageTags("de-DE"))
        assertEquals(listOf("xx"), languageTags("xx"))
    }
}

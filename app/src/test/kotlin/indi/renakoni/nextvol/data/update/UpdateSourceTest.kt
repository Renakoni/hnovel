package indi.renakoni.nextvol.data.update

import org.junit.Assert.*
import org.junit.Test

class UpdateSourceTest {
    @Test fun keysOrderAndDefaultsRemainCompatible() {
        assertEquals(listOf("GitHub", "LnrAPI"), UpdatePlatform.entries.map { it.key })
        assertEquals(listOf("Release", "Development", "CI"), UpdateChannel.entries.map { it.key })
        assertSame(UpdatePlatform.LNR_API, UpdatePlatform.default)
        assertSame(UpdateChannel.DEVELOPMENT, UpdateChannel.default)
    }

    @Test fun everySavedSelectionResolvesTheExistingParserWithoutUiOrNetwork() {
        listOf(
            Triple("GitHub", "Release", GithubParser.ReleaseParser),
            Triple("GitHub", "Development", GithubParser.DevelopmentParser),
            Triple("GitHub", "CI", GithubParser.CIParser),
            Triple("LnrAPI", "Release", APIParser.StableParser),
            Triple("LnrAPI", "Development", APIParser.BetaParser),
            Triple("LnrAPI", "CI", APIParser.UnstableParser),
        ).forEach { (platform, channel, expected) ->
            assertSame(expected, UpdatePlatform.fromKey(platform).parserFor(UpdateChannel.fromKey(channel)))
        }
    }

    @Test fun unknownAndEmptyKeysKeepTheExistingLookupFailure() {
        for (key in listOf("", "github", "LNRAPI", "old-platform")) {
            val failure = assertThrows(NoSuchElementException::class.java) { UpdatePlatform.fromKey(key) }
            assertEquals("OptionWithValue '$key' not found", failure.message)
        }
        for (key in listOf("", "release", "development", "ci", "old-channel")) {
            val failure = assertThrows(NoSuchElementException::class.java) { UpdateChannel.fromKey(key) }
            assertEquals("OptionWithValue '$key' not found", failure.message)
        }
    }
}

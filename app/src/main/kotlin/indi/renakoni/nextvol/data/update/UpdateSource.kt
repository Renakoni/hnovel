package indi.renakoni.nextvol.data.update

enum class UpdateChannel(val key: String) {
    RELEASE("Release"),
    DEVELOPMENT("Development"),
    CI("CI");

    companion object {
        val default = DEVELOPMENT

        fun fromKey(key: String): UpdateChannel = entries.firstOrNull { it.key == key }
            ?: throw NoSuchElementException("OptionWithValue '$key' not found")
    }
}

enum class UpdatePlatform(val key: String) {
    GITHUB("GitHub"),
    LNR_API("LnrAPI");

    fun parserFor(channel: UpdateChannel): UpdateParser = when (this) {
        GITHUB -> when (channel) {
            UpdateChannel.RELEASE -> GithubParser.ReleaseParser
            UpdateChannel.DEVELOPMENT -> GithubParser.DevelopmentParser
            UpdateChannel.CI -> GithubParser.CIParser
        }
        LNR_API -> when (channel) {
            UpdateChannel.RELEASE -> APIParser.StableParser
            UpdateChannel.DEVELOPMENT -> APIParser.BetaParser
            UpdateChannel.CI -> APIParser.UnstableParser
        }
    }

    companion object {
        val default = LNR_API

        // The check failure phase exposes this legacy lookup message to the user.
        fun fromKey(key: String): UpdatePlatform = entries.firstOrNull { it.key == key }
            ?: throw NoSuchElementException("OptionWithValue '$key' not found")
    }
}

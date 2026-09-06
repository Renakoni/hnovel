package io.nightfish.potatoautoproxy

data class Proxy(
    val host: String,
    val port: Int
) {
    companion object {
        fun fromString(url: String): Proxy {
            val things = url
                .reversed()
                .split(":", limit = 2)
            return Proxy(things[1].reversed(), things[0].reversed().toInt())
        }
    }
}
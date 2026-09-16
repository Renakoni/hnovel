package indi.renakoni.nextvol.utils

object FormattingSettings {
    @Volatile var dateFormat: String = "numeric"
        internal set

    @Volatile var dateShowYear: Boolean = true
        internal set

    @Volatile var dateOrder: String = "auto"
        internal set

    @Volatile var useRelativeTime: Boolean = true
        internal set
}

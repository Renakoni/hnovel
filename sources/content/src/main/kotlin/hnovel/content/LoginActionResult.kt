package hnovel.content

/** Host-owned reading position. Source identity and stored book/chapter state come from the panel's RuleSource. */
data class LoginReadingContext(val bookId: String, val chapterId: String?, val readingProgress: Float = 0f) {
    init {
        require(bookId.isNotBlank() && bookId.length <= 8192)
        require(chapterId == null || chapterId.isNotBlank() && chapterId.length <= 8192)
        require(readingProgress.isFinite() && readingProgress in 0f..1f)
    }
}

/** Refreshes are deferred until the user action succeeds and stay bound to the captured reading context. */
enum class LoginRefreshTarget { BookInformation, Directory, Content }
data class LoginActionResult(val refreshTargets: Set<LoginRefreshTarget> = emptySet())

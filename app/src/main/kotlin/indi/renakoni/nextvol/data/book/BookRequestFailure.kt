package indi.renakoni.nextvol.data.book

import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.error.WebRequestErrorKind

internal fun bookRequestFailureReason(error: WebRequestError?): String = when (error?.kind) {
    WebRequestErrorKind.AuthenticationRequired -> "authentication_required"
    WebRequestErrorKind.VerificationRequired -> "verification_required"
    WebRequestErrorKind.SourceUnavailable -> "source_unavailable"
    else -> "source_request_failed"
}

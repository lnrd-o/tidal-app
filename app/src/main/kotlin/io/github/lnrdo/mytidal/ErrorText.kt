package io.github.lnrdo.mytidal

import android.content.Context
import androidx.annotation.StringRes
import io.github.lnrdo.mytidal.core.Problem
import io.github.lnrdo.mytidal.core.ProtectedStreamException
import io.github.lnrdo.mytidal.core.ServiceException
import java.io.IOException

/** The translated sentence for each [Problem]. The compiler rejects a new problem that has no sentence. */
@StringRes
internal fun Problem.textRes(): Int = when (this) {
    Problem.SIGN_IN_EXPIRED -> R.string.error_sign_in_expired
    Problem.SIGN_IN_WRONG_CALLBACK -> R.string.error_sign_in_wrong_callback
    Problem.SIGN_IN_OTHER_ATTEMPT -> R.string.error_sign_in_other_attempt
    Problem.SIGN_IN_DECLINED -> R.string.error_sign_in_declined
    Problem.SIGN_IN_NO_CODE -> R.string.error_sign_in_no_code
    Problem.SIGN_IN_USED -> R.string.error_sign_in_used
    Problem.SIGN_IN_CONFIG_REJECTED -> R.string.error_sign_in_config_rejected
    Problem.SIGN_IN_FAILED -> R.string.error_sign_in_failed
    Problem.SIGN_IN_REQUIRED -> R.string.error_sign_in_required
    Problem.SESSION_EXPIRED -> R.string.error_session_expired
    Problem.SESSION_NOT_RENEWED -> R.string.error_session_not_renewed
    Problem.LOSSY_AUDIO -> R.string.error_lossy_audio
    Problem.PREVIEW_ONLY -> R.string.error_preview_only
    Problem.STREAM_INVALID -> R.string.error_stream_invalid
    Problem.STREAM_UNSUPPORTED -> R.string.error_stream_unsupported
    Problem.PROTECTED_STREAM -> R.string.error_protected_stream
    Problem.REFRESH_PLAYLIST -> R.string.error_refresh_playlist
    Problem.PLAYLIST_CHANGED, Problem.HTTP_CONFLICT -> R.string.error_playlist_changed
    Problem.UNREADABLE_RESPONSE -> R.string.error_unreadable_response
    Problem.HTTP_FORBIDDEN -> R.string.error_http_forbidden
    Problem.HTTP_NOT_FOUND -> R.string.error_http_not_found
    Problem.HTTP_RATE_LIMIT -> R.string.error_http_rate_limit
    Problem.HTTP_SERVER -> R.string.error_http_server
    Problem.HTTP_OTHER -> R.string.error_http_other
    Problem.SCROBBLE_TOKEN_MISSING -> R.string.error_scrobble_token_missing
    Problem.SCROBBLE_TOKEN_REJECTED -> R.string.error_scrobble_token_rejected
    Problem.SCROBBLE_LISTEN_REJECTED -> R.string.error_scrobble_listen_rejected
    Problem.SCROBBLE_CHECK_FAILED -> R.string.error_scrobble_check_failed
    Problem.SCROBBLE_ADDRESS_INVALID -> R.string.error_scrobble_address_invalid
    Problem.NOT_AVAILABLE_OFFLINE -> R.string.error_not_available_offline
    Problem.TRACK_MISSING -> R.string.error_track_missing
    Problem.TRACK_UNAVAILABLE -> R.string.error_track_unavailable
    Problem.BACKUP_INVALID -> R.string.error_backup_invalid
    Problem.BACKUP_TOO_NEW -> R.string.error_backup_too_new
}

/** A message for the user in the language of the device. */
fun Context.problemText(error: Throwable): String = when (error) {
    is ServiceException -> when (error.problem) {
        Problem.HTTP_OTHER, Problem.SCROBBLE_LISTEN_REJECTED, Problem.SCROBBLE_CHECK_FAILED -> getString(error.problem.textRes(), error.status)
        else -> getString(error.problem.textRes())
    }
    is ProtectedStreamException -> getString(Problem.PROTECTED_STREAM.textRes())
    is IOException -> getString(R.string.error_connection)
    else -> getString(R.string.error_generic)
}

/** Download failures are kept in the work data as a code, so that they appear in the language of the moment. */
fun downloadErrorCode(error: Throwable): String = when (error) {
    is ServiceException -> "problem:${error.problem.name}:${error.status}"
    is ProtectedStreamException -> "problem:${Problem.PROTECTED_STREAM.name}:0"
    is io.github.lnrdo.mytidal.data.StorageNotAllowedException -> "storage"
    is IOException -> "interrupted"
    else -> "save"
}

fun Context.downloadErrorText(code: String?): String {
    if (code != null && code.startsWith("problem:")) {
        val parts = code.removePrefix("problem:").split(":")
        val problem = Problem.entries.firstOrNull { it.name == parts[0] }
        if (problem != null) return problemText(ServiceException(parts.getOrNull(1)?.toIntOrNull() ?: 0, parts[0], problem))
    }
    return when (code) {
        "storage" -> getString(R.string.download_error_storage)
        "interrupted" -> getString(R.string.download_error_interrupted)
        "save" -> getString(R.string.download_error_save)
        else -> getString(R.string.download_failed)
    }
}

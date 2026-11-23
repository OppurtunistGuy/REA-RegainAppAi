package app.steady.feature.settings

import androidx.annotation.StringRes
import app.steady.R
import app.steady.remote.ApiException
import app.steady.remote.NoKeyHolderLinked
import app.steady.remote.NotSignedIn

/** Maps remote failures to plain, calm copy. Shared by Settings, Partner and Paywall. */
@StringRes
fun accountErrorText(e: Throwable): Int = when (e) {
    is NotSignedIn -> R.string.acct_err_signed_out
    is NoKeyHolderLinked -> R.string.partner_linked_none
    is ApiException.Network -> R.string.acct_err_network
    is ApiException.Unauthorized -> if (e.code == "invalid_code") R.string.acct_err_code else R.string.acct_err_signed_out
    is ApiException.NotFound -> R.string.acct_err_not_found
    is ApiException.Conflict -> when {
        e.isKeyHolderLimit -> R.string.partner_err_keyholder_limit
        e.code == "purchase_in_use" -> R.string.acct_err_purchase_in_use
        else -> R.string.acct_err_conflict
    }
    is ApiException.Server -> when (e.code) {
        "otp_not_configured", "otp_send_failed" -> R.string.acct_err_otp_unavailable
        "billing_unavailable", "billing_not_configured" -> R.string.acct_err_billing_unavailable
        else -> R.string.acct_err_server
    }
    is ApiException.Rejected -> when {
        e.status == 429 -> R.string.acct_err_rate_limited
        e.status == 410 -> R.string.acct_err_invite_gone
        e.code == "self_invite" -> R.string.acct_err_self_invite
        e.code == "purchase_invalid" -> R.string.acct_err_purchase_invalid
        e.code == "protected_app" -> R.string.acct_err_protected_app
        else -> R.string.acct_err_invalid
    }
    else -> R.string.acct_err_server
}

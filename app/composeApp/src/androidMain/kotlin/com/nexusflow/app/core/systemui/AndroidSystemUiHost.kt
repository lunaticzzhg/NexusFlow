package com.nexusflow.app.core.systemui

import android.app.Activity
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CancellationException

/** Activity-owned executor for the existing Android Google Credential Manager interaction. */
internal class AndroidSystemUiHost {
    private var activity: Activity? = null

    fun attach(value: Activity) {
        val current = activity
        check(current == null || current === value) {
            "AndroidSystemUiHost is already attached to a different activity."
        }
        activity = value
    }

    fun detach() {
        activity = null
    }

    suspend fun execute(request: GoogleSignInRequest): GoogleSignInResult {
        val foregroundActivity = activity ?: return GoogleSignInResult.Unavailable(request.id)
        if (request.serverClientId.isBlank()) return GoogleSignInResult.Unavailable(request.id)

        return try {
            val result =
                CredentialManager
                    .create(foregroundActivity)
                    .getCredential(
                        context = foregroundActivity,
                        request =
                            GetCredentialRequest.Builder()
                                .addCredentialOption(
                                    GetSignInWithGoogleOption.Builder(request.serverClientId).build(),
                                ).build(),
                    )
            result.credential.toGoogleSignInResult(request.id)
        } catch (_: GetCredentialCancellationException) {
            GoogleSignInResult.Cancelled(request.id)
        } catch (_: NoCredentialException) {
            GoogleSignInResult.Cancelled(request.id)
        } catch (exception: GetCredentialException) {
            Log.e(
                LOG_TAG,
                "google_credential_request_failed exceptionClass=${exception.javaClass.simpleName} " +
                    "credentialType=${exception.type}",
            )
            GoogleSignInResult.Failed(request.id)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Log.e(
                LOG_TAG,
                "google_credential_request_failed exceptionClass=${exception.javaClass.simpleName}",
            )
            GoogleSignInResult.Failed(request.id)
        }
    }
}

private fun androidx.credentials.Credential.toGoogleSignInResult(requestId: SystemUiRequestId): GoogleSignInResult =
    when {
        this is CustomCredential && type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL -> {
            runCatching { GoogleIdTokenCredential.createFrom(data).idToken }
                .fold(
                    onSuccess = { idToken -> GoogleSignInResult.Success(requestId, idToken) },
                    onFailure = { exception ->
                        Log.e(
                            LOG_TAG,
                            "google_credential_parse_failed exceptionClass=${exception.javaClass.simpleName}",
                        )
                        GoogleSignInResult.Failed(requestId)
                    },
                )
        }
        else -> GoogleSignInResult.Failed(requestId)
    }

private const val LOG_TAG = "AndroidSystemUiHost"

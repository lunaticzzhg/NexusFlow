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
import kotlinx.coroutines.sync.Mutex

class AndroidSystemUiGateway(
    private val activity: Activity,
) : SystemUiGateway {
    private val activeRequest = Mutex()
    private val credentialManager = CredentialManager.create(activity)

    override suspend fun requestGoogleSignIn(request: GoogleSignInRequest): GoogleSignInResult {
        if (request.serverClientId.isBlank() || !activeRequest.tryLock()) {
            return GoogleSignInResult.Unavailable(request.id)
        }

        return try {
            val result =
                credentialManager.getCredential(
                    context = activity,
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
        } catch (exception: Exception) {
            Log.e(
                LOG_TAG,
                "google_credential_request_failed exceptionClass=${exception.javaClass.simpleName}",
            )
            GoogleSignInResult.Failed(request.id)
        } finally {
            activeRequest.unlock()
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

private const val LOG_TAG = "AndroidSystemUiGateway"

package com.nexusflow.app.core.systemui

/** Google is intentionally not hosted on iOS in this delivery. */
class IosSystemUiGateway : SystemUiGateway {
    override suspend fun requestGoogleSignIn(request: GoogleSignInRequest): GoogleSignInResult = GoogleSignInResult.Unavailable(request.id)
}

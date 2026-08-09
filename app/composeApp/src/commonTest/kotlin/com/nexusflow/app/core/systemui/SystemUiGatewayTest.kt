package com.nexusflow.app.core.systemui

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemUiGatewayTest {
    @Test
    fun hostCompletionResolvesTheMatchingGoogleSignInRequest() =
        runTest {
            val gateway = DefaultSystemUiGateway()
            val request = GoogleSignInRequest(SystemUiRequestId("first"), "client-id")
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { gateway.requestGoogleSignIn(request) }

            assertEquals(request, gateway.requests.first())
            val result = GoogleSignInResult.Success(request.id, "id-token")

            assertTrue(gateway.complete(result))
            assertEquals(result, waiting.await())
        }

    @Test
    fun secondGoogleSignInRequestIsUnavailableWhileAnotherIsActive() =
        runTest {
            val gateway = DefaultSystemUiGateway()
            val firstRequest = GoogleSignInRequest(SystemUiRequestId("first"), "client-id")
            val first = async(start = CoroutineStart.UNDISPATCHED) { gateway.requestGoogleSignIn(firstRequest) }
            assertEquals(firstRequest, gateway.requests.first())

            val second = gateway.requestGoogleSignIn(GoogleSignInRequest(SystemUiRequestId("second"), "client-id"))

            assertEquals(GoogleSignInResult.Unavailable(SystemUiRequestId("second")), second)
            gateway.complete(GoogleSignInResult.Cancelled(firstRequest.id))
            assertEquals(GoogleSignInResult.Cancelled(firstRequest.id), first.await())
        }

    @Test
    fun callerCancellationNotifiesTheHostAndClearsTheActiveRequest() =
        runTest {
            val gateway = DefaultSystemUiGateway()
            val request = GoogleSignInRequest(SystemUiRequestId("first"), "client-id")
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { gateway.requestGoogleSignIn(request) }
            assertEquals(request, gateway.requests.first())
            val cancellation = async(start = CoroutineStart.UNDISPATCHED) { gateway.cancellations.first() }

            waiting.cancelAndJoin()

            assertEquals(request.id, cancellation.await())
            assertFalse(gateway.isActive(request.id))
        }

    @Test
    fun hostCancellationCompletesTheActiveRequestAsCancelled() =
        runTest {
            val gateway = DefaultSystemUiGateway()
            val request = GoogleSignInRequest(SystemUiRequestId("first"), "client-id")
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { gateway.requestGoogleSignIn(request) }
            assertEquals(request, gateway.requests.first())

            gateway.cancelActive()

            assertEquals(GoogleSignInResult.Cancelled(request.id), waiting.await())
            assertFalse(gateway.complete(GoogleSignInResult.Cancelled(request.id)))
        }

    @Test
    fun staleResultCannotCompleteANewerGoogleSignInRequest() =
        runTest {
            val gateway = DefaultSystemUiGateway()
            val firstRequest = GoogleSignInRequest(SystemUiRequestId("first"), "client-id")
            val first = async(start = CoroutineStart.UNDISPATCHED) { gateway.requestGoogleSignIn(firstRequest) }
            assertEquals(firstRequest, gateway.requests.first())
            gateway.cancelActive()
            assertEquals(GoogleSignInResult.Cancelled(firstRequest.id), first.await())

            val secondRequest = GoogleSignInRequest(SystemUiRequestId("second"), "client-id")
            val second = async(start = CoroutineStart.UNDISPATCHED) { gateway.requestGoogleSignIn(secondRequest) }
            assertEquals(secondRequest, gateway.requests.first())

            assertFalse(gateway.complete(GoogleSignInResult.Success(firstRequest.id, "old-token")))
            val secondResult = GoogleSignInResult.Success(secondRequest.id, "new-token")
            assertTrue(gateway.complete(secondResult))
            assertEquals(secondResult, second.await())
        }
}

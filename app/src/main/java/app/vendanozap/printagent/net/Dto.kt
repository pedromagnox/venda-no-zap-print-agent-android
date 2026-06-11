package app.vendanozap.printagent.net

import kotlinx.serialization.Serializable

@Serializable
data class ExchangeRequest(
    val refreshToken: String,
    val hostname: String? = null,
    val os: String? = null,
    val agentVersion: String? = null,
    val machineIdHash: String? = null,
)

@Serializable
data class StoreDto(val id: String, val name: String)

@Serializable
data class ExchangeResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val storeId: String,
    val store: StoreDto? = null,
)

@Serializable
data class ClaimLeaseRequest(val max: Int = 5)

@Serializable
data class QueueItem(
    val id: String,
    val orderId: String,
    val reason: String? = null,
    val attempts: Int = 0,
    val paperWidth: String? = null,
    val paperWidthMm: Int? = null,
    val bytesBase64: String,
)

@Serializable
data class ClaimLeaseResponse(val items: List<QueueItem> = emptyList())

@Serializable
data class AckRequest(val durationMs: Long? = null)

@Serializable
data class ReleaseRequest(
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val retry: Boolean = true,
)

@Serializable
data class FcmTokenRequest(val token: String, val platform: String = "android")

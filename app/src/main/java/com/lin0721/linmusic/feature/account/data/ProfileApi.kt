package com.lin0721.linmusic.feature.account.data

import com.lin0721.linmusic.core.model.EmptyBody
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

interface ProfileApi {

    @POST("/weapi/v1/user/detail/{uid}")
    suspend fun getProfile(
        @Path("uid") uid: Long,
        @Body body: EmptyBody = EmptyBody()
    ): ProfileDetailResponse

    @POST("/weapi/user/profile/update")
    suspend fun updateProfile(
        @Body body: ProfileUpdateRequest
    ): ProfileUpdateResponse
}

@Serializable
data class ProfileDetailResponse(
    val code: Int = 0,
    val profile: ProfilePayload? = null
) {
    val isSuccess: Boolean get() = code == 200
}

/** Nullable fields reflect the partial profile payload returned by the detail endpoint. */
@Serializable
data class ProfilePayload(
    val userId: Long = 0L,
    val nickname: String = "",
    val avatarUrl: String = "",
    val signature: String = "",
    val gender: Int? = null,
    val birthday: Long? = null,
    val province: Int? = null,
    val city: Int? = null
)

@Serializable
data class ProfileUpdateRequest(
    val nickname: String,
    val signature: String,
    val gender: Int,
    val birthday: Long,
    val province: Int,
    val city: Int
)

@Serializable
data class ProfileUpdateResponse(
    val code: Int = 0,
    val message: String? = null
) {
    val isSuccess: Boolean get() = code == 200
}

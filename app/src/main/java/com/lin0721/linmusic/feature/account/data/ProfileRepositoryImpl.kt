package com.lin0721.linmusic.feature.account.data

import com.lin0721.linmusic.core.network.apiFlow
import com.lin0721.linmusic.core.network.AppError
import com.lin0721.linmusic.feature.account.domain.UserProfileDetails
import kotlinx.coroutines.flow.Flow

class ProfileRepositoryImpl(
    private val apiService: ProfileApi
) : ProfileRepository {

    override fun getProfile(uid: Long): Flow<Result<UserProfileDetails>> = apiFlow(
        request = {
            require(uid > 0L) { "user id must be positive" }
            apiService.getProfile(uid)
        },
        isSuccess = { it.isSuccess },
        code = { it.code },
        transform = { response ->
            val payload = response.profile ?: throw AppError.ParseError
            if (payload.userId != uid) throw AppError.ParseError
            UserProfileDetails(
                userId = payload.userId,
                nickname = payload.nickname,
                avatarUrl = payload.avatarUrl,
                signature = payload.signature,
                gender = payload.gender,
                birthday = payload.birthday,
                province = payload.province,
                city = payload.city
            )
        }
    )

    override fun updateProfile(
        profile: UserProfileDetails,
        nickname: String,
        signature: String
    ): Flow<Result<Unit>> = apiFlow(
        request = {
            check(profile.isEditable) { "profile is missing fields required for update" }
            apiService.updateProfile(
                ProfileUpdateRequest(
                    nickname = nickname,
                    signature = signature,
                    gender = checkNotNull(profile.gender),
                    birthday = checkNotNull(profile.birthday),
                    province = checkNotNull(profile.province),
                    city = checkNotNull(profile.city)
                )
            )
        },
        isSuccess = { it.isSuccess },
        code = { it.code },
        msg = { it.message },
        transform = { Unit }
    )
}

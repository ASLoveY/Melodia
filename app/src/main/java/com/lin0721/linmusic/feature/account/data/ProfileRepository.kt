package com.lin0721.linmusic.feature.account.data

import com.lin0721.linmusic.feature.account.domain.UserProfileDetails
import kotlinx.coroutines.flow.Flow

interface ProfileRepository {
    fun getProfile(uid: Long): Flow<Result<UserProfileDetails>>

    fun updateProfile(
        profile: UserProfileDetails,
        nickname: String,
        signature: String
    ): Flow<Result<Unit>>
}

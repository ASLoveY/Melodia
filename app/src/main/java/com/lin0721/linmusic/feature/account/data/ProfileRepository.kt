package com.lin0721.linmusic.feature.account.data

import com.lin0721.linmusic.feature.account.domain.UserProfileDetails
import com.lin0721.linmusic.core.auth.UserSessionTag
import kotlinx.coroutines.flow.Flow

interface ProfileRepository {
    fun getProfile(uid: Long, sessionTag: UserSessionTag? = null): Flow<Result<UserProfileDetails>>

    fun updateProfile(
        profile: UserProfileDetails,
        nickname: String,
        signature: String,
        sessionTag: UserSessionTag
    ): Flow<Result<Unit>>
}

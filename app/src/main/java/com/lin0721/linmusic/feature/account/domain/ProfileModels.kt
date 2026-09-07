package com.lin0721.linmusic.feature.account.domain

/**
 * Account profile fields used by the profile editor.
 *
 * The demographic fields are nullable because the detail endpoint can omit them. The profile is
 * still useful for display in that case, but it must not be submitted until every field required by
 * the update endpoint has been loaded.
 */
data class UserProfileDetails(
    val userId: Long,
    val nickname: String,
    val avatarUrl: String,
    val signature: String,
    val gender: Int?,
    val birthday: Long?,
    val province: Int?,
    val city: Int?
) {
    val isEditable: Boolean
        get() = userId > 0L &&
            gender != null &&
            birthday != null &&
            province != null &&
            city != null
}

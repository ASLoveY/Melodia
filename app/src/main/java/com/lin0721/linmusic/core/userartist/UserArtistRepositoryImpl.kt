package com.lin0721.linmusic.core.userartist

import com.lin0721.linmusic.core.model.ArtistInfo
import com.lin0721.linmusic.core.network.apiFlow
import kotlinx.coroutines.flow.Flow

class UserArtistRepositoryImpl(
    private val apiService: UserArtistApi
) : UserArtistRepository {
    // An empty collection remains empty. Recommendations are not user subscriptions.
    override fun getFavoriteArtists(): Flow<Result<List<ArtistInfo>>> = apiFlow(
        request = { apiService.getArtistSublist() },
        isSuccess = { it.code == 200 },
        code = { it.code },
        transform = { response ->
            response.data.map { artist ->
                ArtistInfo(
                    id = artist.id,
                    name = artist.name,
                    avatarUrl = artist.img1v1Url.takeIf { it.isNotBlank() } ?: artist.picUrl
                )
            }
        }
    )
}

package com.lin0721.linmusic.feature.home.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lin0721.linmusic.core.auth.UserPreferences
import com.lin0721.linmusic.core.auth.UserProfile
import com.lin0721.linmusic.core.auth.SessionChangedException
import com.lin0721.linmusic.core.contentfilter.ContentFilter
import com.lin0721.linmusic.core.network.AppError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class DailyRecommendRepositoryTest {
    private val prefs = UserPreferences(ApplicationProvider.getApplicationContext<Context>())
    @Test fun guestHasLoginStateAndAuthenticatedResponseCanBeEmptyOrPopulated() = runBlocking {
        assumeTrue("Never overwrite a real user's session", prefs.currentSessionTag() == null)
        var calls = 0
        var response = DailyRecommendSongsResponse(200, DailyRecommendData(emptyList()))
        val api = Proxy.newProxyInstance(HomeApi::class.java.classLoader, arrayOf(HomeApi::class.java)) { _, method, args ->
            check(method.name in setOf("getDailyRecommendSongs", "getLegacyDailyRecommendSongs"))
            assertNotNull(args[1]); calls++; response
        } as HomeApi
        val repository = HomeRepositoryImpl(api, ContentFilter(flowOf(emptySet())), prefs)
        assertEquals(AppError.Unauthorized, repository.getDailyRecommendSongs().first().exceptionOrNull())
        assertEquals(0, calls)
        try {
            prefs.saveCookies("fixture=local-only"); prefs.saveUserProfile(UserProfile(99, "测试会话", ""))
            assertEquals(emptyList<DailySong>(), repository.getDailyRecommendSongs().first().getOrThrow())
            response = DailyRecommendSongsResponse(200, DailyRecommendData(listOf(DailySong(42, "日推测试"))))
            assertEquals(42L, repository.getDailyRecommendSongs().first().getOrThrow().single().id)
            response = DailyRecommendSongsResponse(200)
            val error = repository.getDailyRecommendSongs().first().exceptionOrNull()
            assertTrue(error is AppError.BizError)
        } finally { prefs.clearUserProfile() }
    }
}

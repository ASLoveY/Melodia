package com.lin0721.linmusic.core.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileSessionPersistenceTest {
    private lateinit var preferences: UserPreferences
    private var previousCookies: String? = null
    private var previousProfile: UserProfile? = null

    @Before
    fun prepare() = runBlocking {
        preferences = UserPreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        previousCookies = preferences.cookies.first()
        previousProfile = preferences.userProfile.first()
        preferences.saveCookies("instrumented-profile-session")
        preferences.saveUserProfile(UserProfile(42, "Original", "original-avatar", "Original signature"))
    }

    @After
    fun restore() = runBlocking {
        preferences.clearUserProfile()
        previousCookies?.let { preferences.saveCookies(it) }
        previousProfile?.let { preferences.saveUserProfile(it) }
        Unit
    }

    @Test
    fun profileSavePersistsSignatureAndInvalidatesOldRevision() = runBlocking {
        val revision = checkNotNull(preferences.sessionRevisionForUser(42))
        assertTrue(preferences.saveProfileForSession(42, revision, UserProfile(42, "Updated", "", "Updated signature")))
        assertEquals(UserProfile(42, "Updated", "original-avatar", "Updated signature"), preferences.userProfile.first())
        assertFalse(preferences.saveProfileForSession(42, revision, UserProfile(42, "Stale", "", "Stale signature")))
        assertEquals("Updated", preferences.userProfile.first()?.nickname)
    }

    @Test
    fun accountSwitchRejectsEarlierProfileSave() = runBlocking {
        val revision = checkNotNull(preferences.sessionRevisionForUser(42))
        preferences.clearUserProfile()
        preferences.saveCookies("instrumented-second-session")
        val second = UserProfile(99, "Second", "second-avatar", "Second signature")
        preferences.saveUserProfile(second)

        assertFalse(preferences.saveProfileForSession(42, revision, UserProfile(42, "Stale", "", "Stale signature")))
        assertEquals(second, preferences.userProfile.first())
    }
}

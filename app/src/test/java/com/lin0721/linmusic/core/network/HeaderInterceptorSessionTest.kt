package com.lin0721.linmusic.core.network

import com.lin0721.linmusic.core.auth.SessionChangedException
import com.lin0721.linmusic.core.auth.UserSessionSnapshot
import com.lin0721.linmusic.core.auth.UserSessionTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HeaderInterceptorSessionTest {

    @Test
    fun taggedRequestUsesCookieFromTheMatchingSnapshot() {
        val tag = UserSessionTag(uid = 42L, revision = 7L)

        assertEquals(
            "cookie-a",
            resolveTaggedSessionCookie(
                tag,
                UserSessionSnapshot(tag = tag, cookies = "cookie-a")
            )
        )
    }

    @Test(expected = SessionChangedException::class)
    fun changedSessionIsRejectedBeforeDispatch() {
        resolveTaggedSessionCookie(
            UserSessionTag(uid = 42L, revision = 7L),
            UserSessionSnapshot(
                tag = UserSessionTag(uid = 99L, revision = 8L),
                cookies = "cookie-b"
            )
        )
    }

    @Test(expected = SessionChangedException::class)
    fun sameAccountWithNewRevisionIsRejected() {
        resolveTaggedSessionCookie(
            UserSessionTag(uid = 42L, revision = 7L),
            UserSessionSnapshot(
                tag = UserSessionTag(uid = 42L, revision = 8L),
                cookies = "cookie-new"
            )
        )
    }

    @Test
    fun sessionChangedErrorKeepsItsUserFacingMessage() {
        val error = SessionChangedException()

        assertSame(error, mapToAppError(error))
        assertEquals("账号状态已变化，请刷新后重试", error.message)
    }
}

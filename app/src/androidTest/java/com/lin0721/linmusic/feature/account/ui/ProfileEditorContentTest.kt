package com.lin0721.linmusic.feature.account.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.createComposeRule
import com.lin0721.linmusic.feature.account.domain.UserProfileDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfileEditorContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editingNicknameAndSignatureEnablesSave() {
        var editorState by mutableStateOf(readyState())
        val nicknameChanges = mutableListOf<String>()
        val signatureChanges = mutableListOf<String>()
        var saveCalls = 0

        composeRule.setContent {
            MaterialTheme {
                ProfileEditorContent(
                    state = editorState,
                    onNicknameChange = { value ->
                        nicknameChanges += value
                        editorState = editorState.copy(nickname = value)
                    },
                    onSignatureChange = { value ->
                        signatureChanges += value
                        editorState = editorState.copy(signature = value)
                    },
                    onSave = { saveCalls++ },
                    onRefresh = {}
                )
            }
        }

        composeRule.onNodeWithTag("profile_nickname").performTextReplacement("新昵称")
        composeRule.onNodeWithTag("profile_signature").performTextReplacement("新的签名")
        composeRule.runOnIdle {
            assertTrue(nicknameChanges.contains("新昵称"))
            assertTrue(signatureChanges.contains("新的签名"))
        }

        composeRule.onNodeWithTag("profile_save").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, saveCalls) }
    }

    @Test
    fun incompleteProfileDisablesEditingAndSave() {
        var saveCalls = 0
        var editorState by mutableStateOf(
            readyState(profileDetails(gender = null, birthday = null, province = null, city = null))
        )

        composeRule.setContent {
            MaterialTheme {
                ProfileEditorContent(
                    state = editorState,
                    onNicknameChange = { value -> editorState = editorState.copy(nickname = value) },
                    onSignatureChange = { value -> editorState = editorState.copy(signature = value) },
                    onSave = { saveCalls++ },
                    onRefresh = {}
                )
            }
        }

        composeRule.onNodeWithTag("profile_nickname").assertIsNotEnabled()
        composeRule.onNodeWithTag("profile_signature").assertIsNotEnabled()
        composeRule.onNodeWithTag("profile_save").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0, saveCalls) }
    }

    @Test
    fun savingDisablesFieldsAndSaveButton() {
        var editorState by mutableStateOf(
            readyState(nickname = "已修改", isSaving = false)
        )
        var saveCalls = 0

        composeRule.setContent {
            MaterialTheme {
                ProfileEditorContent(
                    state = editorState,
                    onNicknameChange = { value -> editorState = editorState.copy(nickname = value) },
                    onSignatureChange = { value -> editorState = editorState.copy(signature = value) },
                    onSave = { saveCalls++ },
                    onRefresh = {}
                )
            }
        }

        composeRule.runOnIdle {
            editorState = editorState.copy(isSaving = true)
        }
        composeRule.onNodeWithTag("profile_nickname").assertIsNotEnabled()
        composeRule.onNodeWithTag("profile_signature").assertIsNotEnabled()
        composeRule.onNodeWithTag("profile_save").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0, saveCalls) }
    }

    private fun readyState(
        profile: UserProfileDetails = profileDetails(),
        nickname: String? = null,
        signature: String? = null,
        isSaving: Boolean = false
    ) = ProfileEditorState.Ready(
        profile = profile,
        nickname = nickname ?: profile.nickname,
        signature = signature ?: profile.signature,
        isSaving = isSaving
    )

    private fun profileDetails(
        gender: Int? = 1,
        birthday: Long? = 946684800000L,
        province: Int? = 1,
        city: Int? = 2
    ) = UserProfileDetails(
        userId = 42L,
        nickname = "原昵称",
        avatarUrl = "",
        signature = "原签名",
        gender = gender,
        birthday = birthday,
        province = province,
        city = city
    )
}

package app.lunex

import android.Manifest
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeWebRtcCallTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA
    )

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun androidCompletesAudioAndVideoCallsWithBrowserPeer() {
        compose.onNodeWithTag("server-url").performTextReplacement("http://10.0.2.2:3001")
        compose.onNodeWithTag("toggle-register").performClick()
        compose.onNodeWithTag("auth-username").performTextInput("e2e_android")
        compose.onNodeWithTag("auth-display-name").performTextInput("Android E2E")
        compose.onNodeWithTag("auth-password").performTextInput("Lunex-test-password-2026")
        compose.onNodeWithTag("auth-submit").performClick()

        compose.waitUntil(30000) {
            compose.onAllNodesWithText("Вошли как Android E2E").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("open-dms").performClick()
        compose.onNodeWithTag("dm-username").performTextInput("e2e_bob")
        compose.onNodeWithTag("open-dm-submit").performClick()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Bob E2E").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("call-audio").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("call-audio").performClick()
        compose.waitUntil(60000) {
            compose.onAllNodesWithText("Соединение установлено").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Завершить").performClick()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Соединение установлено").fetchSemanticsNodes().isEmpty()
        }

        compose.onNodeWithTag("call-video").performClick()
        compose.waitUntil(60000) {
            compose.onAllNodesWithText("Соединение установлено").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(20000) {
            compose.onAllNodesWithText("Видео собеседника").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Завершить").performClick()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText("Соединение установлено").fetchSemanticsNodes().isEmpty()
        }
    }
}

package staff.android.network

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CatalogHttpTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun control(mode: String) = runBlocking {
        val reply = HttpTransport().exchange(Request.Builder()
            .url("http://127.0.0.1:8787/control?mode=$mode").build())
        check(reply.code == 200)
    }
    private fun waitFor(tag: String) {
        ui.waitUntil(8_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    @Before fun reset() { control("reset") }

    @Test fun invalidPriceRetainsAcceptedProduct() {
        ui.onNodeWithTag("refresh").performScrollTo().performClick()
        waitFor("product:camera")
        ui.onNodeWithTag("product:camera").assertTextContains("USD 1299.00", substring = true)
        control("invalid")
        ui.onNodeWithTag("refresh").performScrollTo().performClick()
        waitFor("problem")
        ui.onNodeWithText("Invalid price", substring = true)
            .performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("product:camera").assertTextContains("USD 1299.00", substring = true)
    }

    @Test fun loadMoreThenAccountSwitchUsesNewPrices() {
        ui.onNodeWithTag("refresh").performScrollTo().performClick()
        waitFor("product:camera")
        ui.onNodeWithTag("load_more").performScrollTo().performClick()
        waitFor("product:tripod")
        ui.onNodeWithTag("load_more").assertIsNotEnabled()
        ui.onNodeWithTag("lab_controls").performScrollTo().performClick()
        ui.onNodeWithTag("switch_b").performScrollTo().performClick()
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("product:camera").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("account").assertTextContains("Account b")
        ui.onNodeWithTag("product:camera").assertTextContains("USD 899.00", substring = true)
        ui.onNodeWithTag("product:tripod").assertDoesNotExist()
    }
}

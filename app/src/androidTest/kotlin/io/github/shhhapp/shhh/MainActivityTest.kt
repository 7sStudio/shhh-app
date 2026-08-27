package io.github.shhhapp.shhh

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launches_andShowsHomeSections() {
        composeRule.onNodeWithText("Shhh").assertIsDisplayed()
        composeRule.onNodeWithText("One tap to hush your phone").assertIsDisplayed()
        composeRule.onNodeWithText("Hush for a while").assertIsDisplayed()
        composeRule.onNodeWithText("Quiet hours").assertIsDisplayed()
    }
}

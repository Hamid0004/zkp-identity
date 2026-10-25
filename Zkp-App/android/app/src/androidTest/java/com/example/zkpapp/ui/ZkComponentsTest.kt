package com.example.zkpapp.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ZkComponentsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun zkButton_showsText() {
        composeRule.setContent {
            ZKAuthTheme { ZkButton(text = "Continue", onClick = {}) }
        }
        composeRule.onNodeWithText("Continue").assertIsDisplayed()
    }

    @Test
    fun zkButton_click_invokesCallback() {
        var clicked = false
        composeRule.setContent {
            ZKAuthTheme { ZkButton(text = "Go", onClick = { clicked = true }) }
        }
        composeRule.onNodeWithText("Go").performClick()
        assertTrue(clicked)
    }

    @Test
    fun zkStatusBanner_showsMessage() {
        composeRule.setContent {
            ZKAuthTheme { ZkStatusBanner(message = "Proof verified", status = ZkStatus.Success) }
        }
        composeRule.onNodeWithText("Proof verified").assertIsDisplayed()
    }
}

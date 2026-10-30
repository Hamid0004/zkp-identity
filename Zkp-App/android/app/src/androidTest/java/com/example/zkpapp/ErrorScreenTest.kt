package com.example.zkpapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.zkpapp.ui.ZKAuthTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ErrorScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsErrorLogText() {
        composeRule.setContent {
            ZKAuthTheme {
                ErrorScreen(errorText = "Boom: test error", onCopy = {}, onRestart = {})
            }
        }
        composeRule.onNodeWithText("Boom: test error").assertIsDisplayed()
    }

    @Test
    fun copyButton_invokesCallback() {
        var copied = false
        composeRule.setContent {
            ZKAuthTheme {
                ErrorScreen(errorText = "x", onCopy = { copied = true }, onRestart = {})
            }
        }
        composeRule.onNodeWithText("Copy error").performClick()
        assertTrue(copied)
    }

    @Test
    fun restartButton_invokesCallback() {
        var restarted = false
        composeRule.setContent {
            ZKAuthTheme {
                ErrorScreen(errorText = "x", onCopy = {}, onRestart = { restarted = true })
            }
        }
        composeRule.onNodeWithText("Restart app").performClick()
        assertTrue(restarted)
    }
}
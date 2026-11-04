package com.example.zkpapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.zkpapp.ui.ZKAuthTheme
import com.example.zkpapp.ui.ZkStatus
import org.junit.Rule
import org.junit.Test

class LoginScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsTitleAndStatus() {
        composeRule.setContent {
            ZKAuthTheme { LoginScreen(status = "Generating Proof", kind = ZkStatus.Info) }
        }
        composeRule.onNodeWithText("ZK Identity Proof").assertIsDisplayed()
        composeRule.onNodeWithText("Generating Proof").assertIsDisplayed()
    }

    @Test
    fun showsSuccessMessage() {
        composeRule.setContent {
            ZKAuthTheme { LoginScreen(status = "LOGIN APPROVED", kind = ZkStatus.Success) }
        }
        composeRule.onNodeWithText("LOGIN APPROVED").assertIsDisplayed()
    }
}
package ru.restofleet.agent

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.StateRestorationTester
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Две вкладки верхнего уровня: Lab и Device Owner; выбранная вкладка переживает пересоздание. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AgentTabHostTest {
    @get:Rule val rule = createComposeRule()

    private fun content() = rule.setContent {
        AgentTabHost(lab = { Text("lab-content") }, deviceOwner = { Text("do-content") })
    }

    @Test
    fun labIsShownFirst() {
        content()

        rule.onNodeWithText("lab-content").assertExists()
        rule.onNodeWithText("do-content").assertDoesNotExist()
        rule.onNodeWithText("Lab").assertIsSelected()
    }

    @Test
    fun clickingTheDeviceOwnerTabSwitchesTheContentAndBack() {
        content()

        rule.onNodeWithText("Device Owner").performClick()
        rule.onNodeWithText("do-content").assertExists()
        rule.onNodeWithText("lab-content").assertDoesNotExist()
        rule.onNodeWithText("Device Owner").assertIsSelected()

        rule.onNodeWithText("Lab").performClick()
        rule.onNodeWithText("lab-content").assertExists()
        rule.onNodeWithText("do-content").assertDoesNotExist()
    }

    @Test
    fun selectedTabSurvivesRecreation() {
        val tester = StateRestorationTester(rule)
        tester.setContent {
            AgentTabHost(lab = { Text("lab-content") }, deviceOwner = { Text("do-content") })
        }
        rule.onNodeWithText("Device Owner").performClick()

        tester.emulateSavedInstanceStateRestore()

        rule.onNodeWithText("do-content").assertExists()
        rule.onNodeWithText("Device Owner").assertIsSelected()
    }
}

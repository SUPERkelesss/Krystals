package com.krystals.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun homeShowsPrimaryActions() {
        rule.onNodeWithText(rule.activity.getString(R.string.import_local)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.new_file)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.import_online)).assertIsDisplayed()
    }
}

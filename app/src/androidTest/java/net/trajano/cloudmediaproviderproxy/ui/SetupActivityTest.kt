package net.trajano.cloudmediaproviderproxy.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import net.trajano.cloudmediaproviderproxy.R
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SetupActivityTest {

    @Test
    fun setupScreenShowsBackButton() {
        ActivityScenario.launch(SetupActivity::class.java).use {
            onView(withId(R.id.back_button)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun setupScreenAppliesTopInsetPadding() {
        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val container = activity.findViewById<android.view.View>(R.id.setup_container)
                assertTrue(container.paddingTop > 24)
            }
        }
    }
}

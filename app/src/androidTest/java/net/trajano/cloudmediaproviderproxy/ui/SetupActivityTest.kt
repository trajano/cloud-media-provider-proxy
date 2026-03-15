package net.trajano.cloudmediaproviderproxy.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.appbar.MaterialToolbar
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import android.widget.ImageView
import net.trajano.cloudmediaproviderproxy.R
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SetupActivityTest {

    @Test
    fun setupScreenUsesToolbarAppBar() {
        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<MaterialToolbar>(R.id.setup_toolbar))
                assertNotNull(activity.supportActionBar)
            }
        }
    }

    @Test
    fun setupScreenRegistersToolbarAsSupportActionBar() {
        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.supportActionBar)
            }
        }
    }

    @Test
    fun setupScreenIncludesProviderIconSlot() {
        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<ImageView>(R.id.setup_provider_icon))
            }
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

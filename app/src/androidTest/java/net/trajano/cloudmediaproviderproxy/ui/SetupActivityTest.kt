package net.trajano.cloudmediaproviderproxy.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.appbar.MaterialToolbar
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import net.trajano.cloudmediaproviderproxy.config.SafRootPreferences
import net.trajano.cloudmediaproviderproxy.provider.SafMediaCatalog
import android.widget.ImageView
import net.trajano.cloudmediaproviderproxy.R
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SetupActivityTest {

    @Test
    fun nextcloudStorageNoticeIncludesInstructionsAndAnOpenButton() {
        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val noticeId = activity.resources.getIdentifier("nextcloud_storage_notice", "id", activity.packageName)
                assertTrue("Nextcloud storage notice must exist", noticeId != 0)
                val buttonId = activity.resources.getIdentifier("open_nextcloud_button", "id", activity.packageName)
                assertTrue("Nextcloud launch button must exist", buttonId != 0)
                assertNotNull(activity.findViewById<com.google.android.material.button.MaterialButton>(buttonId))
                val instructions = activity.findViewById<android.widget.TextView>(R.id.nextcloud_storage_instructions).text.toString()
                assertTrue(instructions.contains("Allow access from other apps"))
                assertTrue(instructions.contains("If the option is already off"))
                val authority = SafRootPreferences(activity).getRootUri()?.authority
                assertEquals(if (isNextcloudDocumentsAuthority(authority)) android.view.View.VISIBLE else android.view.View.GONE,
                    activity.findViewById<android.view.View>(noticeId).visibility)
            }
        }
    }

    @Test
    fun setupScreenIncludesPhotoCompatibilityToggle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = SafRootPreferences(context)
        val initiallyEnabled = preferences.compatiblePhotosEnabled()
        val previousCollection = SafMediaCatalog(context).collectionId()
        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    val identifier = activity.resources.getIdentifier("compatible_photos_switch", "id", activity.packageName)
                    assertTrue("Photo compatibility control must exist", identifier != 0)
                    val toggle = activity.findViewById<MaterialSwitch>(identifier)
                    assertNotNull(toggle)
                    toggle.isChecked = !initiallyEnabled
                    assertEquals(!initiallyEnabled, preferences.compatiblePhotosEnabled())
                    assertNotEquals(previousCollection, SafMediaCatalog(context).collectionId())
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals(!initiallyEnabled, activity.findViewById<MaterialSwitch>(R.id.compatible_photos_switch).isChecked)
                }
            } finally {
                scenario.onActivity { activity ->
                    activity.findViewById<MaterialSwitch>(R.id.compatible_photos_switch).isChecked = initiallyEnabled
                }
            }
        }
    }

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
    fun setupScreenIncludesShowAdvancedRootsToggle() {
        ActivityScenario.launch(SetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<MaterialSwitch>(R.id.show_advanced_switch))
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

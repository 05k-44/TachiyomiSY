package eu.kanade.tachiyomi.ui.setting

import androidx.biometric.BiometricManager
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.asImmediateFlow
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.ui.category.biometric.BiometricTimesController
import eu.kanade.tachiyomi.util.preference.defaultValue
import eu.kanade.tachiyomi.util.preference.intListPreference
import eu.kanade.tachiyomi.util.preference.onClick
import eu.kanade.tachiyomi.util.preference.preference
import eu.kanade.tachiyomi.util.preference.summaryRes
import eu.kanade.tachiyomi.util.preference.switchPreference
import eu.kanade.tachiyomi.util.preference.titleRes
import kotlinx.coroutines.flow.launchIn
import eu.kanade.tachiyomi.data.preference.PreferenceKeys as Keys

class SettingsSecurityController : SettingsController() {

    override fun setupPreferenceScreen(screen: PreferenceScreen) = screen.apply {
        titleRes = R.string.pref_category_security

        if (BiometricManager.from(context).canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS) {
            switchPreference {
                key = Keys.useBiometricLock
                titleRes = R.string.lock_with_biometrics
                defaultValue = false
            }
            intListPreference {
                key = Keys.lockAppAfter
                titleRes = R.string.lock_when_idle
                val values = arrayOf("0", "1", "2", "5", "10", "-1")
                entries = values.mapNotNull {
                    when (it) {
                        "-1" -> context.getString(R.string.lock_never)
                        "0" -> context.getString(R.string.lock_always)
                        else -> resources?.getQuantityString(R.plurals.lock_after_mins, it.toInt(), it)
                    }
                }.toTypedArray()
                entryValues = values
                defaultValue = "0"
                summary = "%s"

                preferences.useBiometricLock().asImmediateFlow { isVisible = it }
                    .launchIn(scope)
            }
        }

        switchPreference {
            key = Keys.secureScreen
            titleRes = R.string.secure_screen
            summaryRes = R.string.secure_screen_summary
            defaultValue = false
        }
        switchPreference {
            key = Keys.hideNotificationContent
            titleRes = R.string.hide_notification_content
            defaultValue = false
        }
        preference {
            key = "pref_edit_lock_times"
            titleRes = R.string.action_edit_biometric_lock_times

            val timeRanges = preferences.biometricTimeRanges().get().count()
            summary = context.resources.getQuantityString(R.plurals.num_lock_times, timeRanges, timeRanges)

            onClick {
                router.pushController(BiometricTimesController().withFadeTransaction())
            }
        }
    }
}

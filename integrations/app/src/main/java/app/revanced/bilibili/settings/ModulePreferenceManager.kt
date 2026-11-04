package app.revanced.bilibili.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.Keep
import androidx.preference.PreferenceManager

/**
 * Supplies [prefs] to preference screens inflated by the module.
 *
 * [getSharedPreferences] is retargeted by the preference-manager patch onto the host
 * method. Play 6.7.1 R8 makes that host method a final `b()SharedPreferences`, which
 * ART refuses to override, so the patch drops this method there and writes [prefs]
 * into the field the final getter returns.
 */
@Keep
@SuppressLint("RestrictedApi")
class ModulePreferenceManager(
    context: Context,
    val prefs: SharedPreferences
) : PreferenceManager(context) {
    override fun getSharedPreferences(): SharedPreferences = prefs
}

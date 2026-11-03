package app.revanced.patches.bilibili.misc.settings.fingerprints

import app.revanced.patcher.fingerprint.MethodFingerprint

object PreferenceManagerFingerprint : MethodFingerprint(
    // Play 6.7.1 obfuscates PreferenceManager and shortens setDefaultValues to (int, Context).
    strings = listOf("_has_set_default_values"),
    parameters = listOf("I", "Landroid/content/Context;"),
    returnType = "V"
)

package app.revanced.patches.bilibili.misc.settings.patch

import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patcher.patch.annotation.CompatiblePackage
import app.revanced.patcher.patch.annotation.Patch
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethodParameter.Companion.toMutable
import app.revanced.patches.bilibili.misc.settings.fingerprints.PreferenceManagerFingerprint
import app.revanced.patches.bilibili.utils.MethodParameter
import app.revanced.patches.bilibili.utils.cloneMutable
import app.revanced.util.exception

/**
 * TODO: better way to solve it?
 */
@Patch(
    name = "Fix preference manager",
    description = "修复PreferenceManager被混淆后引起的问题，避免覆盖被标成final的getSharedPreferences",
    compatiblePackages = [
        CompatiblePackage(name = "tv.danmaku.bili"),
        CompatiblePackage(name = "tv.danmaku.bilibilihd"),
        CompatiblePackage(name = "com.bilibili.app.in")
    ]
)
object FixPreferenceManagerPatch : BytecodePatch(setOf(PreferenceManagerFingerprint)) {
    override fun execute(context: BytecodeContext) {
        val preferenceManagerDef = PreferenceManagerFingerprint.result?.classDef
            ?: throw PreferenceManagerFingerprint.exception

        val checkBoxGroupPreferenceClass =
            context.findClass("Lapp/revanced/bilibili/widget/CheckBoxGroupPreference;")!!.mutableClass
        checkBoxGroupPreferenceClass.methods.run {
            first { it.name == "onAttachedToHierarchy" }.apply {
                replaceInstruction(
                    1, """
                    invoke-super {p0, p1}, Landroidx/preference/PreferenceCategory;->onAttachedToHierarchy($preferenceManagerDef)V
                """.trimIndent()
                )
            }.run {
                remove(this)
                cloneMutable(parameters = parameters.also {
                    it[0] = MethodParameter(preferenceManagerDef.type).toMutable()
                }).let { add(it) }
            }
            first { it.name == "notifyChanged" }.run {
                replaceInstruction(
                    0, """
                    invoke-virtual {p0}, Landroidx/preference/PreferenceCategory;->getPreferenceManager()$preferenceManagerDef
                """.trimIndent()
                )
                replaceInstruction(
                    2, """
                    invoke-virtual {p0, v0}, Lapp/revanced/bilibili/widget/CheckBoxGroupPreference;->onAttachedToHierarchy($preferenceManagerDef)V
                """.trimIndent()
                )
            }
        }

        val modulePrefsManagerClass =
            context.findClass("Lapp/revanced/bilibili/settings/ModulePreferenceManager;")!!.mutableClass
        // Proxy PreferenceManager only when its cache field must be widened. Rewriting the
        // whole obfuscated class on the non-final path is unnecessary.
        val preferenceManager = when (chooseSharedPreferencesBind(preferenceManagerDef)) {
            is SharedPreferencesBind.StoreField ->
                context.proxy(preferenceManagerDef).mutableClass
            is SharedPreferencesBind.Override -> preferenceManagerDef
        }
        applyModulePreferenceManagerFix(preferenceManager, modulePrefsManagerClass)
    }
}

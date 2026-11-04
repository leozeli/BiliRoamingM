package app.revanced.patches.bilibili.misc.settings.patch

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.util.proxy.mutableTypes.MutableClass
import app.revanced.patcher.util.proxy.mutableTypes.MutableField
import app.revanced.patches.bilibili.utils.removeFinal
import app.revanced.patches.bilibili.utils.toPublic
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val SHARED_PREFERENCES_TYPE = "Landroid/content/SharedPreferences;"

/**
 * Play 6.7.1 R8 renames [androidx.preference.PreferenceManager] to `Landroidx/preference/g;`
 * and turns `getSharedPreferences()` into a **final** instance method `b()`.
 * Copying that name onto [app.revanced.bilibili.settings.ModulePreferenceManager]
 * makes ART throw LinkageError while inflating BiliRoamingSettingsFragment.
 *
 * When the getter is not final, keep overriding it under its (possibly obfuscated) name.
 * When every matching getter is final, drop the override and write the module
 * SharedPreferences into the field that getter returns.
 */
internal sealed class SharedPreferencesBind {
    data class Override(val methodName: String) : SharedPreferencesBind()
    data class StoreField(val field: Field) : SharedPreferencesBind()
}

internal fun chooseSharedPreferencesBind(preferenceManager: ClassDef): SharedPreferencesBind {
    val instanceFields = preferenceManager.fields.filter { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) && field.type == SHARED_PREFERENCES_TYPE
    }
    val getters = preferenceManager.methods.filter { it.isSharedPreferencesGetter() }
    val overridable = getters.filter { !AccessFlags.FINAL.isSet(it.accessFlags) }
    if (overridable.isNotEmpty()) {
        val preferred = overridable.firstOrNull { method ->
            method.referencedSharedPreferencesField(instanceFields) != null
        } ?: overridable.first()
        return SharedPreferencesBind.Override(preferred.name)
    }

    val field = getters.firstNotNullOfOrNull { method ->
        method.referencedSharedPreferencesField(instanceFields)
    } ?: instanceFields.singleOrNull()
        ?: throw PatchException(
            "PreferenceManager getter is final and has no SharedPreferences field to store into"
        )
    return SharedPreferencesBind.StoreField(field)
}

internal fun applyModulePreferenceManagerFix(
    preferenceManager: ClassDef,
    modulePreferenceManager: MutableClass,
) {
    modulePreferenceManager.setSuperClass(preferenceManager.type)
    val constructor = modulePreferenceManager.methods.first { it.name == "<init>" }
    constructor.replaceInstruction(
        0,
        """
        invoke-direct {p0, p1}, ${preferenceManager.type}-><init>(Landroid/content/Context;)V
        """.trimIndent()
    )

    val override = modulePreferenceManager.methods.first { method ->
        method.name == "getSharedPreferences" && method.isSharedPreferencesGetter()
    }
    when (val bind = chooseSharedPreferencesBind(preferenceManager)) {
        is SharedPreferencesBind.Override -> override.name = bind.methodName
        is SharedPreferencesBind.StoreField -> {
            modulePreferenceManager.methods.remove(override)
            val field = (preferenceManager as? MutableClass)?.fields?.first { mutable ->
                mutable.name == bind.field.name && mutable.type == bind.field.type
            } ?: throw PatchException(
                "PreferenceManager must be mutable to store SharedPreferences into ${bind.field.name}"
            )
            field.allowSubclassWrite()
            constructor.addInstructions(
                1,
                """
                iput-object p2, p0, ${preferenceManager.type}->${field.name}:${field.type}
                """.trimIndent()
            )
        }
    }

    val collisions = finalSharedPreferencesOverrideNames(preferenceManager, modulePreferenceManager)
    if (collisions.isNotEmpty()) {
        throw PatchException(
            "ModulePreferenceManager still overrides final PreferenceManager method(s): " +
                collisions.joinToString()
        )
    }
}

/**
 * Names of no-arg SharedPreferences methods on [modulePreferenceManager] that match a
 * final instance method on [preferenceManager]. That is the LinkageError signature
 * (`b()Landroid/content/SharedPreferences;` on Play 6.7.1).
 */
internal fun finalSharedPreferencesOverrideNames(
    preferenceManager: ClassDef,
    modulePreferenceManager: ClassDef,
): List<String> {
    val finalNames = preferenceManager.methods.mapNotNull { method ->
        if (!method.isSharedPreferencesGetter()) return@mapNotNull null
        if (!AccessFlags.FINAL.isSet(method.accessFlags)) return@mapNotNull null
        method.name
    }.toSet()
    return modulePreferenceManager.methods.mapNotNull { method ->
        if (method.name in finalNames && method.isSharedPreferencesGetter()) method.name else null
    }
}

private fun Method.isSharedPreferencesGetter(): Boolean {
    return !AccessFlags.STATIC.isSet(accessFlags) &&
        parameters.isEmpty() &&
        returnType == SHARED_PREFERENCES_TYPE
}

private fun Method.referencedSharedPreferencesField(instanceFields: List<Field>): Field? {
    val instructions = implementation?.instructions?.toList() ?: return null
    var fallback: Field? = null
    for (index in instructions.indices) {
        val instruction = instructions[index]
        if (instruction !is ReferenceInstruction) continue
        if (instruction.opcode != Opcode.IGET_OBJECT && instruction.opcode != Opcode.IPUT_OBJECT) continue
        val reference = instruction.reference as? FieldReference ?: continue
        if (reference.type != SHARED_PREFERENCES_TYPE) continue
        val field = instanceFields.find { it.name == reference.name && it.definingClass == reference.definingClass }
            ?: instanceFields.find { it.name == reference.name }
            ?: continue
        if (instruction.opcode == Opcode.IGET_OBJECT) fallback = field
        if (instruction.opcode == Opcode.IGET_OBJECT &&
            instructions.getOrNull(index + 1)?.opcode == Opcode.RETURN_OBJECT
        ) {
            return field
        }
    }
    return fallback
}

private fun MutableField.allowSubclassWrite() {
    var flags = accessFlags
    if (AccessFlags.FINAL.isSet(flags)) flags = flags.removeFinal()
    val visibleToSubclass = AccessFlags.PUBLIC.isSet(flags) || AccessFlags.PROTECTED.isSet(flags)
    if (!visibleToSubclass) flags = flags.toPublic()
    if (flags != accessFlags) accessFlags = flags
}

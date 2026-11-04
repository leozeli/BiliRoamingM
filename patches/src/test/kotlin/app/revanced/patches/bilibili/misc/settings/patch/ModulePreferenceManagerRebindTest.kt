package app.revanced.patches.bilibili.misc.settings.patch

import app.revanced.patcher.util.proxy.mutableTypes.MutableClass
import app.revanced.patches.bilibili.utils.Field
import app.revanced.patches.bilibili.utils.Method
import app.revanced.patches.bilibili.utils.MethodImplementation
import app.revanced.patches.bilibili.utils.MethodParameter
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModulePreferenceManagerRebindTest {
    @Test
    fun play671DoesNotDeclareFinalMethodB() {
        val parent = MutableClass(
            preferenceManager(
                type = PLAY_PREFERENCE_MANAGER,
                fields = listOf(
                    Field(
                        PLAY_PREFERENCE_MANAGER,
                        "a",
                        SHARED_PREFERENCES_TYPE,
                        AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
                    ),
                    Field(
                        PLAY_PREFERENCE_MANAGER,
                        "unused",
                        SHARED_PREFERENCES_TYPE,
                        AccessFlags.PRIVATE.value,
                    ),
                ),
                methods = listOf(
                    // Listed first so the old `.first { no params; returns SharedPreferences }`
                    // selection would rename the override to this final method.
                    sharedPreferencesGetter(
                        owner = PLAY_PREFERENCE_MANAGER,
                        name = "b",
                        accessFlags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        fieldName = "a",
                    ),
                    sharedPreferencesGetter(
                        owner = PLAY_PREFERENCE_MANAGER,
                        name = "a",
                        accessFlags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value,
                        fieldName = "unused",
                    ),
                ),
            )
        )
        val child = modulePreferenceManager()

        applyModulePreferenceManagerFix(parent, child)

        assertEquals(PLAY_PREFERENCE_MANAGER, child.superclass)
        assertTrue(
            finalSharedPreferencesOverrideNames(parent, child).isEmpty(),
            "ModulePreferenceManager still declares final PreferenceManager method b()",
        )
        assertTrue(child.methods.none { it.name == "b" && it.parameters.isEmpty() })
        assertTrue(child.methods.none { it.name == "a" && it.parameters.isEmpty() })
        assertTrue(child.methods.none { it.name == "getSharedPreferences" })

        val cacheField = parent.fields.first { it.name == "a" }
        assertTrue(AccessFlags.PUBLIC.isSet(cacheField.accessFlags))
        assertTrue(!AccessFlags.PRIVATE.isSet(cacheField.accessFlags))
        assertTrue(!AccessFlags.FINAL.isSet(cacheField.accessFlags))
        assertTrue(AccessFlags.PRIVATE.isSet(parent.fields.first { it.name == "unused" }.accessFlags))

        val constructor = child.methods.first { it.name == "<init>" }
        val instructions = constructor.implementation!!.instructions.toList()
        val superInit = instructions.indexOfFirst { instruction ->
            instruction.opcode == Opcode.INVOKE_DIRECT &&
                (instruction as ReferenceInstruction).reference.toString().contains("<init>")
        }
        val store = instructions.indexOfFirst { instruction ->
            instruction.opcode == Opcode.IPUT_OBJECT &&
                ((instruction as ReferenceInstruction).reference as FieldReference).let { field ->
                    field.definingClass == PLAY_PREFERENCE_MANAGER && field.name == "a"
                }
        }
        assertTrue(superInit >= 0 && store > superInit)
    }

    @Test
    fun nonFinalGetterIsStillOverridden() {
        val parent = MutableClass(
            preferenceManager(
                type = STOCK_PREFERENCE_MANAGER,
                fields = listOf(
                    Field(
                        STOCK_PREFERENCE_MANAGER,
                        "mSharedPreferences",
                        SHARED_PREFERENCES_TYPE,
                        AccessFlags.PRIVATE.value,
                    )
                ),
                methods = listOf(
                    sharedPreferencesGetter(
                        owner = STOCK_PREFERENCE_MANAGER,
                        name = "getSharedPreferences",
                        accessFlags = AccessFlags.PUBLIC.value,
                        fieldName = "mSharedPreferences",
                    )
                ),
            )
        )
        val child = modulePreferenceManager(superType = STOCK_PREFERENCE_MANAGER)

        applyModulePreferenceManagerFix(parent, child)

        assertEquals(STOCK_PREFERENCE_MANAGER, child.superclass)
        val override = child.methods.single { it.parameters.isEmpty() && it.returnType == SHARED_PREFERENCES_TYPE }
        assertEquals("getSharedPreferences", override.name)
        assertTrue(AccessFlags.PRIVATE.isSet(parent.fields.first().accessFlags))
        val storedOnParent = child.methods.first { it.name == "<init>" }.implementation!!.instructions.any { instruction ->
            instruction.opcode == Opcode.IPUT_OBJECT &&
                ((instruction as ReferenceInstruction).reference as FieldReference).definingClass == STOCK_PREFERENCE_MANAGER
        }
        assertTrue(!storedOnParent)
    }

    @Test
    fun finalMethodBIsNotChosenWhenAnotherGetterIsOverridable() {
        val parentType = "Landroidx/preference/PreferenceManager;"
        val parent = preferenceManager(
            type = parentType,
            fields = listOf(
                Field(parentType, "cache", SHARED_PREFERENCES_TYPE, AccessFlags.PUBLIC.value),
            ),
            methods = listOf(
                nullGetter(parentType, "b", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value),
                sharedPreferencesGetter(
                    owner = parentType,
                    name = "c",
                    accessFlags = AccessFlags.PUBLIC.value,
                    fieldName = "cache",
                ),
            ),
        )

        val bind = chooseSharedPreferencesBind(parent)
        assertIs<SharedPreferencesBind.Override>(bind)
        assertEquals("c", bind.methodName)

        val child = modulePreferenceManager(superType = parentType)
        applyModulePreferenceManagerFix(parent, child)
        assertTrue(finalSharedPreferencesOverrideNames(parent, child).isEmpty())
        assertEquals(
            "c",
            child.methods.single { it.parameters.isEmpty() && it.returnType == SHARED_PREFERENCES_TYPE }.name,
        )
    }

    private fun modulePreferenceManager(
        superType: String = "Landroidx/preference/PreferenceManager;",
    ): MutableClass {
        val constructor = Method(
            definingClass = MODULE,
            name = "<init>",
            returnType = "V",
            accessFlags = AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
            parameters = listOf(
                MethodParameter("Landroid/content/Context;"),
                MethodParameter(SHARED_PREFERENCES_TYPE),
            ),
            implementation = MethodImplementation(
                registerCount = 3,
                instructions = listOf(
                    ImmutableInstruction35c(
                        Opcode.INVOKE_DIRECT,
                        2,
                        0,
                        1,
                        0,
                        0,
                        0,
                        ImmutableMethodReference(
                            superType,
                            "<init>",
                            listOf("Landroid/content/Context;"),
                            "V",
                        ),
                    ),
                    ImmutableInstruction22c(
                        Opcode.IPUT_OBJECT,
                        2,
                        0,
                        ImmutableFieldReference(MODULE, "prefs", SHARED_PREFERENCES_TYPE),
                    ),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
            ),
        )
        val getter = Method(
            definingClass = MODULE,
            name = "getSharedPreferences",
            returnType = SHARED_PREFERENCES_TYPE,
            accessFlags = AccessFlags.PUBLIC.value,
            implementation = MethodImplementation(
                registerCount = 2,
                instructions = listOf(
                    ImmutableInstruction22c(
                        Opcode.IGET_OBJECT,
                        0,
                        1,
                        ImmutableFieldReference(MODULE, "prefs", SHARED_PREFERENCES_TYPE),
                    ),
                    ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                ),
            ),
        )
        return MutableClass(
            ImmutableClassDef(
                MODULE,
                AccessFlags.PUBLIC.value,
                superType,
                emptyList(),
                null,
                null,
                listOf(Field(MODULE, "prefs", SHARED_PREFERENCES_TYPE, AccessFlags.PRIVATE.value)),
                listOf(constructor, getter),
            )
        )
    }

    private fun preferenceManager(
        type: String,
        fields: List<com.android.tools.smali.dexlib2.iface.Field>,
        methods: List<com.android.tools.smali.dexlib2.iface.Method>,
    ) = ImmutableClassDef(
        type,
        AccessFlags.PUBLIC.value,
        "Ljava/lang/Object;",
        emptyList(),
        null,
        null,
        fields,
        methods,
    )

    private fun sharedPreferencesGetter(
        owner: String,
        name: String,
        accessFlags: Int,
        fieldName: String,
    ) = Method(
        definingClass = owner,
        name = name,
        returnType = SHARED_PREFERENCES_TYPE,
        accessFlags = accessFlags,
        implementation = MethodImplementation(
            registerCount = 2,
            instructions = listOf(
                ImmutableInstruction22c(
                    Opcode.IGET_OBJECT,
                    0,
                    1,
                    ImmutableFieldReference(owner, fieldName, SHARED_PREFERENCES_TYPE),
                ),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            ),
        ),
    )

    private fun nullGetter(owner: String, name: String, accessFlags: Int) = Method(
        definingClass = owner,
        name = name,
        returnType = SHARED_PREFERENCES_TYPE,
        accessFlags = accessFlags,
        implementation = MethodImplementation(
            registerCount = 1,
            instructions = listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            ),
        ),
    )

    private companion object {
        const val MODULE = "Lapp/revanced/bilibili/settings/ModulePreferenceManager;"
        const val PLAY_PREFERENCE_MANAGER = "Landroidx/preference/g;"
        const val STOCK_PREFERENCE_MANAGER = "Landroidx/preference/PreferenceManager;"
    }
}

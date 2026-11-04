package app.revanced.patches.bilibili.misc.protobuf.patch

import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patcher.patch.annotation.CompatiblePackage
import app.revanced.patcher.patch.annotation.Patch
import app.revanced.patcher.util.proxy.mutableTypes.MutableClass
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethod
import app.revanced.patches.bilibili.utils.*
import app.revanced.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Patch(
    name = "Unlock ProtoBuf",
    description = "公开ProtoBuf实体构建方法",
    compatiblePackages = [
        CompatiblePackage(name = "tv.danmaku.bili"),
        CompatiblePackage(name = "tv.danmaku.bilibilihd"),
        CompatiblePackage(name = "com.bilibili.app.in")
    ]
)
object UnlockProtobufPatch : BytecodePatch() {
    override fun execute(context: BytecodeContext) {
        val methodNameRegex = Regex("""^((set|add|remove|clear|merge|getMutable)\w+)|<init>$""")
        context.classes.filter { it.superclass == "Lcom/google/protobuf/GeneratedMessageLite;" }
            .map { it.proxy(context) }
            .forEach { clazz ->
                // Private instance setters stay invoke-direct at their call sites.
                // Publishing them without rewriting those calls makes ART throw
                // IncompatibleClassChangeError (expected direct, found virtual).
                // Play 6.7.1 hits this in Metrics.r -> setExtraFlag during IgHttpDns.report.
                val opened = clazz.methods.filter { m ->
                    m.accessFlags.isPrivate()
                            && !m.accessFlags.isStatic()
                            && m.name != "<init>"
                            && m.name.matches(methodNameRegex)
                }
                if (opened.isNotEmpty()) {
                    rewriteDirectCalls(clazz, opened)
                    opened.forEach { it.accessFlags = it.accessFlags.toPublic() }
                }
                clazz.methods.filter { m ->
                    m.accessFlags.isPrivate()
                            && !m.accessFlags.isStatic()
                            && m.name == "<init>"
                }.forEach { it.accessFlags = it.accessFlags.toPublic() }
            }
        // add back XXX method copy from executeXXX method
        context.classes.filter {
            it.type.startsWith("Lcom/bapis") && it.type.endsWith("Moss;")
        }.forEach { c ->
            c.proxy(context).run {
                val newMethods = mutableListOf<MutableMethod>()
                methods.forEach { m ->
                    if (m.name.startsWith("execute") && m.name.length > 7 && m.returnType != "V") {
                        val noPrefixName = m.name.substringAfter("execute").replaceFirstChar { it.lowercaseChar() }
                        m.cloneMutable(name = noPrefixName).takeIf { mm ->
                            methods.none { it == mm }
                        }?.let { newMethods.add(it) }
                    }
                }
                methods.addAll(newMethods)
            }
        }
    }

    private fun rewriteDirectCalls(clazz: MutableClass, opened: List<MutableMethod>) {
        val openedKeys = opened.map { it.toKey() }.toSet()
        clazz.methods.forEach { caller ->
            val implementation = caller.implementation ?: return@forEach
            val replacements = implementation.instructions.mapIndexedNotNull { index, inst ->
                if (inst.opcode != Opcode.INVOKE_DIRECT && inst.opcode != Opcode.INVOKE_DIRECT_RANGE)
                    return@mapIndexedNotNull null
                val ref = inst.getReference<MethodReference>()
                if (ref.definingClass != clazz.type || ref.toKey() !in openedKeys)
                    return@mapIndexedNotNull null
                index to when (inst.opcode) {
                    Opcode.INVOKE_DIRECT -> {
                        val args = (inst as FiveRegisterInstruction).args()
                        "invoke-virtual {$args}, $ref"
                    }
                    else -> {
                        val range = inst as Instruction3rc
                        val start = range.startRegister
                        val end = start + range.registerCount - 1
                        "invoke-virtual/range {v$start .. v$end}, $ref"
                    }
                }
            }
            replacements.forEach { (index, smali) -> caller.replaceInstruction(index, smali) }
        }
    }

    private fun MethodReference.toKey() = Triple(name, parameterTypes.map { it.toString() }, returnType)
}

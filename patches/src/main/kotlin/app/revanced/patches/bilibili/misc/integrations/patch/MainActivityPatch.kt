package app.revanced.patches.bilibili.misc.integrations.patch

import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
import app.revanced.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.annotation.CompatiblePackage
import app.revanced.patcher.patch.annotation.Patch
import com.android.tools.smali.dexlib2.Opcode

@Patch(
    name = "Main activity patch",
    description = "代理部分MainActivity方法补丁",
    compatiblePackages = [
        CompatiblePackage(name = "tv.danmaku.bili"),
        CompatiblePackage(name = "tv.danmaku.bilibilihd"),
        CompatiblePackage(name = "com.bilibili.app.in")
    ]
)
object MainActivityPatch : BytecodePatch() {
    override fun execute(context: BytecodeContext) {
        context.findClass("Ltv/danmaku/bili/MainActivityV2;")?.mutableClass?.run {
            // Parameter registers sit at the top of the frame. Play 6.7.1
            // onBackPressed has 19 registers and one parameter, so p0 is v18.
            // invoke-static {pN} is a 35c op and only encodes v0–v15. Smali prints
            // "Invalid register: v18" and drops that invoke, leaving move-result
            // with an undefined result. ART then rejects the class:
            // copyRes1 v0<- result0 type=Undefined.
            // invoke-static/range encodes the same call for any register.
            methods.find { it.name == "onCreate" }?.run {
                addInstruction(
                    implementation!!.instructions.size - 1,
                    """
                    invoke-static/range {p0 .. p0}, Lapp/revanced/bilibili/patches/main/MainActivityDelegate;->onCreate(Ltv/danmaku/bili/MainActivityV2;)V
                """.trimIndent()
                )
            }
            methods.find { it.name == "onStart" }?.run {
                val insertIdx = implementation!!.instructions.indexOfFirst { it.opcode == Opcode.INVOKE_SUPER } + 1
                addInstruction(
                    insertIdx, """
                    invoke-static/range {p0 .. p0}, Lapp/revanced/bilibili/patches/main/MainActivityDelegate;->onStart(Ltv/danmaku/bili/MainActivityV2;)V
                """.trimIndent()
                )
            }
            methods.find { it.name == "onBackPressed" }?.run {
                addInstructionsWithLabels(
                    0, """
                    invoke-static/range {p0 .. p0}, Lapp/revanced/bilibili/patches/main/MainActivityDelegate;->onBackPressed(Ltv/danmaku/bili/MainActivityV2;)Z
                    move-result v0
                    if-eqz v0, :jump
                    return-void
                    :jump
                    nop
                """.trimIndent()
                )
                val hooked = implementation!!.instructions.firstOrNull()?.opcode
                if (hooked != Opcode.INVOKE_STATIC_RANGE) {
                    throw PatchException(
                        "MainActivityV2.onBackPressed hook was dropped (p0 wider than v15)"
                    )
                }
            }
            methods.find { it.name == "onSaveInstanceState" }?.run {
                val insertIdx = implementation!!.instructions.indexOfFirst { it.opcode == Opcode.INVOKE_SUPER } + 1
                addInstruction(
                    insertIdx, """
                    invoke-static/range {p0 .. p1}, Lapp/revanced/bilibili/patches/main/MainActivityDelegate;->onSaveInstanceState(Ltv/danmaku/bili/MainActivityV2;Landroid/os/Bundle;)V
                """.trimIndent()
                )
            }
        } ?: throw PatchException("main activity patch failed")
    }
}

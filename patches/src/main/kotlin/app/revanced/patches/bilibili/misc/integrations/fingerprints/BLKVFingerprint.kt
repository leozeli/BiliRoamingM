package app.revanced.patches.bilibili.misc.integrations.fingerprints

import app.revanced.patcher.fingerprint.MethodFingerprint

object BLKVFingerprint : MethodFingerprint(
    // Play 6.7.1 dropped the trailing mode int; the file-based overload still has it.
    strings = listOf(".blkv"),
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;", "Z")
)

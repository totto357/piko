/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.timeline.muteFilter

import app.crimera.patches.twitter.entity.entityGenerator
import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.twitter.utils.enableSettings
import app.crimera.patches.twitter.utils.versionCheckPatch
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode

private const val INLINE_ACTION_BAR_DESCRIPTOR = "Lcom/twitter/ui/tweet/inlineactions/InlineActionBar;"

private object SetTweetFingerprint : Fingerprint(
    definingClass = INLINE_ACTION_BAR_DESCRIPTOR,
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this"
        ),
        string("file:///android_asset/default_heart_v3.json")
    )
)

@Suppress("unused")
val timelineMuteFilterPatch =
    bytecodePatch(
        name = "Timeline mute filter",
        description = "Hide posts matching custom muted words or users across all timelines including lists and search.",
    ) {
        compatibleWith(COMPATIBILITY_X)
        dependsOn(settingsPatch, versionCheckPatch, entityGenerator)

        execute {
            val method = SetTweetFingerprint.method
            val returnVoidIndex = method.indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID)

            method.addInstruction(
                returnVoidIndex,
                "invoke-static/range { p0 .. p1 }, $PATCHES_DESCRIPTOR/TimelineMuteFilter;->filterTweet(Landroid/view/View;Ljava/lang/Object;)V",
            )

            enableSettings("timelineMuteFilter")
        }
    }

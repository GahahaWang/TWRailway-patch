package app.twrailway.patches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.twrailway.patches.shared.Constants.COMPATIBILITY_TRAIN_SCHEDULE

/**
 * 火車時刻表 is a Flutter app. Its ads are requested from Dart through the
 * google_mobile_ads plugin, which forwards every ad request over the
 * `plugins.flutter.io/google_mobile_ads` method channel to the native
 * Google Mobile Ads (Next-Gen) SDK. The native SDK is fully obfuscated, so the
 * patch hooks the plugin's method channel handler instead.
 *
 * Every `load*` call (loadBannerAd, loadInterstitialAd, loadNativeAd, loadAppOpenAd,
 * loadRewardedAd, ...) is answered with `result.success(null)` without loading anything.
 * The Dart side then never receives an `onAdLoaded` event, so no ad is ever shown.
 */
@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Stops the app from loading banner, interstitial, native and app open ads.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_TRAIN_SCHEDULE)

    execute {
        GoogleMobileAdsPluginOnMethodCallFingerprint.method.apply {
            val methodCallType = parameterTypes[0].toString()
            val resultType = parameterTypes[1].toString()

            // MethodCall has two fields: `method` (String) and `arguments` (Object).
            val methodNameField = classDefBy(methodCallType).fields.singleOrNull { field ->
                field.type == "Ljava/lang/String;"
            } ?: throw PatchException("Could not find MethodCall.method field in $methodCallType")

            // MethodChannel.Result: success(Object), error(String, String, Object), notImplemented().
            val successMethod = classDefBy(resultType).methods.singleOrNull { method ->
                method.returnType == "V" &&
                    method.parameterTypes.map { it.toString() } == listOf("Ljava/lang/Object;")
            } ?: throw PatchException("Could not find MethodChannel.Result.success in $resultType")

            val implementation = implementation
                ?: throw PatchException("onMethodCall has no implementation")
            val parameterRegisters = parameterTypes.sumOf { type ->
                if (type.toString() == "J" || type.toString() == "D") 2 else 1
            } + 1 // this
            if (implementation.registerCount - parameterRegisters < 2) {
                throw PatchException("onMethodCall has too few free registers")
            }

            addInstructionsWithLabels(
                0,
                """
                    move-object/from16 v0, p1
                    iget-object v0, v0, $methodCallType->${methodNameField.name}:Ljava/lang/String;
                    if-eqz v0, :original
                    const-string v1, "load"
                    invoke-virtual { v0, v1 }, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
                    move-result v0
                    if-eqz v0, :original
                    move-object/from16 v0, p2
                    const/4 v1, 0x0
                    invoke-interface { v0, v1 }, $resultType->${successMethod.name}(Ljava/lang/Object;)V
                    return-void
                """,
                ExternalLabel("original", getInstruction(0)),
            )
        }
    }
}

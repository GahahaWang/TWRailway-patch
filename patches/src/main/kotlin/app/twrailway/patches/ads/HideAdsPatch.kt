package app.twrailway.patches.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.returnEarly
import app.twrailway.patches.shared.Constants.COMPATIBILITY_TRAIN_SCHEDULE
import java.util.logging.Logger

private const val ADS_PACKAGE = "Lcom/google/android/gms/ads"

/**
 * Banner views. Loading an ad is skipped and the view is hidden,
 * so the empty banner does not take up screen space.
 */
private val bannerLoadMethods = listOf(
    "$ADS_PACKAGE/BaseAdView;" to "loadAd",
    "$ADS_PACKAGE/admanager/AdManagerAdView;" to "loadAd",
)

/**
 * Full screen and native ads. Loading is skipped, so the load callback is never invoked
 * and the app never receives an ad to show.
 */
private val fullScreenLoadMethods = listOf(
    // Interstitial ads.
    "$ADS_PACKAGE/interstitial/InterstitialAd;" to "load",
    "$ADS_PACKAGE/admanager/AdManagerInterstitialAd;" to "load",
    // Legacy interstitial API (Mobile Ads SDK 19 and older).
    "$ADS_PACKAGE/InterstitialAd;" to "loadAd",
    // Rewarded ads.
    "$ADS_PACKAGE/rewarded/RewardedAd;" to "load",
    "$ADS_PACKAGE/rewardedinterstitial/RewardedInterstitialAd;" to "load",
    // App open ads.
    "$ADS_PACKAGE/appopen/AppOpenAd;" to "load",
    // Native ads.
    "$ADS_PACKAGE/AdLoader;" to "loadAd",
    "$ADS_PACKAGE/AdLoader;" to "loadAds",
)

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Stops the app from loading and showing banner, interstitial, rewarded, app open and native ads.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_TRAIN_SCHEDULE)

    execute {
        val logger = Logger.getLogger(this::class.java.name)
        var patchedCount = 0

        fun forEachMethod(
            targets: List<Pair<String, String>>,
            action: (MutableMethod) -> Unit,
        ) {
            targets.forEach { (classType, methodName) ->
                val classDef = mutableClassDefByOrNull(classType) ?: return@forEach
                classDef.methods.filter { method ->
                    method.name == methodName &&
                        method.returnType == "V" &&
                        method.implementation != null
                }.forEach { method ->
                    action(method)
                    patchedCount++
                    logger.info("Patched $classType->$methodName")
                }
            }
        }

        // Banner: hide the view, then return before any ad request is made.
        // The AdRequest parameter register (p1) is reused because it is no longer needed.
        forEachMethod(bannerLoadMethods) { method ->
            if (method.parameterTypes.size != 1) {
                method.returnEarly()
                return@forEachMethod
            }

            method.addInstructions(
                0,
                """
                    const/16 p1, 0x8
                    invoke-virtual/range { p0 .. p1 }, Landroid/view/View;->setVisibility(I)V
                    return-void
                """,
            )
        }

        // Full screen and native ads: never load.
        forEachMethod(fullScreenLoadMethods) { method ->
            method.returnEarly()
        }

        if (patchedCount == 0) {
            throw PatchException("Could not find any Google Mobile Ads SDK classes to patch")
        }
    }
}

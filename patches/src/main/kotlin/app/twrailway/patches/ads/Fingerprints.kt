package app.twrailway.patches.ads

import app.morphe.patcher.Fingerprint

/**
 * `GoogleMobileAdsPlugin.onMethodCall(MethodCall, MethodChannel.Result)` of the
 * google_mobile_ads Flutter plugin. The class and its parameter types are obfuscated,
 * but the method channel names it dispatches on are not.
 */
internal object GoogleMobileAdsPluginOnMethodCallFingerprint : Fingerprint(
    name = "onMethodCall",
    returnType = "V",
    strings = listOf(
        "MobileAds#initialize",
        "loadBannerAd",
        "loadInterstitialAd",
    ),
    custom = { method, _ -> method.parameterTypes.size == 2 },
)

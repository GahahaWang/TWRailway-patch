package app.twrailway.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    /**
     * 火車時刻表 (Taiwan train schedule) by tsumii.
     * https://play.google.com/store/apps/details?id=com.tsumii.trainschedule
     */
    val COMPATIBILITY_TRAIN_SCHEDULE = Compatibility(
        name = "火車時刻表",
        packageName = "com.tsumii.trainschedule",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x1E88E5,
        targets = listOf(
            // The patch only touches the public Google Mobile Ads SDK API,
            // so it is expected to work with any app version.
            AppTarget(
                version = null,
                isExperimental = true,
            ),
        ),
    )
}

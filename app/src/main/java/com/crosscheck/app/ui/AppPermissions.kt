package com.crosscheck.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.crosscheck.app.R

/** Runtime permissions the app asks for, with the user-facing reason (SPEC section 4). */
enum class AppPermission(
    val permission: String,
    @StringRes val label: Int,
    @StringRes val why: Int,
    /** Needed for Mode 1 to work at all; drives the warning banner on the Log screen. */
    val core: Boolean,
) {
    RECEIVE_SMS(Manifest.permission.RECEIVE_SMS, R.string.perm_receive_sms, R.string.perm_receive_sms_why, core = true),
    POST_NOTIFICATIONS(
        Manifest.permission.POST_NOTIFICATIONS,
        R.string.perm_post_notifications,
        R.string.perm_post_notifications_why,
        core = false,
    ),
    CAMERA(Manifest.permission.CAMERA, R.string.perm_camera, R.string.perm_camera_why, core = false),
    RECORD_AUDIO(Manifest.permission.RECORD_AUDIO, R.string.perm_record_audio, R.string.perm_record_audio_why, core = false);

    /** POST_NOTIFICATIONS only exists as a runtime permission from API 33. */
    val applicable: Boolean
        get() = this != POST_NOTIFICATIONS || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun isGranted(context: Context): Boolean =
        !applicable || ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun shouldShowRationale(activity: Activity): Boolean =
        ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)

    companion object {
        fun applicable(): List<AppPermission> = entries.filter { it.applicable }
        fun missingCore(context: Context): List<AppPermission> = applicable().filter { it.core && !it.isGranted(context) }
    }
}

fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

package io.github.lnrdo.mytidal.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** The permissions that the app asks for. Which ones apply depends on the version of Android. */
object Permissions {
    /** Notifications show the playback controls and the progress of a download. Android 13 and later ask for them. */
    fun notificationsApply() = Build.VERSION.SDK_INT >= 33

    /** Android 9 and older ask for storage access to save a download in the music folder. Later versions need no permission. */
    fun storageApplies() = Build.VERSION.SDK_INT <= 28

    fun granted(context: Context, permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** The permissions that apply and that the listener has not allowed. */
    fun missing(context: Context): Array<String> = buildList {
        if (notificationsApply() && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
        if (storageApplies() && !granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }.toTypedArray()
}

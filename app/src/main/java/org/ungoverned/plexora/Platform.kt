package org.ungoverned.plexora

import android.content.Context
import android.content.pm.PackageManager

/** True on Android Automotive OS builds running on a head unit. */
fun Context.isAutomotive(): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

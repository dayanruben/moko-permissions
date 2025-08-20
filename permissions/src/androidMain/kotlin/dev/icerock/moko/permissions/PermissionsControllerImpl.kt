/*
 * Copyright 2019 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */

package dev.icerock.moko.permissions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

@Suppress("TooManyFunctions")
class PermissionsControllerImpl(
    private val applicationContext: Context,
) : PermissionsController {
    private val activityHolder = MutableStateFlow<Activity?>(null)

    private val mutex: Mutex = Mutex()

    private val launcherHolder = MutableStateFlow<ActivityResultLauncher<Array<String>>?>(null)

    private val permissionRequestResultFlow = MutableSharedFlow<PermissionRequestResult>(extraBufferCapacity = 1)

    private val key = UUID.randomUUID().toString()

    override fun bind(activity: ComponentActivity) {
        unbindActivity()
        this.activityHolder.value = activity
        val activityResultRegistryOwner = activity as ActivityResultRegistryOwner

        val launcher = activityResultRegistryOwner.activityResultRegistry.register(
            key,
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val isCancelled = permissions.isEmpty()

            if (isCancelled) {
                permissionRequestResultFlow.tryEmit(PermissionRequestResult.CANCELLED)
                return@register
            }

            val success = permissions.values.all { it }

            if (success) {
                permissionRequestResultFlow.tryEmit(PermissionRequestResult.GRANTED)
            } else {
                if (shouldShowRequestPermissionRationale(activity, permissions.keys.first())) {
                    permissionRequestResultFlow.tryEmit(PermissionRequestResult.DENIED)
                } else {
                    permissionRequestResultFlow.tryEmit(PermissionRequestResult.DENIED_ALWAYS)
                }
            }
        }

        launcherHolder.value = launcher

        val observer = object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_DESTROY) {
                    unbindActivity()
                    source.lifecycle.removeObserver(this)
                }
            }
        }
        activity.lifecycle.addObserver(observer)
    }

    override suspend fun providePermission(permission: Permission) {
        mutex.withLock {
            val launcher = awaitActivityResultLauncher()
            val platformPermission = permission.delegate.getPlatformPermission()
            launcher.launch(platformPermission.toTypedArray())

            when (permissionRequestResultFlow.first()) {
                PermissionRequestResult.GRANTED -> Unit
                PermissionRequestResult.DENIED -> throw DeniedException(permission)
                PermissionRequestResult.DENIED_ALWAYS -> throw DeniedAlwaysException(permission)
                PermissionRequestResult.CANCELLED -> throw RequestCanceledException(permission)
            }
        }
    }

    private suspend fun awaitActivityResultLauncher(): ActivityResultLauncher<Array<String>> {
        val activityResultLauncher = launcherHolder.value
        if (activityResultLauncher != null) return activityResultLauncher

        return withTimeoutOrNull(AWAIT_ACTIVITY_TIMEOUT_DURATION_MS) {
            launcherHolder.filterNotNull().first()
        } ?: error(
            "activityResultLauncher is null, `bind` function was never called," +
                " consider calling permissionsController.bind(activity)" +
                " or BindEffect(permissionsController) in the composable function," +
                " check the documentation for more info: " +
                "https://github.com/icerockdev/moko-permissions/blob/master/README.md"
        )
    }

    private suspend fun awaitActivity(): Activity {
        val activity = activityHolder.value
        if (activity != null) return activity

        return withTimeoutOrNull(AWAIT_ACTIVITY_TIMEOUT_DURATION_MS) {
            activityHolder.filterNotNull().first()
        } ?: error(
            "activity is null, `bind` function was never called," +
                " consider calling permissionsController.bind(activity)" +
                " or BindEffect(permissionsController) in the composable function," +
                " check the documentation for more info: " +
                "https://github.com/icerockdev/moko-permissions/blob/master/README.md"
        )
    }

    override suspend fun isPermissionGranted(permission: Permission): Boolean {
        return getPermissionState(permission) == PermissionState.Granted
    }

    @Suppress("ReturnCount")
    override suspend fun getPermissionState(permission: Permission): PermissionState {
        permission.delegate.getPermissionStateOverride(applicationContext)?.let { return it }
        val permissions: List<String> = permission.delegate.getPlatformPermission()
        val status: List<Int> = permissions.map {
            ContextCompat.checkSelfPermission(applicationContext, it)
        }
        val isAllGranted: Boolean = status.all { it == PackageManager.PERMISSION_GRANTED }
        if (isAllGranted) return PermissionState.Granted

        val isAllRequestRationale: Boolean = permissions.all {
            shouldShowRequestPermissionRationale(it)
        }
        return if (isAllRequestRationale) {
            PermissionState.Denied
        } else {
            PermissionState.NotGranted
        }
    }

    private suspend fun shouldShowRequestPermissionRationale(permission: String): Boolean {
        val activity = awaitActivity()
        return shouldShowRequestPermissionRationale(activity, permission)
    }

    private fun shouldShowRequestPermissionRationale(
        activity: Activity,
        permission: String
    ): Boolean {
        return ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            permission
        )
    }

    override fun openAppSettings() {
        val intent = Intent().apply {
            action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            data = Uri.fromParts("package", applicationContext.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        applicationContext.startActivity(intent)
    }

    private fun unbindActivity() {
        launcherHolder.value?.unregister()
        activityHolder.value = null
        launcherHolder.value = null
    }

    private companion object {
        private const val AWAIT_ACTIVITY_TIMEOUT_DURATION_MS = 2000L
    }
}

private enum class PermissionRequestResult {
    GRANTED, DENIED, DENIED_ALWAYS, CANCELLED
}

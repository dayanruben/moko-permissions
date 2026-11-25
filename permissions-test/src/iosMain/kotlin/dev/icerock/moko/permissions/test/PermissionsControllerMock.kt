/*
 * Copyright 2021 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */

package dev.icerock.moko.permissions.test

import dev.icerock.moko.permissions.DeniedException
import dev.icerock.moko.permissions.Permission
import dev.icerock.moko.permissions.PermissionState
import dev.icerock.moko.permissions.PermissionsController

actual abstract class PermissionsControllerMock : PermissionsController {
    actual abstract override suspend fun providePermission(permission: Permission)

    actual abstract override suspend fun isPermissionGranted(permission: Permission): Boolean

    actual companion object
}

actual fun createPermissionControllerMock(
    allow: Set<Permission>,
    granted: Set<Permission>,
): PermissionsControllerMock = object : PermissionsControllerMock() {
    private val grantedPermissions = mutableSetOf<Permission>().apply { addAll(granted) }

    override suspend fun providePermission(permission: Permission) {
        if (allow.contains(permission)) {
            grantedPermissions.add(permission)
            return
        }
        if (grantedPermissions.contains(permission)) return

        throw DeniedException(permission, "mock block permission")
    }

    override suspend fun isPermissionGranted(permission: Permission): Boolean {
        return grantedPermissions.contains(permission)
    }

    override fun openAppSettings() = Unit

    override suspend fun getPermissionState(permission: Permission): PermissionState {
        return if (isPermissionGranted(permission)) {
            PermissionState.Granted
        } else {
            PermissionState.NotDetermined
        }
    }
}

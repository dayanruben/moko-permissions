/*
 * Copyright 2021 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */

package dev.icerock.moko.permissions.test

import dev.icerock.moko.permissions.DeniedException
import dev.icerock.moko.permissions.Permission
import dev.icerock.moko.permissions.PermissionState
import dev.icerock.moko.permissions.PermissionsController

expect abstract class PermissionsControllerMock constructor() : PermissionsController {
    abstract override suspend fun providePermission(permission: Permission)

    abstract override suspend fun isPermissionGranted(permission: Permission): Boolean

    companion object
}

expect fun createPermissionControllerMock(
    allow: Set<Permission> = emptySet(),
    granted: Set<Permission> = emptySet(),
): PermissionsControllerMock

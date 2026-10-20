package com.sysadmindoc.alarmclock.domain

import javax.inject.Inject
import javax.inject.Singleton

enum class ProductionSchedulingRoute {
    LEGACY_ONLY,
    OWNER_EXCLUSIVE
}

/**
 * Central, immutable production scheduling route authority for MAIN_ALARM_SLOT operations.
 *
 * Default: [ProductionSchedulingRoute.LEGACY_ONLY].
 * Immutable by contract: no runtime mutable flags, DataStore toggles, or automatic Owner activation.
 */
@Singleton
class ProductionSchedulingRouteAuthority @Inject constructor() {
    val currentRoute: ProductionSchedulingRoute = ProductionSchedulingRoute.LEGACY_ONLY

    fun isOwnerRouteActive(): Boolean = currentRoute == ProductionSchedulingRoute.OWNER_EXCLUSIVE
}

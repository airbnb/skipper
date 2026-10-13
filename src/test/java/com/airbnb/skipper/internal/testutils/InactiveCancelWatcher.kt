package com.airbnb.skipper.internal.testutils

import com.airbnb.skipper.InMemoryFeatureGate
import com.airbnb.skipper.NoOpMetrics
import com.airbnb.skipper.internal.InFlightActions
import com.airbnb.skipper.internal.InFlightCancelWatcher
import java.time.Duration
import org.mockito.kotlin.mock

/** A real watcher whose flags are off, so it never reads the store; for tests that are not about it. */
fun inactiveCancelWatcher(): InFlightCancelWatcher =
    InFlightCancelWatcher(InFlightActions(), mock(), InMemoryFeatureGate(), NoOpMetrics.INSTANCE, Duration.ofDays(1))

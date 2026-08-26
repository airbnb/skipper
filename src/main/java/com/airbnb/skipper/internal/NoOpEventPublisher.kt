package com.airbnb.skipper.internal

import com.airbnb.skipper.Event
import com.airbnb.skipper.EventPublisher
import javax.inject.Singleton

/** A no-op implementation of the EventBroker interface. */
@Singleton
class NoOpEventPublisher : EventPublisher {
    override fun publishEvent(event: Event) {
        // no-op
    }
}

package com.airbnb.skipper

/**
 * EventPublisher is an interface for publishing events created as part of the Skipper workflow
 * execution flow.
 */
interface EventPublisher {
    /**
     * Publish an event to the event broker.
     *
     * The specific implementation should handle populating the event metadata such as timestamp
     * and others as needed.
     *
     * @param event The event to be published.
     */
    fun publishEvent(event: Event)
}

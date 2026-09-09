package com.airbnb.skipper.util

import com.airbnb.skipper.Event
import com.airbnb.skipper.EventPublisher
import com.airbnb.skipper.SkipperAnnotationNames.UTC_CLOCK
import java.time.Clock
import java.util.LinkedList
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * An in-memory implementation of the EventBroker interface for testing purposes. It stores events
 * in a map keyed by workflow ID and allows publishing and printing events. DO NOT use in production
 * code.
 */
@Singleton
class InMemoryEventPublisher
    @Inject
    constructor(
        @Named(UTC_CLOCK) private val now: Clock
    ) : EventPublisher {
        private val eventsByWorkflowId: MutableMap<String, MutableList<Event>> = HashMap()

        override fun publishEvent(event: Event) {
            if (!eventsByWorkflowId.containsKey(event.workflowId)) {
                eventsByWorkflowId[event.workflowId] = LinkedList()
            }
            eventsByWorkflowId[event.workflowId]!!
                .add(event.toBuilder().time(now.instant()).build())
        }

        fun print(workflowId: String): String {
            val events =
                eventsByWorkflowId[workflowId]
                    ?: return "No events found for workflow ID: $workflowId"

            val sb = StringBuilder()
            sb.append("Events for workflow ID: ").append(workflowId).append("\n")
            for (event in events) {
                val evt = String.format("%s\t%s", event.time, event.type)
                sb.append(evt)
                if (event.description != null) {
                    sb.append(String.format(" (%s)", event.description))
                }
                sb.append("\n")
            }
            return sb.toString()
        }
    }

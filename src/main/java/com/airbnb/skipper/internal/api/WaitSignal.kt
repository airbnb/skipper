package com.airbnb.skipper.internal.api

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Duration

/**
 * A special type of exception that does NOT represent an error, but rather a signal that send
 * control back to the client whenever the workflow reaches a conditional wait that has not been
 * fulfilled yet, and therefore the workflow execution cannot advance any further at this time.
 *
 * It is very important that the client code DOES NOT CATCH OR THROW THIS EXCEPTION under any
 * circumstances.
 */
@JsonAutoDetect(
    fieldVisibility = JsonAutoDetect.Visibility.NONE,
    setterVisibility = JsonAutoDetect.Visibility.NONE,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    isGetterVisibility = JsonAutoDetect.Visibility.NONE,
    creatorVisibility = JsonAutoDetect.Visibility.NONE,
)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WaitSignal
    @JsonCreator
    constructor(
        @get:JsonProperty("waitDuration") val waitDuration: Duration,
    ) : Error("WaitSignal")

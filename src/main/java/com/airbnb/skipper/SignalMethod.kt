package com.airbnb.skipper

/**
 * Annotates a workflow method as a signal receiver, which can be
 * used to pass input into a running workflow, typically when it is
 * in a conditional waiting state.
 *
 * @property persist When true, the signal (method name, serialized input and request context) is
 *   durably persisted *before* the signal method executes, so that it can be manually replayed
 *   later if execution fails or is otherwise lost. Persisted signals are tracked through a
 *   `PENDING -> EXECUTED / FAILED` lifecycle and can be listed and replayed via the admin API.
 *   Opting in requires the signal's argument (if any) to be serializable. Defaults to false, in
 *   which case the signal is executed exactly as before with no persistence.
 */
annotation class SignalMethod(
    val persist: Boolean = false,
)

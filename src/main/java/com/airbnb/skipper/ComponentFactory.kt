package com.airbnb.skipper

/**
 * Factory interface for creating Skipper components from a [SkipperConfig]. Used on [SkipperConfig]
 * fields to allow callers to plug in alternative implementations (e.g. a remote store vs MySQL storage).
 *
 * @param T the type of component this factory creates
 */
fun interface ComponentFactory<T> {
    fun create(config: SkipperConfig): T
}

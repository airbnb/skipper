package com.airbnb.skipper

/**
 * Action helper backing [Workflow.version].
 *
 * The `@Execute`-annotated method runs once per (workflow, changeId) tuple and is checkpointed as a
 * named checkpoint with name `"version:{changeId}"`. On first execution the method returns
 * `maxVersion`, which becomes the persisted result; on replay the persisted value is returned
 * regardless of the current `maxVersion` argument, preserving the version that was active when the
 * workflow instance was first created.
 *
 * This action class reuses the named-checkpoint infrastructure (see [Actions.named]) and therefore
 * introduces no new storage path: the persisted version is just the cached return value of a named
 * checkpointed action.
 */
open class VersionGateHelpers : Actions() {
    /**
     * Returns `maxVersion` on first execution. On replay the persisted return value is returned by
     * the engine without re-invoking this method.
     *
     * @param changeId the migration identifier (used as the named checkpoint suffix)
     * @param minVersion the lowest version still supported in code (caller-validated)
     * @param maxVersion the latest version known to the current code
     * @return `maxVersion` on fresh execution
     */
    @Execute
    open fun getVersion(
        changeId: String,
        minVersion: Int,
        maxVersion: Int
    ): Int = maxVersion
}

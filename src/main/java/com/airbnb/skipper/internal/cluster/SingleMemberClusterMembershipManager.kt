package com.airbnb.skipper.internal.cluster

import com.airbnb.skipper.ComponentFactory
import com.airbnb.skipper.SkipperConfig
import io.vavr.collection.List
import javax.inject.Singleton

/**
 * No-op implementation of ClusterMembershipManager that disables cluster membership functionality.
 *
 * This implementation always reports a single member (itself) and doesn't perform any actual
 * cluster coordination. Used as the default when no cluster membership is configured or as a
 * fallback when cluster membership is disabled.
 */
@Singleton
class SingleMemberClusterMembershipManager : ClusterMembershipManager {
    private var currentMemberId: String? = null

    override fun getClusterName(): String = ""

    override fun getActiveMemberIds(): List<String> {
        val memberId = currentMemberId
        return if (memberId != null) List.of(memberId) else List.empty()
    }

    override fun registerMember(memberId: String) {
        this.currentMemberId = memberId
    }

    override fun unregisterMember(memberId: String) {
        if (memberId == this.currentMemberId) {
            this.currentMemberId = null
        }
    }

    override fun getCurrentMemberId(): String? = currentMemberId

    override fun isRegistered(): Boolean = currentMemberId != null

    @Throws(ClusterMembershipException::class)
    override fun start() {
        // No-op
    }

    @Throws(ClusterMembershipException::class)
    override fun stop() {
        this.currentMemberId = null
    }

    class Factory : ComponentFactory<ClusterMembershipManager> {
        override fun create(config: SkipperConfig): ClusterMembershipManager = SingleMemberClusterMembershipManager()
    }
}

package com.airbnb.skipper.internal.cluster;

import io.vavr.collection.List;

/**
 * Generic interface for managing cluster membership in distributed systems.
 *
 * <p>This interface provides functionality to track active members in a cluster,
 * register/unregister the current instance, and receive notifications when membership changes
 * occur.
 *
 * <p>Implementations should handle member failures gracefully (e.g., using ephemeral nodes in
 * ZooKeeper) and provide consistent ordering of members across all cluster instances.
 */
public interface ClusterMembershipManager {
  /** Gets the name of the cluster of which the current instance is a member. */
  String getClusterName();

  /**
   * Get the current list of active cluster members.
   *
   * <p>The returned list should be consistently ordered across all cluster members to enable
   * deterministic partitioning algorithms.
   *
   * @return ordered list of active member identifiers
   */
  List<String> getActiveMemberIds();

  /**
   * Register the current instance as an active cluster member.
   *
   * <p>This should be called during service startup. The member will remain active until explicitly
   * unregistered or the process fails.
   *
   * @param memberId unique identifier for this cluster member
   * @throws ClusterMembershipException if registration fails
   */
  void registerMember(String memberId);

  /**
   * Unregister the current instance from the cluster.
   *
   * <p>This should be called during graceful shutdown to immediately remove this member from the
   * active list.
   *
   * @param memberId the member identifier to unregister
   * @throws ClusterMembershipException if unregistration fails
   */
  void unregisterMember(String memberId);

  /**
   * Get the unique identifier for the current cluster member.
   *
   * @return the member ID for this instance, or null if not registered
   */
  String getCurrentMemberId();

  /**
   * Check if this instance is currently registered as a cluster member.
   *
   * @return true if registered, false otherwise
   */
  boolean isRegistered();

  /**
   * Start the cluster membership manager.
   *
   * <p>This initializes connections and starts monitoring cluster state. Must be called before
   * other operations.
   */
  void start();

  /**
   * Stop the cluster membership manager and clean up resources.
   *
   * <p>This will automatically unregister the current member if registered.
   */
  void stop();

  /**
   * Explicitly refresh the membership information from the underlying store.
   *
   * <p>This can be used to force a refresh outside of normal caching TTL windows. Implementations
   * may choose to be no-ops if they don't use caching.
   */
  default void refreshMembershipCache() {
    // Default no-op implementation for backward compatibility
  }
}

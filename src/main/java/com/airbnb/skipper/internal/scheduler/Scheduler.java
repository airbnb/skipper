package com.airbnb.skipper.internal.scheduler;

import com.airbnb.skipper.internal.cluster.BucketRange;
import io.vavr.collection.List;
import io.vavr.control.Option;
import java.time.Instant;

/** The scheduler is a simple priority queue */
public interface Scheduler {

  /**
   * Enqueue a new task to the scheduler.
   *
   * <p>The implementation must guarantee that the task will NOT be available for fetching before
   * `runAfter`. In the event there are multiple task elements with the same payload.dedupToken, the
   * last write will win and will replace the existing task.
   *
   * @param request The request to schedule a task.
   * @return A completable future with the task that was just created.
   * @param <T> The type of payload.
   */
  <T> Task<T> schedule(ScheduleRequest<T> request);

  /**
   * Fetches elements from the queue that are ready to be delivered.
   *
   * <p>This method will only get tasks whose `runAfter` is in the past AND that don't have an
   * active lease (meaning they have already been delivered and currently in processing), therefore
   * their status is PENDING.
   *
   * <p>The implementation WILL NOT remove or dequeue the elements automatically, instead, it will
   * hold a lease for a certain amount of time to allow for the client to process the tasks AND THEN
   * the client must remove them if the processing was successful.
   *
   * @param limit The maximum number of tasks to fetch
   * @return A list of tasks ready to be processed.
   * @param <T> The type of payload.
   */
  <T> List<Task<T>> fetch(int limit);

  /**
   * Fetches elements from the queue that are ready to be delivered, filtered by bucket partition.
   *
   * <p>This method is identical to {@link #fetch(int)} but only returns tasks whose task ID hash
   * falls within the specified bucket range partition. This enables distributed processing where
   * multiple workers can fetch disjoint sets of tasks to eliminate contention.
   *
   * <p>Implementations should:
   *
   * <ul>
   *   <li>For MySQL: Use SQL filtering with hash functions for efficiency
   *   <li>For a remote store without server-side hash filtering: Fetch tasks normally and filter
   *       in-memory
   * </ul>
   *
   * @param limit The maximum number of tasks to fetch
   * @param partition The bucket range to filter tasks by (null means no filtering)
   * @return A list of tasks ready to be processed within the partition
   * @param <T> The type of payload.
   */
  default <T> List<Task<T>> fetch(int limit, BucketRange partition) {
    // Default implementation falls back to unpartitioned fetch for backward compatibility
    return fetch(limit);
  }

  /**
   * Remove all tasks based on their IDs.
   *
   * <p>In case these tasks have any task depending on them, those tasks will be up for processing
   * so long as their `runAfter` is current. The implementation can choose to physically remove the
   * task from the queue or just mark it as COMPLETED. If the task is already in a terminal state or
   * there is no longer a task with the given ID, this will be a no-op.
   *
   * @param task The task to remove
   */
  <T> void remove(Task<T> task);

  /**
   * Mark a task as FAILED.
   *
   * @param task The task to mark as failed
   * @param statusMessage The status message to set for debugging purposes
   */
  <T> void markAsFailed(Task<T> task, String statusMessage);

  /**
   * Re-schedule the tasks to be executed at a later time due to a retryable error. Error count of
   * the task will be incremented by 1.
   *
   * <p>If there is no task with the given ID, this will throw an IllegalArgumentException.
   *
   * @param task The task to reschedule
   * @param runAfter The time to reschedule the task for
   */
  <T> void rescheduleForRetry(Task<T> task, Instant runAfter);

  /**
   * Renew the lease on a task.
   *
   * <p>Renew the lease on a PENDING task to prevent it from being picked up by another worker. If
   * the task's status is not PENDING or RUNNING, this will throw IllegalStateException. Similarly,
   * if the task's version is stale, meaning other thread already took a lease on it, this will
   * throw OptimisticLockException.
   *
   * @param task The task to renew the lease on
   * @return The task with the renewed lease
   */
  <T> Task<T> renewLease(Task<T> task);

  /**
   * Get a task by its ID. This won't dequeue the task and is only used for debugging purposes.
   *
   * @param taskId The ID of the task to get
   * @return The task with the given ID, if it exists.
   * @param <T> The type of payload.
   */
  <T> Option<Task<T>> getTask(String taskId);

  /**
   * Requeue a failed task.
   *
   * <p>After this method completes successfully, the task should be scheduled for immediate retry.
   * Retry count will also be cleared. The task must be in FAILED state, otherwise this method will
   * fail.
   *
   * @param taskId The ID of the task to requeue
   */
  void requeueFailedTask(String taskId);

  /**
   * Get all tasks that have failed.
   *
   * @return A list of failed tasks.
   * @param <T> The type of payload.
   */
  <T> List<Task<T>> getFailedTasks();

  /**
   * Count the number of tasks that are ready to be processed.
   *
   * <p>By "ready to be processed", we mean tasks that have a `runAfter` in the past and are not
   * currently being processed and are not in a terminal state.
   *
   * @return The number of tasks in the queue ready to be processed.
   */
  long countBacklog();

  /**
   * Returns the absolute number of tasks in the queue, regardless of their state. This is mostly
   * used for testing.
   *
   * @return The number of tasks in the queue.
   */
  long realSize();
}

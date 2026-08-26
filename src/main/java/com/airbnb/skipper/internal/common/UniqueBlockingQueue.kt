package com.airbnb.skipper.internal.common

import io.vavr.Tuple2
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import org.slf4j.LoggerFactory

/**
 * Blocking queue implementation that ensures that only one task with the same id can be added to
 * the queue. This is not intended to be a full implementation of a blocking queue, but rather a
 * composition of a blocking queue and a set to ensure uniqueness.
 */
class UniqueBlockingQueue<T> {
    private val queue: BlockingQueue<Tuple2<String, T>> = LinkedBlockingQueue()
    private val taskIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()

    /**
     * Add a task to the queue. If a task with the same id is already in the queue, this will be a
     * no-op.
     *
     * @param item The element to add to the queue
     * @param id The id of the task. This is used to determine if the task is already in the queue.
     * @return true if the task was added to the queue, false if the task was already in the queue
     */
    fun add(
        item: T,
        id: String
    ): Boolean {
        lock.lock()
        try {
            if (!taskIds.contains(id)) {
                if (!queue.offer(Tuple2(id, item))) {
                    return false
                }
                taskIds.add(id)
                notEmpty.signal()
            } else {
                return false
            }
            log.debug("added task with id: {}. current queue size is {}", id, queue.size)
        } finally {
            lock.unlock()
        }
        return true
    }

    fun contains(id: String): Boolean {
        return taskIds.contains(id)
    }

    /**
     * Take the element from the head of the queue. This will block until an element is available.
     *
     * This method will throw a RetryableError if the thread is interrupted while waiting.
     *
     * @return The element from the queue
     */
    fun take(): T {
        lock.lock()
        try {
            log.debug("taking task from queue. current queue size is {}", queue.size)
            while (queue.isEmpty()) {
                // wait for an element to be added to the queue and release the lock.
                notEmpty.await()
            }
            log.debug("task available in queue")
            val item = queue.poll() ?: throw IllegalStateException("queue is empty")
            taskIds.remove(item._1())
            return item._2()
        } catch (e: InterruptedException) {
            throw RuntimeException(e) // TODO: add error message
        } finally {
            lock.unlock()
        }
    }

    /**
     * Get the number of elements in the queue.
     *
     * @return The number of elements in the queue
     */
    fun size(): Int {
        return queue.size
    }

    companion object {
        private val log = LoggerFactory.getLogger(UniqueBlockingQueue::class.java)
    }
}

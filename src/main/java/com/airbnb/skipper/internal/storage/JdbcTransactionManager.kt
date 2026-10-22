package com.airbnb.skipper.internal.storage

import com.airbnb.skipper.ComponentFactory
import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.internal.common.SneakyThrow
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.function.Function
import java.util.function.Supplier
import javax.inject.Singleton
import javax.sql.DataSource
import kotlin.random.Random
import org.flywaydb.core.Flyway
import org.sqlite.SQLiteDataSource

/**
 * Manages connections and transactions over a JDBC [DataSource].
 *
 * This is the backend-agnostic transaction logic shared by the MySQL and SQLite backends: a
 * [DataSource], a [ThreadLocal] holding the current transaction's [Connection], and a nested-aware
 * [execute] that begins/commits/rolls-back/closes only on the outermost transaction.
 *
 * Backend-specific DataSource resolution lives in the nested [MySqlFactory] and [SqliteFactory]
 * [ComponentFactory] implementations, which the MySQL and SQLite stores/schedulers use to obtain a
 * configured manager.
 */
@Singleton
class JdbcTransactionManager
    @JvmOverloads
    constructor(
        private val ds: DataSource,
        /** How long a statement keeps retrying transient lock contention before the failure propagates. */
        private val lockRetryBudget: Duration = DEFAULT_LOCK_RETRY_BUDGET,
    ) {
        private val currentTransaction = ThreadLocal<Connection>()

        /**
         * Get a connection from the pool. This method should be used for non-transactional queries.
         *
         * @return a connection from the pool
         */
        fun getConnection(): Connection = ds.connection

        /**
         * Execute a lambda function within a transaction. If a transaction is already in progress, the
         * existing transaction will be used. If no transaction is in progress, a new transaction will be
         * created and committed after the lambda is executed.
         *
         * In case the lambda throws any exception, the transaction will be rolled back and the exception
         * will be rethrown.
         *
         * Outermost transactions are retried for up to [lockRetryBudget] when they fail with transient
         * lock contention (see [isTransientLockContention]). The SQLite backend serves one logical
         * database from a shared cache across connections, and the engine writes from background
         * scheduler / executor threads concurrently with the calling thread, so a write can lose the
         * race for the table lock and fail with `SQLITE_LOCKED` / `SQLITE_BUSY`. Because a failed attempt
         * is rolled back before the retry, re-running the (DB-only, side-effect-free) lambda on a fresh
         * transaction is safe. Nested calls do not retry — the outermost transaction owns the retry.
         * Backends whose errors never match [isTransientLockContention] (e.g. MySQL) are unaffected.
         *
         * @param lambda the lambda function to execute. The queries inside the lambda must use the
         *     connection passed as an argument to the lambda.
         * @param T the return type of the lambda function
         * @return the result of the lambda function
         */
        fun <T> execute(lambda: Function<Connection, T>): T {
            // Nested calls join the current (outermost) transaction, which owns the
            // begin/commit/rollback/close lifecycle and the retry-on-contention loop below.
            if (inOuterTransaction()) {
                try {
                    return lambda.apply(currentTransaction.get())
                } catch (
                    @Suppress("TooGenericExceptionCaught") ex: Throwable
                ) {
                    throw SneakyThrow.sneakyThrow(ex)
                }
            }

            val retry = LockRetry()
            while (true) {
                try {
                    begin()
                    val result = lambda.apply(currentTransaction.get())
                    currentTransaction.get().commit()
                    return result
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Throwable
                ) {
                    rollbackCurrentTransactionQuietly()
                    retry.rethrowUnlessRetryable(e)
                } finally {
                    closeCurrentTransaction()
                }
                // Reached only when the attempt failed with retryable lock contention and was not rethrown.
                retry.backoff()
            }
        }

        /**
         * Runs [action], retrying it for up to [lockRetryBudget] when it fails with transient lock
         * contention (see [isTransientLockContention]), for work that manages its own connection via
         * [getConnection] instead of running inside [execute] — e.g. the schedulers' auto-commit fetch
         * paths and the cluster membership reads. The same SQLite `SQLITE_LOCKED` / `SQLITE_BUSY`
         * conditions that [execute] retries can hit any statement when several Skipper instances share
         * one database; [action] must therefore be safe to re-run from the top (which holds for the
         * versioned, optimistic writes Skipper issues: a repeat either finds the row already moved and
         * skips it, or moves it once). Backends whose errors never match (e.g. MySQL) run [action]
         * exactly once.
         *
         * Only retry work that has not yet produced a side effect the caller depends on seeing (e.g. a
         * SELECT phase); a write phase should handle contention per statement instead, as the SQLite
         * scheduler's lease loop does, so that nothing already written is dropped from the result.
         */
        fun <T> retryOnTransientLockContention(action: Supplier<T>): T {
            val retry = LockRetry()
            while (true) {
                try {
                    return action.get()
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Throwable
                ) {
                    retry.rethrowUnlessRetryable(e)
                }
                retry.backoff()
            }
        }

        /**
         * One statement's retries on lock contention. Retries are bounded by time, not by count: a
         * writer stalled mid-transaction on a busy host can hold a table lock for far longer than a few
         * quick retries cover, and a reader has nothing to gain from giving up before that.
         */
        private inner class LockRetry {
            private var attempt = 0
            private var deadlineNanos = 0L

            fun rethrowUnlessRetryable(error: Throwable) {
                if (!isTransientLockContention(error)) {
                    throw SneakyThrow.sneakyThrow(error)
                }
                val now = System.nanoTime()
                if (deadlineNanos == 0L) {
                    deadlineNanos = now + lockRetryBudget.toNanos()
                } else if (now >= deadlineNanos) {
                    throw SneakyThrow.sneakyThrow(error)
                }
                attempt++
            }

            /** Sleeps a growing, randomized back-off so contending writers do not retry in lock-step. */
            fun backoff() {
                val base = (RETRY_BASE_BACKOFF_MILLIS shl minOf(attempt - 1, MAX_BACKOFF_DOUBLINGS)).coerceAtMost(RETRY_MAX_BACKOFF_MILLIS)
                try {
                    Thread.sleep(base + Random.nextLong(RETRY_JITTER_MILLIS + 1))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw SneakyThrow.sneakyThrow(e)
                }
            }
        }

        /**
         * Rolls back the current transaction, swallowing any rollback failure so it cannot mask the
         * original error that triggered the rollback.
         */
        private fun rollbackCurrentTransactionQuietly() {
            try {
                currentTransaction.get()?.rollback()
            } catch (
                @Suppress("TooGenericExceptionCaught") ignored: Throwable
            ) {
                // Best-effort: the original failure is the meaningful one.
            }
        }

        /** Closes the current transaction's connection (best-effort) and clears the thread-local. */
        private fun closeCurrentTransaction() {
            try {
                currentTransaction.get()?.close()
            } catch (
                @Suppress("TooGenericExceptionCaught") ignored: Throwable
            ) {
                // Best-effort close; nothing actionable on failure.
            } finally {
                currentTransaction.remove()
            }
        }

        /**
         * Returns true if [error] (or any cause in its chain) is a transient lock-contention failure
         * worth retrying — SQLite's `SQLITE_LOCKED` (incl. `SQLITE_LOCKED_SHAREDCACHE`) and
         * `SQLITE_BUSY` conditions. Matched on the exception message so it is robust across the driver's
         * primary / extended result codes, and so other backends (whose messages do not contain these
         * tokens) are never retried.
         */
        fun isTransientLockContention(error: Throwable): Boolean {
            var cause: Throwable? = error
            while (cause != null) {
                val message = cause.message
                if (message != null &&
                    (message.contains("SQLITE_LOCKED") || message.contains("SQLITE_BUSY"))
                ) {
                    return true
                }
                cause = cause.cause
            }
            return false
        }

        private fun begin() {
            try {
                if (!inOuterTransaction()) {
                    currentTransaction.set(getConnection())
                    currentTransaction.get().autoCommit = false
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") ex: Throwable
            ) {
                throw SneakyThrow.sneakyThrow(ex)
            }
        }

        private fun inOuterTransaction(): Boolean = currentTransaction.get() != null

        companion object {
            /** Default [lockRetryBudget]: how long a statement keeps retrying transient lock contention. */
            @JvmField
            val DEFAULT_LOCK_RETRY_BUDGET: Duration = Duration.ofSeconds(10)

            /** Back-off before the first retry; it doubles on each further retry. */
            private const val RETRY_BASE_BACKOFF_MILLIS = 5L

            /** Doublings after which the back-off stops growing. */
            private const val MAX_BACKOFF_DOUBLINGS = 5

            /** Longest back-off between retries, before jitter. */
            private const val RETRY_MAX_BACKOFF_MILLIS = 100L

            /** Upper bound (inclusive) of the random jitter added to each retry back-off. */
            private const val RETRY_JITTER_MILLIS = 15L
        }

        /**
         * Builds a [JdbcTransactionManager] over the MySQL [DataSource] supplied on the
         * [SkipperConfig.mySqlDataSource] field. Used by the MySQL-backed store and scheduler.
         */
        class MySqlFactory : ComponentFactory<JdbcTransactionManager> {
            override fun create(config: SkipperConfig): JdbcTransactionManager {
                val dataSource =
                    config.mySqlDataSource
                        ?: throw IllegalStateException("mySqlDataSource must be set on SkipperConfig")
                return JdbcTransactionManager(dataSource)
            }
        }

        /**
         * Builds a [JdbcTransactionManager] over the SQLite [DataSource] resolved from
         * [SkipperConfig.sqliteDataSource], ensuring the schema exists first.
         *
         * If [SkipperConfig.sqliteDataSource] is set, that caller-supplied DataSource is used directly
         * (a durable on-disk database is configured by passing a `SQLiteDataSource` with a
         * `jdbc:sqlite:<path>` URL). When it is `null`, the default shared in-memory DataSource is built
         * (see [buildSharedInMemoryDataSource]).
         *
         * In every case the schema is bootstrapped via Flyway (the `db/sqlite/V*.sql` SQLite-dialect
         * migrations) before the manager is returned, so the five Skipper tables exist before any store or
         * scheduler touches the database. This mirrors how the MySQL backend uses Flyway, and lets the
         * in-memory default self-bootstrap with zero external infrastructure.
         */
        class SqliteFactory
            @JvmOverloads
            constructor(
                /** Overrides [SkipperConfig.sqliteDataSource] when set; see [fileDataSource]. */
                private val dataSourceOverride: DataSource? = null,
            ) : ComponentFactory<JdbcTransactionManager> {
                override fun create(config: SkipperConfig): JdbcTransactionManager {
                    val dataSource = dataSourceOverride ?: config.sqliteDataSource ?: buildSharedInMemoryDataSource()
                    migrateSqliteSchema(dataSource, config.tablePrefix)
                    return JdbcTransactionManager(dataSource)
                }

                /**
                 * Builds the default shared in-memory SQLite DataSource.
                 *
                 * A plain `jdbc:sqlite::memory:` URL gives every connection its own private database, which
                 * breaks Skipper's multi-store-over-one-database model. We instead use a uniquely-named
                 * shared-cache in-memory URL (`jdbc:sqlite:file:<uuid>?mode=memory&cache=shared`) so that all
                 * connections from this DataSource see the same database. The UUID makes each built default
                 * DataSource distinct, so independent (e.g. parallel) consumers do not collide on one shared
                 * in-memory database.
                 *
                 * A shared-cache in-memory SQLite database is destroyed when its last connection closes. To
                 * keep it alive for the DataSource's lifetime, we open one keep-alive connection and never
                 * close it — it is intentionally leaked for the life of the process / DataSource. The pooled
                 * connections handed out by `getConnection()` may open and close freely without losing the
                 * database's contents.
                 */
                private fun buildSharedInMemoryDataSource(): DataSource {
                    val url = "jdbc:sqlite:file:" + UUID.randomUUID() + "?mode=memory&cache=shared"
                    val dataSource = SQLiteDataSource()
                    dataSource.url = url
                    try {
                        // Held open for the DataSource's lifetime so the shared in-memory database is not destroyed
                        // when individual pooled connections close. Intentionally never closed.
                        @Suppress("UNUSED_VARIABLE")
                        val keepAlive = dataSource.connection
                    } catch (e: java.sql.SQLException) {
                        throw SneakyThrow.sneakyThrow(e)
                    }
                    return dataSource
                }

                companion object {
                    /** A [DataSource] for a durable on-disk SQLite database at [path] (`jdbc:sqlite:<path>`). */
                    @JvmStatic
                    fun fileDataSource(path: String): DataSource {
                        val dataSource = SQLiteDataSource()
                        dataSource.url = "jdbc:sqlite:$path"
                        return dataSource
                    }

                    /**
                     * Bootstraps the SQLite schema on [dataSource] via Flyway, pointed at the SQLite-dialect
                     * migrations (`classpath:db/sqlite`). Flyway tracks applied migrations, so this is idempotent
                     * and safe to call on every factory build.
                     *
                     * The migration DDL is templated with the `${tablePrefix}` Flyway placeholder, so
                     * [tablePrefix] must be supplied here: Flyway's `placeholderReplacement` defaults to `true`,
                     * and resolving the migration (even on an up-to-date, no-op run) fails with "No value
                     * provided for placeholder: tablePrefix" if it is missing.
                     */
                    private fun migrateSqliteSchema(
                        dataSource: DataSource,
                        tablePrefix: String
                    ) {
                        val flyway = Flyway()
                        flyway.setDataSource(dataSource)
                        flyway.setLocations("classpath:db/sqlite")
                        flyway.setPlaceholders(mapOf("tablePrefix" to tablePrefix))
                        flyway.migrate()
                    }
                }
            }
    }

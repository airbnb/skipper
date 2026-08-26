package com.airbnb.skipper

/**
 * Contains constants for annotation names used across the Skipper workflow system. These constants are
 * utilized to reference specific system components via annotations, ensuring consistency and reducing
 * the likelihood of errors due to misspellings or misreferences in the code.
 *
 * @property UTC_CLOCK The name of the annotation used to inject or reference the universal time-coordinated (UTC)
 * clock component. This clock is crucial for all scheduling and other time-related operations within the system.
 * It is typically used in scenarios where accurate and consistent timing across different system components is
 * essential.
 *
 * @property SKIPPER_WORKFLOW_FACTORY The name of the annotation used to inject the workflow factory. This component
 * is crucial for creating instances of workflows, and referencing it consistently ensures that workflow management
 * can be centrally controlled and updated.
 */
object SkipperAnnotationNames {
    const val UTC_CLOCK = "UtcClock"
    const val SKIPPER_WORKFLOW_FACTORY = "SkipperWorkflowFactory"
    const val DEFAULT_RETRY_STRATEGY = "DefaultRetryStrategy"
    const val SCHEDULER_LEASE_DURATION = "SchedulerLeaseDuration"
    const val SCHEDULER_TASK_MAX_RETRIES = "SchedulerTaskMaxRetries"
    const val UNEXPECTED_ERROR_RETRY_DELAY = "UnexpectedErrorRetryDelay"
    const val RESULT_POLLING_TIME_LIMIT = "ResultPollingTimeLimit"
    const val RESULT_POLLING_SLEEP_DURATION = "ResultPollingSleepDuration"
    const val TENANT = "Tenant"
    const val PAGE_SIZE = "PageSize"
    const val DEFAULT_CHECKPOINT_MODE = "DefaultCheckpointMode"
    const val SERVICE_NAME = "ServiceName"
    const val SKIPPER_MAIN_THREAD_POOL = "SkipperMainThreadPool"
    const val SCHEDULER_TASK_HANDLER_POOL = "SchedulerTaskHandlerPool"
    const val MYSQL_CONFIG = "MysqlConfig"
    const val TASK_HANDLER_MAP = "TaskHandlerMap"
    const val LEASE_RENEWAL_GRACE_PERIOD = "LeaseExpireGracePeriod"
    const val COMPENSATION_RETRY_STRATEGY = "CompensationRetryStrategy"
    const val CLUSTER_MEMBERSHIP_HEARTBEAT_INTERVAL = "ClusterMembershipHeartbeatInterval"
    const val CLUSTER_MEMBER_NAME = "ClusterMemberName"
    const val GRACEFUL_SHUTDOWN_TIMEOUT = "GracefulShutdownTimeout"

    /**
     * The name of the annotation used to inject the table-name prefix applied to Skipper's
     * migrations and runtime queries. See [com.airbnb.skipper.SkipperConfig.tablePrefix].
     */
    const val TABLE_PREFIX = "TablePrefix"
}

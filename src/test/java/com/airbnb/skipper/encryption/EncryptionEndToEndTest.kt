package com.airbnb.skipper.encryption

import com.airbnb.skipper.Actions
import com.airbnb.skipper.Compensate
import com.airbnb.skipper.Execute
import com.airbnb.skipper.NonRetryableError
import com.airbnb.skipper.QueryMethod
import com.airbnb.skipper.SignalMethod
import com.airbnb.skipper.SkipperConfig
import com.airbnb.skipper.StateField
import com.airbnb.skipper.Workflow
import com.airbnb.skipper.WorkflowMethod
import com.airbnb.skipper.api.WorkflowInstanceStatusView
import com.airbnb.skipper.internal.scheduler.sqlite.SqliteScheduler
import com.airbnb.skipper.internal.storage.sqlite.SqliteWorkflowStore
import com.airbnb.skipper.testutils.Bind
import com.airbnb.skipper.testutils.TestEncryptor
import com.airbnb.skipper.testutils.WorkflowTest
import com.airbnb.skipper.testutils.workflow
import com.airbnb.skipper.testutils.workflowBuilder
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Runs `@Encrypted` actions and state fields through a real runtime on a file-backed SQLite
 * database, and reads the database directly to check what was stored.
 */
class EncryptionEndToEndTest : WorkflowTest() {
    @TempDir
    lateinit var dir: Path

    @Bind
    val bank: FakeBank = FakeBank()

    private val encryptor = FlakyEncryptor(TestEncryptor())

    private lateinit var dbPath: String

    override fun configure(config: SkipperConfig) {
        dbPath = dir.resolve("skipper.db").toString()
        config.workflowStore = SqliteWorkflowStore.Factory(dbPath)
        config.scheduler = SqliteScheduler.Factory(dbPath)
        config.encryptors = mapOf(Encrypted.DEFAULT to encryptor)
        config.taskUnexpectedErrorRetryDelay = Duration.ofMillis(50)
    }

    @Test
    fun storesEncryptedValuesAndDecryptsThemOnResume() {
        val workflow = workflowBuilder<AccountWorkflow>().build()
        helper.expectWaitSignal { workflow.open(CUSTOMER).get() }
        helper.expectWorkflowToWait()

        // The checkpoints of both encrypted actions (one sync, one async) hold no plaintext:
        // not the compensable action's input, and neither action's result.
        val checkpoints = checkpointColumns()
        assertThat(checkpoints).hasSize(2)
        checkpoints.forEach { assertThat(it).contains(EncryptedValue::class.java.name).doesNotContain(CUSTOMER, "ACCOUNT-", "BALANCE-") }
        // The encrypted state field is ciphertext; the plain one next to it is untouched.
        assertThat(stateColumn()).contains(EncryptedValue::class.java.name).doesNotContain("TAXID-").contains("LABEL-$CUSTOMER")

        // Queries see the decrypted state.
        assertThat(workflow<AccountWorkflow>().currentTaxId()).isEqualTo("TAXID-$CUSTOMER")

        // The admin UI shows a placeholder instead of the values or their ciphertext.
        val admin = runtime.adminResource.get().getWorkflowInstance(workflowId).entity as String
        assertThat(admin).contains("[encrypted: default]").doesNotContain("TAXID-", "ACCOUNT-", "BALANCE-")

        // Resuming replays both checkpoints, decrypted, instead of running the actions again.
        workflow<AccountWorkflow>().decide(true)
        assertThat(helper.waitForWorkflowToComplete().status).isEqualTo(WorkflowInstanceStatusView.COMPLETED)
        assertThat(workflow<AccountWorkflow>().open(CUSTOMER).get())
            .isEqualTo("ACCOUNT-$CUSTOMER|BALANCE-ACCOUNT-$CUSTOMER|TAXID-$CUSTOMER")
        assertThat(bank.opened.get()).isEqualTo(1)
    }

    @Test
    fun compensationReceivesTheDecryptedInputAndResult() {
        val workflow = workflowBuilder<AccountWorkflow>().build()
        helper.expectWaitSignal { workflow.open(CUSTOMER).get() }
        helper.expectWorkflowToWait()

        workflow<AccountWorkflow>().decide(false)

        helper.waitForWorkflowToReachStatus(WorkflowInstanceStatusView.COMPENSATION_COMPLETED)
        assertThat(bank.closed).containsExactly("$CUSTOMER=ACCOUNT-$CUSTOMER")
    }

    @Test
    fun anEncryptionFailureRetriesTheExecutionInsteadOfFailingTheWorkflow() {
        encryptor.encryptFailuresLeft.set(1)

        startInBackground()

        helper.expectWorkflowToWait()
        assertThat(encryptor.failures.get()).isEqualTo(1)
        // The action ran again on the retry, since its checkpoint was never stored.
        assertThat(bank.opened.get()).isEqualTo(2)
        checkpointColumns().forEach { assertThat(it).doesNotContain(CUSTOMER, "ACCOUNT-", "BALANCE-") }
        assertThat(stateColumn()).doesNotContain("TAXID-")
    }

    @Test
    fun aDecryptionFailureRetriesTheResumeInsteadOfFailingTheWorkflow() {
        startInBackground()
        helper.expectWorkflowToWait()
        encryptor.decryptFailuresLeft.set(2)

        // Passing the deadline resumes the workflow, which first has to decrypt its state.
        clock.fastForward(Duration.ofHours(2))

        assertThat(helper.waitForWorkflowToComplete().status).isEqualTo(WorkflowInstanceStatusView.COMPLETED)
        assertThat(encryptor.failures.get()).isEqualTo(2)
        assertThat(workflow<AccountWorkflow>().open(CUSTOMER).get())
            .isEqualTo("ACCOUNT-$CUSTOMER|BALANCE-ACCOUNT-$CUSTOMER|TAXID-$CUSTOMER")
    }

    // The first call to the workflow method blocks on the execution; run it off the test thread so
    // a failing first attempt cannot stall the test.
    private fun startInBackground() {
        val workflow = workflowBuilder<AccountWorkflow>().build()
        CompletableFuture.runAsync { runCatching { workflow.open(CUSTOMER).get() } }
    }

    private fun checkpointColumns(): List<String> =
        query("SELECT input, result FROM skipper_action_checkpoints WHERE workflow_id = ?") { "${it.getString(1)} ${it.getString(2)}" }

    private fun stateColumn(): String = query("SELECT state FROM skipper_workflow_instances WHERE workflow_id = ?") { it.getString(1) }.single()

    private fun <T> query(
        sql: String,
        row: (java.sql.ResultSet) -> T,
    ): List<T> =
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, workflowId)
                statement.executeQuery().use { rs ->
                    val rows = mutableListOf<T>()
                    while (rs.next()) {
                        rows.add(row(rs))
                    }
                    rows
                }
            }
        }

    class FakeBank {
        val opened = AtomicInteger()
        val closed = CopyOnWriteArrayList<String>()
    }

    class AccountActions : Actions() {
        @Inject
        lateinit var bank: FakeBank

        @Execute
        @Encrypted
        fun openAccount(customer: String): String {
            bank.opened.incrementAndGet()
            return "ACCOUNT-$customer"
        }

        @Compensate(forExecute = "openAccount")
        fun closeAccount(
            customer: String,
            account: String,
        ) {
            bank.closed.add("$customer=$account")
        }

        @Execute(returnType = String::class)
        @Encrypted
        fun balance(account: String): CompletableFuture<String> = CompletableFuture.completedFuture("BALANCE-$account")
    }

    class AccountWorkflow : Workflow() {
        private val accounts = actions<AccountActions>()

        @StateField
        @Encrypted
        var taxId: String? = null

        @StateField
        var label: String? = null

        @StateField
        var approved: Boolean? = null

        @WorkflowMethod(returnType = String::class)
        fun open(customer: String): CompletableFuture<String> {
            taxId = "TAXID-$customer"
            label = "LABEL-$customer"
            val account = accounts.openAccount(customer)
            val balance = accounts.balance(account).get()
            waitUntil({ approved != null }, Duration.ofHours(1))
            if (approved == false) {
                throw NonRetryableError("declined")
            }
            return CompletableFuture.completedFuture("$account|$balance|$taxId")
        }

        @SignalMethod
        fun decide(value: Boolean) {
            approved = value
        }

        @QueryMethod
        fun currentTaxId(): String? = taxId
    }

    /** Fails the next encryptions or decryptions it is told to, then delegates. */
    class FlakyEncryptor(
        private val delegate: Encryptor,
    ) : Encryptor {
        val encryptFailuresLeft = AtomicInteger()
        val decryptFailuresLeft = AtomicInteger()
        val failures = AtomicInteger()

        override fun encrypt(plaintext: ByteArray): ByteArray {
            failIfAsked(encryptFailuresLeft)
            return delegate.encrypt(plaintext)
        }

        override fun decrypt(ciphertext: ByteArray): ByteArray {
            failIfAsked(decryptFailuresLeft)
            return delegate.decrypt(ciphertext)
        }

        private fun failIfAsked(left: AtomicInteger) {
            if (left.getAndUpdate { maxOf(0, it - 1) } > 0) {
                failures.incrementAndGet()
                throw IllegalStateException("key service unavailable")
            }
        }
    }

    companion object {
        private const val CUSTOMER = "CUSTOMER-SECRET-1"
    }
}

package com.airbnb.skipper.encryption

/**
 * Encrypts the values Skipper persists for an action method or a workflow state field.
 *
 * - On an `@Execute` method: the action's result is encrypted, and so is its input when Skipper
 *   stores it for compensation. The `@Compensate` method still receives both decrypted.
 * - On a `@StateField` field: the field's value is encrypted.
 *
 * Each value is encrypted as a whole: Skipper serializes it, encrypts the bytes with the
 * [Encryptor] registered under [encryptor] in `SkipperConfig.encryptors`, and stores the ciphertext
 * in place of the value. Null values are stored as null. To protect only part of a value, move that
 * part into its own action or state field.
 *
 * Decryption follows the stored value, not this annotation, so once every host runs a Skipper
 * version with encryption, adding or removing the annotation is safe for workflows that are
 * already running. Values stored in plaintext keep loading as
 * plaintext, and values stored encrypted keep decrypting as long as their encryptor stays
 * registered under the same name.
 *
 * Action errors, workflow inputs and results, and signal payloads are not encrypted.
 *
 * ```kotlin
 * open class PayoutActions : Actions() {
 *     @Execute
 *     @Encrypted
 *     open fun fetchBankAccount(request: AccountRequest): BankAccount = ...
 * }
 *
 * class PayoutWorkflow : Workflow() {
 *     @StateField
 *     @Encrypted("payments-pii")
 *     var taxId: String? = null
 * }
 * ```
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.FIELD)
@MustBeDocumented
annotation class Encrypted(
    /**
     * The name the [Encryptor] is registered under in `SkipperConfig.encryptors`. The name is
     * stored with every encrypted value and used to decrypt it, so renaming it orphans values
     * already stored under the old name.
     */
    val encryptor: String = DEFAULT,
) {
    companion object {
        /** The encryptor name used when [encryptor] is not set. */
        const val DEFAULT: String = "default"
    }
}

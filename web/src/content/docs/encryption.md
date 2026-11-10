---
title: Encrypting Sensitive Data
description: Keep sensitive action inputs, results and state fields encrypted at rest with @Encrypted and your own Encryptor.
section: Guides
order: 17.5
---

Skipper stores action results, compensation inputs and `@StateField`s in its database so a
workflow can resume where it left off. When some of that data is sensitive, annotate the action or
field with **`@Encrypted`**. Skipper then encrypts the value before it reaches the store and
decrypts it when it loads it back. Your workflow and action code keep working with plain values.

Skipper does not pick an algorithm or manage keys. You plug in an **`Encryptor`**, a two-method
interface backed by whatever your organization uses (a KMS client, Tink, a vault).

## Plug in an encryptor

```kotlin
class KmsEncryptor(private val aead: Aead) : Encryptor {
  override fun encrypt(plaintext: ByteArray): ByteArray = aead.encrypt(plaintext, ByteArray(0))
  override fun decrypt(ciphertext: ByteArray): ByteArray = aead.decrypt(ciphertext, ByteArray(0))
}

val config = SkipperConfig.forService("payouts")
config.encryptors = mapOf(
  Encrypted.DEFAULT to KmsEncryptor(defaultAead),
  "payments-pii" to KmsEncryptor(paymentsAead),
)
```

`encryptors` maps a name to an encryptor. `@Encrypted` without arguments uses the one registered
under `Encrypted.DEFAULT`; `@Encrypted("payments-pii")` picks another one, so different data can use
different keys.

## Mark what to encrypt

```kotlin
open class PayoutActions : Actions() {
  @Execute
  @Encrypted
  open fun fetchBankAccount(request: AccountRequest): BankAccount = bank.lookUp(request)

  @Compensate(forExecute = "fetchBankAccount")
  open fun release(request: AccountRequest, account: BankAccount) = bank.release(account)
}

class PayoutWorkflow : Workflow() {
  @StateField
  @Encrypted("payments-pii")
  var taxId: String? = null
}
```

| Annotated | What is encrypted |
|---|---|
| `@Execute` method | The action's result, and its input when Skipper stores it for compensation. The `@Compensate` method still receives both decrypted. |
| `@StateField` field | The field's value. |

Each value is encrypted as a whole. Skipper serializes it with the configured serde (so Thrift and
POJO types both work), encrypts the bytes, and stores an `EncryptedValue` holding the encryptor's
name and the base64 ciphertext. Null values stay null. To protect only part of a value, move that
part into its own action or state field.

## What stays readable

- **The admin UI** shows `[encrypted: <name>]` in place of an encrypted value. It never decrypts.
- **Action errors** are stored as they are, so keep sensitive data out of exception messages.
- **Workflow inputs and results, and signal payloads** are not encrypted. `@Encrypted` on a
  workflow method fails validation rather than being silently ignored.

## Behavior to know about

**Validation.** An `@Encrypted` name that is not registered in `encryptors`, `@Encrypted` on a method
that is not `@Execute`, or on a field that is not `@StateField`, fails validation when the workflow
is created. No value is stored in that case.

**Failures retry, never fail open.** If `encrypt` or `decrypt` throws, the execution is retried
after `taskUnexpectedErrorRetryDelay`. That does not use the workflow's retry budget and never moves
the workflow to `ERROR`. A value is never stored in plaintext as a fallback. If encryption fails
right after an action ran, the action runs again on the retry, as it would after any failure to
checkpoint.

**Adding or removing the annotation is safe.** Decryption is driven by the stored value, not by
the annotation. Values stored before you added `@Encrypted` keep loading as plaintext, and values
stored encrypted keep decrypting after you remove it. The new setting applies to values written
from then on.

**Names are part of the stored data.** Each encrypted value records its encryptor's name. Keep a
name registered for as long as values encrypted under it may be read, and rotate keys inside the
encryptor (for example with an AEAD format that embeds the key version) instead of renaming it.

**Keep encryptors fast and local.** Skipper decrypts a workflow's encrypted values every time it
resumes, so a remote call per value adds up quickly. Cache data keys instead of calling a key
service for every value. Encryptors are called from several threads at once.

**Encrypted values are larger.** Base64 and the envelope add about 40%. On MySQL, each value has
to fit in its `TEXT` column (64 KB), and all state fields of an instance share one column.

## Testing

`TestEncryptor` in `skipper-testutils` is a reversible stand-in that needs no key. It scrambles
bytes so a test can tell ciphertext from plaintext, and it is not secure. Register it in
[`WorkflowTest.configure`](/docs/testing/):

```kotlin
override fun configure(config: SkipperConfig) {
  config.encryptors = mapOf(Encrypted.DEFAULT to TestEncryptor())
}
```

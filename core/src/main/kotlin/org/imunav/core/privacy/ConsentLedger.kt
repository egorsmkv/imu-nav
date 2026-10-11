package org.imunav.core.privacy

import org.imunav.core.diagnostics.DiagnosticOwner
import java.security.MessageDigest
import java.util.UUID

/** Persistent key/value adapter; null writes remove keys. Legacy URL-only entries are intentionally never read. */
interface ConsentStorage {
    fun read(key: String): String?
    fun write(values: Map<String, String?>)
}

/** Account-scoped receipts and withdrawal intents survive switching accounts without inheriting another person's choices. */
class ConsentLedger(private val storage: ConsentStorage) {
    private val identities = LinkedHashMap<DiagnosticOwner, String>()

    @Synchronized fun granted(owner: DiagnosticOwner, purpose: String, version: String): Boolean = storage.read(
        key(owner, purpose, "grant"),
    ) == version && pending(owner, purpose) == null

    /** A concurrent withdrawal always wins locally over an earlier in-flight grant. */
    @Synchronized fun grant(owner: DiagnosticOwner, purpose: String, version: String): Boolean {
        if (pending(owner, purpose) != null) return false
        storage.write(mapOf(key(owner, purpose, "grant") to version, key(owner, purpose, "receipt") to UUID.randomUUID().toString()))
        return true
    }

    /** Local refusal is immediate; the unique intent prevents an old response from clearing a newer withdrawal. */
    @Synchronized fun withdraw(owner: DiagnosticOwner, purpose: String) {
        storage.write(mapOf(key(owner, purpose, "grant") to null, key(owner, purpose, "receipt") to null, key(owner, purpose, "pending") to UUID.randomUUID().toString()))
    }

    @Synchronized fun pending(owner: DiagnosticOwner, purpose: String): String? = storage.read(key(owner, purpose, "pending"))

    @Synchronized fun acknowledge(owner: DiagnosticOwner, purpose: String, intent: String) {
        if (pending(owner, purpose) == intent) storage.write(mapOf(key(owner, purpose, "pending") to null))
    }

    /** Remote reconciliation can revoke a local receipt, but cannot manufacture a grant on this installation. */
    @Synchronized fun revoke(owner: DiagnosticOwner, purpose: String) = storage.write(mapOf(key(owner, purpose, "grant") to null, key(owner, purpose, "receipt") to null))

    @Synchronized fun stamp(owner: DiagnosticOwner, purpose: String): String? = storage.read(key(owner, purpose, "receipt"))

    /** An old remote response cannot erase a newer local confirmation, even for the same notice version. */
    @Synchronized fun revokeIfUnchanged(owner: DiagnosticOwner, purpose: String, expectedStamp: String?) {
        if (stamp(owner, purpose) == expectedStamp) revoke(owner, purpose)
    }

    @Synchronized fun version(owner: DiagnosticOwner, purpose: String): String? = storage.read(key(owner, purpose, "grant"))

    private fun key(owner: DiagnosticOwner, purpose: String, field: String): String {
        require(purpose in setOf("tower_upload", "diagnostics", "live_position"))
        val identity = identities[owner] ?: run {
            val bytes = "${owner.server}\n${owner.accountId}\n${owner.syncIdentity}".toByteArray(Charsets.UTF_8)
            val encoded = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (identities.size >= MAX_IDENTITIES) identities.remove(identities.keys.first())
            identities[owner] = encoded
            encoded
        }
        return "owner-v2:$identity:$purpose:$field"
    }

    private companion object {
        const val MAX_IDENTITIES = 32
    }
}

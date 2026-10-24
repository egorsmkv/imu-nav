package org.imunav.app

import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/**
 * Host JVMs have no Android Keystore. This provider of the same name keeps keys in memory, so the
 * app's real AES/GCM code runs unchanged in Robolectric tests.
 */
internal object FakeAndroidKeyStore {
    private const val NAME = "AndroidKeyStore"
    private val keys = Collections.synchronizedMap(HashMap<String, Key>())

    fun install() {
        if (Security.getProvider(NAME) != null) return
        Security.addProvider(
            object : Provider(NAME, 1.0, "In-memory Android Keystore for host tests") {
                init {
                    put("KeyStore.$NAME", Store::class.java.name)
                    put("KeyGenerator.AES", Generator::class.java.name)
                }
            },
        )
    }

    /** Keys created by [Generator] are stored under the alias of the spec that requested them. */
    class Store : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCreationDate(alias: String): Date? = if (alias in keys) Date() else null
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<Certificate>?) {
            keys[alias] = key
        }

        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<Certificate>?) = throw UnsupportedOperationException()
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = throw UnsupportedOperationException()
        override fun engineDeleteEntry(alias: String) {
            keys.remove(alias)
        }

        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())
        override fun engineContainsAlias(alias: String) = alias in keys
        override fun engineSize() = keys.size
        override fun engineIsKeyEntry(alias: String) = alias in keys
        override fun engineIsCertificateEntry(alias: String) = false
        override fun engineGetCertificateAlias(cert: Certificate): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }

    /** AES keys of the requested alias; Android's KeyGenParameterSpec is read by reflection only for its alias. */
    class Generator : KeyGeneratorSpi() {
        private var alias: String? = null

        override fun engineInit(random: SecureRandom?) = Unit
        override fun engineInit(params: AlgorithmParameterSpec, random: SecureRandom?) {
            alias = runCatching { params.javaClass.getMethod("getKeystoreAlias").invoke(params) as String }.getOrNull()
        }

        override fun engineInit(keysize: Int, random: SecureRandom?) = Unit
        override fun engineGenerateKey(): SecretKey {
            val key = KeyGenerator.getInstance("AES", "SunJCE").apply { init(256) }.generateKey()
            alias?.let { keys[it] = key }
            return key
        }
    }

    /** Forget every key, as if the app had been reinstalled. */
    fun reset() {
        keys.clear()
        KeyStore.getInstance(NAME).load(null)
    }
}

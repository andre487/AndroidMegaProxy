package net.megaproxy487

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/** Test-only JCA provider. Production ConfigStore still performs its normal encryption/serialization. */
class UiTestKeyStoreProvider : Provider("AndroidKeyStore", 1.0, "In-memory UI test keys") {
    init {
        put("KeyStore.AndroidKeyStore", UiTestKeyStore::class.java.name)
        put("KeyGenerator.AES", UiTestKeyGenerator::class.java.name)
    }
}

class UiTestKeyStore : KeyStoreSpi() {
    companion object { val keys = java.util.concurrent.ConcurrentHashMap<String, Key>() }
    override fun engineGetKey(alias: String, password: CharArray?) = keys[alias]
    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
    override fun engineGetCertificate(alias: String): Certificate? = null
    override fun engineGetCreationDate(alias: String) = Date(0)
    override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) { keys[alias] = key }
    override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) { error("unsupported") }
    override fun engineSetCertificateEntry(alias: String, cert: Certificate) { error("unsupported") }
    override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
    override fun engineAliases() = Collections.enumeration(keys.keys)
    override fun engineContainsAlias(alias: String) = keys.containsKey(alias)
    override fun engineSize() = keys.size
    override fun engineIsKeyEntry(alias: String) = keys.containsKey(alias)
    override fun engineIsCertificateEntry(alias: String) = false
    override fun engineGetCertificateAlias(cert: Certificate): String? = null
    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
}

class UiTestKeyGenerator : KeyGeneratorSpi() {
    private lateinit var alias: String
    override fun engineInit(random: SecureRandom?) = Unit
    override fun engineInit(keysize: Int, random: SecureRandom?) = Unit
    override fun engineInit(params: AlgorithmParameterSpec, random: SecureRandom?) {
        alias = (params as KeyGenParameterSpec).keystoreAlias
    }
    override fun engineGenerateKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also {
        UiTestKeyStore.keys[alias] = it
    }
}

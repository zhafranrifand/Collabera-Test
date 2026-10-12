package com.expensemail.app

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.time.OffsetDateTime
import java.util.Base64
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** Authenticated public-key metadata returned by GET /v1/backup/key. */
data class CloudBackupKeyMetadata(
    val keyId: String,
    val publicKey: String,
    val oaepLabel: String,
)

/** Wire-compatible envelope accepted by POST /v1/backups. Binary values use standard Base64. */
data class CloudBackupEnvelope(
    val version: Int,
    val keyId: String,
    val createdAt: String,
    val algorithm: String,
    val nonce: String,
    val tag: String,
    val ciphertext: String,
    val wrappedKey: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", version)
        put("keyId", keyId)
        put("createdAt", createdAt)
        put("algorithm", algorithm)
        put("nonce", nonce)
        put("tag", tag)
        put("ciphertext", ciphertext)
        put("wrappedKey", wrappedKey)
    }
}

/** Response returned by GET /v1/backups/{id}; the AES content key is Base64-encoded. */
data class CloudBackupRestoreResponse(
    val id: String,
    val uploadedAt: String,
    val envelope: CloudBackupEnvelope,
    val contentKey: String,
)

/**
 * Creates and opens Pocketlog's server-assisted encrypted cloud envelope.
 * RSA-OAEP uses SHA-256 for both OAEP and MGF1 and the exact UTF-8 key label from the API.
 */
object CloudBackupEnvelopeCodec {
    const val ENVELOPE_VERSION = 1
    const val ENVELOPE_ALGORITHM = "A256GCM+RSA-OAEP-256"
    const val PUBLIC_KEY_ALGORITHM = "RSA-OAEP-256"
    const val PUBLIC_KEY_FORMAT = "spki-der-base64"
    const val CONTENT_ENCRYPTION = "AES-256-GCM"
    const val AES_KEY_BYTES = 32
    const val GCM_NONCE_BYTES = 12
    const val GCM_TAG_BYTES = 16
    const val MAX_CIPHERTEXT_BYTES = 10 * 1024 * 1024
    private const val LABEL_PREFIX = "pocketlog-backup:v1:"
    private val keyIdPattern = Pattern.compile("^[A-Za-z0-9._-]{1,64}$")
    private val uuidPattern = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
    private val base64Pattern = Pattern.compile("^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$")

    fun parseKeyMetadata(json: String): CloudBackupKeyMetadata {
        val root = JSONObject(json)
        require(root.optString("algorithm") == PUBLIC_KEY_ALGORITHM) { "Unsupported cloud backup key algorithm." }
        require(root.optString("publicKeyFormat") == PUBLIC_KEY_FORMAT) { "Unsupported cloud backup public key format." }
        require(root.optString("contentEncryption") == CONTENT_ENCRYPTION) { "Unsupported cloud backup content encryption." }
        val keyId = root.optString("keyId")
        require(isValidKeyId(keyId)) { "Invalid cloud backup key ID." }
        val publicKey = root.optString("publicKey")
        val label = root.optString("oaepLabel")
        require(label == "$LABEL_PREFIX$keyId") { "Cloud backup OAEP label does not match the key ID." }
        decodeRsaPublicKey(publicKey)
        return CloudBackupKeyMetadata(keyId, publicKey, label)
    }

    fun parseEnvelope(json: String): CloudBackupEnvelope {
        val root = JSONObject(json)
        val expectedKeys = setOf("version", "keyId", "createdAt", "algorithm", "nonce", "tag", "ciphertext", "wrappedKey")
        val fields = root.names() ?: throw IllegalArgumentException("Cloud backup envelope fields are invalid.")
        require(fields.length() == expectedKeys.size && (0 until fields.length()).all { fields.getString(it) in expectedKeys }) {
            "Cloud backup envelope fields are invalid."
        }
        val version = root.optInt("version", -1)
        val envelope = CloudBackupEnvelope(
            version = version,
            keyId = root.optString("keyId"),
            createdAt = root.optString("createdAt"),
            algorithm = root.optString("algorithm"),
            nonce = root.optString("nonce"),
            tag = root.optString("tag"),
            ciphertext = root.optString("ciphertext"),
            wrappedKey = root.optString("wrappedKey"),
        )
        validateEnvelope(envelope)
        return envelope
    }

    fun parseRestoreResponse(json: String): CloudBackupRestoreResponse {
        val root = JSONObject(json)
        val id = root.optString("id")
        require(uuidPattern.matcher(id).matches()) { "Invalid cloud backup snapshot ID." }
        val uploadedAt = root.optString("uploadedAt")
        validateTimestamp(uploadedAt)
        val envelopeObject = root.optJSONObject("envelope") ?: throw IllegalArgumentException("Cloud backup restore is missing its envelope.")
        val envelope = parseEnvelope(envelopeObject.toString())
        val contentKey = root.optString("contentKey")
        val decodedKey = decodeBase64(contentKey, "content key", AES_KEY_BYTES)
        require(decodedKey.size == AES_KEY_BYTES) { "Cloud backup content key must be 32 bytes." }
        return CloudBackupRestoreResponse(id, uploadedAt, envelope, contentKey)
    }

    /** Encrypts a UTF-8 app backup payload. The API requires an ISO-8601 offset timestamp. */
    fun encrypt(plaintext: ByteArray, metadata: CloudBackupKeyMetadata, createdAt: String): CloudBackupEnvelope {
        validateMetadata(metadata)
        validateTimestamp(createdAt)
        require(plaintext.size <= MAX_CIPHERTEXT_BYTES) { "Cloud backup is too large to upload." }
        val publicKey = decodeRsaPublicKey(metadata.publicKey)
        val contentKey = ByteArray(AES_KEY_BYTES).also { secureRandom.nextBytes(it) }
        val nonce = ByteArray(GCM_NONCE_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(GCM_TAG_BYTES * 8, nonce))
        val ciphertextAndTag = cipher.doFinal(plaintext)
        val ciphertext = ciphertextAndTag.copyOfRange(0, ciphertextAndTag.size - GCM_TAG_BYTES)
        val tag = ciphertextAndTag.copyOfRange(ciphertextAndTag.size - GCM_TAG_BYTES, ciphertextAndTag.size)
        val wrappedKey = rsaCipher(Cipher.ENCRYPT_MODE, publicKey, metadata.oaepLabel).doFinal(contentKey)
        return CloudBackupEnvelope(
            version = ENVELOPE_VERSION,
            keyId = metadata.keyId,
            createdAt = createdAt,
            algorithm = ENVELOPE_ALGORITHM,
            nonce = encodeBase64(nonce),
            tag = encodeBase64(tag),
            ciphertext = encodeBase64(ciphertext),
            wrappedKey = encodeBase64(wrappedKey),
        )
    }

    /** Decrypts after the restore API returns the unwrapped Base64 AES content key. */
    fun decrypt(envelope: CloudBackupEnvelope, contentKeyBase64: String): ByteArray {
        validateEnvelope(envelope)
        val contentKey = decodeBase64(contentKeyBase64, "content key", AES_KEY_BYTES)
        require(contentKey.size == AES_KEY_BYTES) { "Cloud backup content key must be 32 bytes." }
        val nonce = decodeBase64(envelope.nonce, "nonce", GCM_NONCE_BYTES)
        val tag = decodeBase64(envelope.tag, "tag", GCM_TAG_BYTES)
        val ciphertext = decodeBase64(envelope.ciphertext, "ciphertext", MAX_CIPHERTEXT_BYTES)
        val combined = ByteArray(ciphertext.size + tag.size).apply {
            System.arraycopy(ciphertext, 0, this, 0, ciphertext.size)
            System.arraycopy(tag, 0, this, ciphertext.size, tag.size)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(GCM_TAG_BYTES * 8, nonce))
        return cipher.doFinal(combined)
    }

    private fun validateMetadata(metadata: CloudBackupKeyMetadata) {
        require(isValidKeyId(metadata.keyId)) { "Invalid cloud backup key ID." }
        require(metadata.oaepLabel == "$LABEL_PREFIX${metadata.keyId}") { "Cloud backup OAEP label does not match the key ID." }
        decodeRsaPublicKey(metadata.publicKey)
    }

    private fun validateEnvelope(envelope: CloudBackupEnvelope) {
        require(envelope.version == ENVELOPE_VERSION) { "Unsupported cloud backup envelope version." }
        require(isValidKeyId(envelope.keyId)) { "Invalid cloud backup key ID." }
        require(envelope.algorithm == ENVELOPE_ALGORITHM) { "Unsupported cloud backup envelope algorithm." }
        validateTimestamp(envelope.createdAt)
        require(decodeBase64(envelope.nonce, "nonce", GCM_NONCE_BYTES).size == GCM_NONCE_BYTES) { "Cloud backup nonce must be 12 bytes." }
        require(decodeBase64(envelope.tag, "tag", GCM_TAG_BYTES).size == GCM_TAG_BYTES) { "Cloud backup tag must be 16 bytes." }
        decodeBase64(envelope.ciphertext, "ciphertext", MAX_CIPHERTEXT_BYTES)
        val wrappedKey = decodeBase64(envelope.wrappedKey, "wrapped key", MAX_RSA_KEY_BYTES)
        require(wrappedKey.size in MIN_RSA_KEY_BYTES..MAX_RSA_KEY_BYTES) { "Cloud backup wrapped key size is invalid." }
    }

    private fun decodeRsaPublicKey(encoded: String): PublicKey {
        val der = decodeBase64(encoded, "public key", MAX_PUBLIC_KEY_BYTES)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        require(key.algorithm.equals("RSA", ignoreCase = true)) { "Cloud backup public key must be RSA." }
        val modulusBits = (key as? RSAPublicKey)?.modulus?.bitLength() ?: 0
        require(modulusBits in 2048..4096) { "Cloud backup RSA key size must be 2048–4096 bits." }
        return key
    }

    private fun rsaCipher(mode: Int, key: PublicKey, label: String): Cipher =
        Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply {
            init(
                mode,
                key,
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified(label.toByteArray(StandardCharsets.UTF_8))),
            )
        }

    private fun decodeBase64(value: String, field: String, maxBytes: Int): ByteArray {
        require(value.isNotEmpty() && base64Pattern.matcher(value).matches()) { "Cloud backup $field is not valid Base64." }
        val decoded = try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Cloud backup $field is not valid Base64.")
        }
        require(decoded.size <= maxBytes) { "Cloud backup $field is too large." }
        require(encodeBase64(decoded) == value) { "Cloud backup $field is not canonical Base64." }
        return decoded
    }

    private fun encodeBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun validateTimestamp(value: String) {
        try {
            OffsetDateTime.parse(value)
        } catch (_: Exception) {
            throw IllegalArgumentException("Cloud backup timestamp must be ISO-8601 with an offset.")
        }
    }

    private fun isValidKeyId(value: String): Boolean = keyIdPattern.matcher(value).matches()

    private val secureRandom = java.security.SecureRandom()

    private const val MIN_RSA_KEY_BYTES = 256
    private const val MAX_RSA_KEY_BYTES = 512
    private const val MAX_PUBLIC_KEY_BYTES = 1024
}

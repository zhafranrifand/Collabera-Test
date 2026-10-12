package com.expensemail.app

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

class CloudBackupEnvelopeTest {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val metadata = CloudBackupKeyMetadata(
        keyId = KEY_ID,
        publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded),
        oaepLabel = "pocketlog-backup:v1:$KEY_ID",
    )

    @Test
    fun encryptsAndDecryptsPayloadUsingCloudApiContract() {
        val payload = "Pocketlog backup: transaksi ✓\n".toByteArray(Charsets.UTF_8)

        val encrypted = CloudBackupEnvelopeCodec.encrypt(payload, metadata, CREATED_AT)
        val parsed = CloudBackupEnvelopeCodec.parseEnvelope(encrypted.toJson().toString())
        val restoreKey = decryptWrappedKey(parsed, keyPair.private)
        val restored = CloudBackupEnvelopeCodec.decrypt(parsed, Base64.getEncoder().encodeToString(restoreKey))

        assertArrayEquals(payload, restored)
        assertEquals(1, parsed.version)
        assertEquals(KEY_ID, parsed.keyId)
        assertEquals("A256GCM+RSA-OAEP-256", parsed.algorithm)
        assertEquals(CREATED_AT, parsed.createdAt)
        assertEquals(12, Base64.getDecoder().decode(parsed.nonce).size)
        assertEquals(16, Base64.getDecoder().decode(parsed.tag).size)
    }

    @Test
    fun restoreResponseParsesApiWireShape() {
        val envelope = CloudBackupEnvelopeCodec.encrypt("backup".toByteArray(), metadata, CREATED_AT)
        val key = decryptWrappedKey(envelope, keyPair.private)
        val response = JSONObject().apply {
            put("id", SNAPSHOT_ID)
            put("uploadedAt", CREATED_AT)
            put("envelope", envelope.toJson())
            put("contentKey", Base64.getEncoder().encodeToString(key))
        }

        val parsed = CloudBackupEnvelopeCodec.parseRestoreResponse(response.toString())

        assertEquals(SNAPSHOT_ID, parsed.id)
        assertEquals(KEY_ID, parsed.envelope.keyId)
        assertArrayEquals("backup".toByteArray(), CloudBackupEnvelopeCodec.decrypt(parsed.envelope, parsed.contentKey))
    }

    @Test
    fun rejectsCiphertextTamperingAndWrongContentKey() {
        val encrypted = CloudBackupEnvelopeCodec.encrypt("sensitive".toByteArray(), metadata, CREATED_AT)
        val contentKey = decryptWrappedKey(encrypted, keyPair.private)
        val tamperedCiphertext = encrypted.copy(ciphertext = flipFirstByte(encrypted.ciphertext))

        assertThrows(Exception::class.java) {
            CloudBackupEnvelopeCodec.decrypt(tamperedCiphertext, Base64.getEncoder().encodeToString(contentKey))
        }
        assertThrows(Exception::class.java) {
            CloudBackupEnvelopeCodec.decrypt(encrypted, Base64.getEncoder().encodeToString(ByteArray(32)))
        }
    }

    @Test
    fun rejectsTamperedAuthenticationTag() {
        val encrypted = CloudBackupEnvelopeCodec.encrypt("sensitive".toByteArray(), metadata, CREATED_AT)
        val tag = Base64.getDecoder().decode(encrypted.tag)
        tag[tag.lastIndex] = (tag.last().toInt() xor 0x01).toByte()

        assertThrows(Exception::class.java) {
            CloudBackupEnvelopeCodec.decrypt(encrypted.copy(tag = Base64.getEncoder().encodeToString(tag)),
                Base64.getEncoder().encodeToString(decryptWrappedKey(encrypted, keyPair.private)))
        }
    }

    @Test
    fun rejectsMalformedPublicKeyAndMismatchedKeyMetadata() {
        assertThrows(Exception::class.java) {
            CloudBackupEnvelopeCodec.encrypt("payload".toByteArray(), metadata.copy(publicKey = "bm90LWEtcHVibGljLWtleQ=="), CREATED_AT)
        }
        val weakKey = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        assertThrows(IllegalArgumentException::class.java) {
            CloudBackupEnvelopeCodec.encrypt(
                "payload".toByteArray(),
                metadata.copy(publicKey = Base64.getEncoder().encodeToString(weakKey.public.encoded)),
                CREATED_AT,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CloudBackupEnvelopeCodec.encrypt("payload".toByteArray(), metadata.copy(oaepLabel = "wrong-label"), CREATED_AT)
        }
        val malformedMetadata = JSONObject().apply {
            put("keyId", KEY_ID)
            put("algorithm", "RSA-OAEP-256")
            put("publicKeyFormat", "spki-der-base64")
            put("contentEncryption", "AES-256-GCM")
            put("publicKey", "bm90LWEtcHVibGljLWtleQ==")
            put("oaepLabel", "pocketlog-backup:v1:$KEY_ID")
        }
        assertThrows(Exception::class.java) { CloudBackupEnvelopeCodec.parseKeyMetadata(malformedMetadata.toString()) }
    }

    @Test
    fun rejectsUnsupportedEnvelopeMetadataAndNonCanonicalBase64() {
        val envelope = CloudBackupEnvelopeCodec.encrypt("payload".toByteArray(), metadata, CREATED_AT)
        assertThrows(IllegalArgumentException::class.java) { CloudBackupEnvelopeCodec.decrypt(envelope.copy(version = 2), Base64.getEncoder().encodeToString(ByteArray(32))) }
        assertThrows(IllegalArgumentException::class.java) { CloudBackupEnvelopeCodec.decrypt(envelope.copy(keyId = "bad key"), Base64.getEncoder().encodeToString(ByteArray(32))) }
        assertThrows(IllegalArgumentException::class.java) { CloudBackupEnvelopeCodec.decrypt(envelope.copy(algorithm = "AES"), Base64.getEncoder().encodeToString(ByteArray(32))) }
        assertThrows(IllegalArgumentException::class.java) { CloudBackupEnvelopeCodec.decrypt(envelope.copy(nonce = "%%%="), Base64.getEncoder().encodeToString(ByteArray(32))) }
        assertThrows(IllegalArgumentException::class.java) { CloudBackupEnvelopeCodec.parseEnvelope(envelope.copy(createdAt = "yesterday").toJson().toString()) }
    }

    @Test
    fun rejectsInvalidRestoreResponseMetadata() {
        val envelope = CloudBackupEnvelopeCodec.encrypt("payload".toByteArray(), metadata, CREATED_AT)
        val contentKey = Base64.getEncoder().encodeToString(decryptWrappedKey(envelope, keyPair.private))
        val invalid = JSONObject().apply {
            put("id", "../other-vault")
            put("uploadedAt", CREATED_AT)
            put("envelope", envelope.toJson())
            put("contentKey", contentKey)
        }

        assertThrows(IllegalArgumentException::class.java) { CloudBackupEnvelopeCodec.parseRestoreResponse(invalid.toString()) }
        invalid.put("id", SNAPSHOT_ID).put("contentKey", "not-base64")
        assertThrows(IllegalArgumentException::class.java) { CloudBackupEnvelopeCodec.parseRestoreResponse(invalid.toString()) }
    }

    private fun decryptWrappedKey(envelope: CloudBackupEnvelope, privateKey: PrivateKey): ByteArray =
        Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").run {
            init(
                Cipher.DECRYPT_MODE,
                privateKey,
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified("pocketlog-backup:v1:${envelope.keyId}".toByteArray(Charsets.UTF_8))),
            )
            doFinal(Base64.getDecoder().decode(envelope.wrappedKey))
        }

    private fun flipFirstByte(value: String): String {
        val bytes = Base64.getDecoder().decode(value)
        if (bytes.isEmpty()) return Base64.getEncoder().encodeToString(byteArrayOf(1))
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        return Base64.getEncoder().encodeToString(bytes)
    }

    companion object {
        private const val KEY_ID = "2026-10"
        private const val CREATED_AT = "2026-10-12T12:00:00.000Z"
        private const val SNAPSHOT_ID = "6d18fd3a-10c5-4d09-9a9f-13c240c02d92"
    }
}

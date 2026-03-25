package com.airbridge.app.data.crypto

import com.airbridge.app.domain.StoredDeviceIdentity
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SessionKeyStore {
    private val secureRandom = SecureRandom()

    fun generateLocalIdentity(deviceName: String): StoredDeviceIdentity {
        val privateKey = X25519PrivateKeyParameters(secureRandom)
        val publicKey = privateKey.generatePublicKey()

        return StoredDeviceIdentity(
            deviceName = deviceName.trim(),
            publicKeyBase64 = publicKey.encoded.encodeBase64(),
            privateKeyBase64 = privateKey.encoded.encodeBase64(),
        )
    }

    fun deriveSharedSecret(privateKeyBase64: String, peerPublicKeyBase64: String): ByteArray {
        val privateKeyBytes = privateKeyBase64.decodeBase64()
        val publicKeyBytes = peerPublicKeyBase64.decodeBase64()
        val privateKey = X25519PrivateKeyParameters(privateKeyBytes, 0)
        val publicKey = X25519PublicKeyParameters(publicKeyBytes, 0)
        val agreement = X25519Agreement()
        val sharedSecret = ByteArray(32)

        agreement.init(privateKey)
        agreement.calculateAgreement(publicKey, sharedSecret, 0)

        return sharedSecret
    }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, size: Int): ByteArray {
        val realSalt = if (salt.isNotEmpty()) salt else ByteArray(32)
        val extractMac = Mac.getInstance("HmacSHA256")
        extractMac.init(SecretKeySpec(realSalt, "HmacSHA256"))
        val prk = extractMac.doFinal(ikm)

        val output = ByteArray(size)
        var generated = 0
        var previous = ByteArray(0)
        var counter = 1

        while (generated < size) {
            val expandMac = Mac.getInstance("HmacSHA256")
            expandMac.init(SecretKeySpec(prk, "HmacSHA256"))
            expandMac.update(previous)
            expandMac.update(info)
            expandMac.update(counter.toByte())
            previous = expandMac.doFinal()

            val bytesToCopy = minOf(previous.size, size - generated)
            previous.copyInto(output, destinationOffset = generated, endIndex = bytesToCopy)
            generated += bytesToCopy
            counter += 1
        }

        return output
    }
}

internal fun ByteArray.encodeBase64(): String = Base64.getEncoder().withoutPadding().encodeToString(this)

internal fun String.decodeBase64(): ByteArray = Base64.getDecoder().decode(this)


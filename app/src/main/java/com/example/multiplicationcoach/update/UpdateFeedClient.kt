package com.example.multiplicationcoach.update

import java.net.URL
import java.security.PublicKey
import java.security.Signature

fun interface UpdateBytesTransport {
    fun get(url: URL): ByteArray
}

class UpdateFeedClient(
    private val sources: List<URL>,
    private val transport: UpdateBytesTransport,
    private val publicKey: PublicKey,
) {
    fun fetchLatest(installed: AppUpdateVersion): UpdateManifest? {
        return sources.mapNotNull { source ->
            runCatching {
                val envelope = SignedUpdateEnvelope.decode(transport.get(source))
                require(verify(envelope)) { "Invalid update signature" }
                UpdateManifest.decodePayload(envelope.payloadBytes)
            }.getOrNull()
        }.filter { candidate ->
            candidate.version.isNewerThan(installed)
        }.maxByOrNull { candidate ->
            candidate.version.versionCode
        }
    }

    private fun verify(envelope: SignedUpdateEnvelope): Boolean {
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update(envelope.payloadBytes)
        return verifier.verify(envelope.signatureBytes)
    }
}

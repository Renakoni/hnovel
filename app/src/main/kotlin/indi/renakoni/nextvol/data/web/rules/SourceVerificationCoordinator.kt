package indi.renakoni.nextvol.data.web.rules

import hnovel.content.SourceContentException
import hnovel.content.SourceVerification
import indi.renakoni.nextvol.data.web.ForegroundSourceRequest
import indi.renakoni.nextvol.data.web.WebSourceRegistry
import indi.renakoni.nextvol.data.web.SourceStatus
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class VerificationOwner(val source: Identifier, val revision: String, val generation: Long)
data class VerificationPrompt(val id: String, val owner: VerificationOwner, val name: String,
    val kind: hnovel.network.BrowserChallengeKind?, val foreground: Boolean, val opening: Boolean = false,
    val certificate: hnovel.network.CertificateProblem? = null, val confirmingCertificate: Boolean = false)

/** Foreground operations open verification directly; background work only leaves a notice. */
@Singleton
class SourceVerificationCoordinator @Inject constructor(private val registry: WebSourceRegistry) {
    private data class Pending(val prompt: VerificationPrompt, val verification: SourceVerification,
        val approval: CompletableDeferred<Boolean> = CompletableDeferred())
    private val lock = Any()
    private val pending = linkedMapOf<String, Pending>()
    private val mutable = MutableStateFlow<List<VerificationPrompt>>(emptyList())
    val prompts = mutable.asStateFlow()
    private val browser = Mutex()

    private fun current(owner: VerificationOwner) = registry.sources.value.any {
        it.metadata.id == owner.source && it.metadata.revision == owner.revision &&
            it.metadata.accountGeneration == owner.generation && it.status == SourceStatus.Ready
    }
    private fun publish() { mutable.value = pending.values.map { it.prompt } }
    private fun remove(id: String) = synchronized(lock) { pending.remove(id)?.approval?.complete(false); publish() }

    fun dismiss(id: String) = remove(id)

    /** Only the confirmation dialog calls this. Merely opening a prompt grants nothing. */
    fun approveCertificate(id: String) = synchronized(lock) {
        val entry = pending[id] ?: return@synchronized
        if (entry.prompt.confirmingCertificate && !entry.prompt.opening && current(entry.prompt.owner)) entry.approval.complete(true)
    }

    /** The visible host collects this; retirement also removes stale background notices. */
    suspend fun observeRetirement(): Unit = registry.sources.collect {
        synchronized(lock) {
            val retired = pending.values.filter { !current(it.prompt.owner) }
            retired.forEach { entry -> pending.remove(entry.prompt.id); entry.approval.complete(false) }
            publish()
        }
    }

    private suspend fun open(entry: Pending) = browser.withLock {
        try { completeVerification(entry) }
        catch (failure: SourceContentException) {
            val verification = failure.verification
            if (entry.prompt.certificate != null || failure.code != hnovel.content.ContentError.Certificate ||
                verification?.certificate == null) throw failure
            // A verification page can itself fail TLS. Keep the original browser action,
            // ask for this certificate separately, then resume that action only once.
            val certificate = Pending(entry.prompt.copy(kind = verification.kind, opening = false,
                certificate = verification.certificate), verification)
            fun replace(expected: Pending, next: Pending) = synchronized(lock) {
                if (pending[entry.prompt.id]?.approval !== expected.approval) throw CancellationException("Verification dismissed")
                pending[entry.prompt.id] = next
                publish()
            }
            replace(entry, certificate)
            completeVerification(certificate)
            replace(certificate, entry)
            completeVerification(entry)
        }
    }

    private suspend fun completeVerification(entry: Pending) {
        if (!current(entry.prompt.owner)) throw SourceContentException(hnovel.content.ContentError.Unavailable, "browser.verification")
        if (entry.prompt.certificate != null) {
            synchronized(lock) {
                if (pending[entry.prompt.id]?.approval !== entry.approval) throw CancellationException("Verification dismissed")
                pending[entry.prompt.id] = entry.copy(prompt = entry.prompt.copy(confirmingCertificate = true))
                publish()
            }
            val approved = entry.approval.await()
            if (!current(entry.prompt.owner)) throw SourceContentException(hnovel.content.ContentError.Unavailable, "certificate.verification")
            if (!approved) throw SourceContentException(hnovel.content.ContentError.Certificate, "certificate.verification")
        }
        synchronized(lock) {
            if (pending[entry.prompt.id]?.approval !== entry.approval) throw CancellationException("Verification dismissed")
            pending[entry.prompt.id] = entry.copy(prompt = entry.prompt.copy(opening = true))
            publish()
        }
        entry.verification.complete()
        if (!current(entry.prompt.owner)) throw SourceContentException(hnovel.content.ContentError.Unavailable, "browser.verification")
    }

    /** A background failure exposes an action, but never waits or opens an Activity itself. */
    suspend fun verifyBackground(id: String) {
        val entry = synchronized(lock) { pending[id]?.takeIf { !it.prompt.foreground && !it.prompt.opening && !it.prompt.confirmingCertificate } } ?: return
        try { withTimeout(300000) { open(entry) } }
        finally { remove(id) }
    }

    suspend fun <T> execute(owner: VerificationOwner, name: String, block: suspend () -> T): T {
        val failure = try { return block() } catch (error: SourceContentException) { error }
        val verification = failure.verification ?: throw failure
        if (!current(owner)) throw failure
        val foreground = currentCoroutineContext()[ForegroundSourceRequest]?.takeIf { it.allowsInteraction }
        val entry = Pending(VerificationPrompt(UUID.randomUUID().toString(), owner, name,
            verification.kind, foreground != null, certificate = verification.certificate), verification)
        synchronized(lock) {
            // Background notifications can replace an older notice for the same account;
            // foreground continuations always keep their own request and cancellation.
            if (foreground == null) pending.entries.removeAll {
                (!it.value.prompt.foreground && !it.value.prompt.opening && !it.value.prompt.confirmingCertificate &&
                    it.value.prompt.owner == owner).also { removed -> if (removed) it.value.approval.complete(false) }
            }
            pending[entry.prompt.id] = entry
            publish()
        }
        if (foreground == null) throw failure
        var verifying = false
        try {
            return foreground.ownVerification {
                foreground.beginVerification(); verifying = true
                val completed = withTimeoutOrNull(300000) {
                    foreground.awaitActive()
                    open(entry)
                    foreground.awaitActive()
                    true
                } ?: false
                if (!completed) throw failure
                // One retry only. A fresh challenge remains visible as an error, not a loop.
                block()
            }
        } finally {
            if (verifying) foreground.endVerification()
            remove(entry.prompt.id)
        }
    }
}

internal class RuleRequestRecovery(private val coordinator: SourceVerificationCoordinator,
    private val owner: VerificationOwner, private val name: String) {
    suspend fun <T> execute(block: suspend () -> T): T = coordinator.execute(owner, name, block)
}

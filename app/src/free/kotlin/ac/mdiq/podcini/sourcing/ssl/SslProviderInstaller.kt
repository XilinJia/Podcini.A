package ac.mdiq.podcini.sourcing.ssl

import ac.mdiq.podcini.utils.Logt
import org.conscrypt.Conscrypt
import java.security.Security

object SslProviderInstaller {
    fun install() {
        // Insert bundled conscrypt as highest security provider (overrides OS version).
        runCatching {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }.onFailure { e ->
            Logt("Security", "Failed to install Conscrypt provider: ${e.message}. Using system default security provider.")
        }
    }
}

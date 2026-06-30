package com.gotohex.rdp.remote

import android.content.Context

import com.gotohex.rdp.data.model.ProtocolType
import com.gotohex.rdp.data.model.RdpCredentials
import com.gotohex.rdp.data.model.RdpPerformance
import com.gotohex.rdp.data.model.RdpProfile
import com.gotohex.rdp.data.model.SshAuthType
import com.gotohex.rdp.rdp.protocol.RdpRemoteAdapter
import com.gotohex.rdp.ssh.protocol.SshAuthMode
import com.gotohex.rdp.ssh.protocol.SshClient
import com.gotohex.rdp.ssh.protocol.SshCredentials
import com.gotohex.rdp.ssh.protocol.SshTunnelCredentials
import com.gotohex.rdp.vnc.protocol.VncClient
import com.gotohex.rdp.vnc.protocol.VncCredentials

/**
 * Builds the right [RemoteSessionClient] implementation for a profile's
 * [ProtocolType]. This is the single place that knows about all three
 * protocol client classes — everything downstream (the session ViewModel,
 * the session UI) only depends on the common [RemoteSessionClient] surface.
 *
 * When an RDP or VNC profile has [RdpProfile.sshTunnelEnabled] set to true
 * the returned client is an [SshTunneledClient] that first opens an SSH
 * port-forwarding tunnel and then connects the inner RDP/VNC client through
 * it, making the tunnel completely transparent to the rest of the app.
 */
object RemoteSessionFactory {

    fun create(
        profile: RdpProfile,
        displayWidth: Int,
        displayHeight: Int,
        compressionQuality: Int = 75,  // FIX #4: wired through from AppSettings.compressionQuality
        // BUG-B / BUG-H: pass appContext so VncClient can give it to bVNC (TLS),
        // and SshClient/SshTunnelManager can persist TOFU keys in SharedPreferences.
        appContext: Context,
    ): RemoteSessionClient {
        return when (profile.protocolType) {

            // ── SSH direct terminal session ────────────────────────────────
            ProtocolType.SSH -> SshClient(
                credentials = SshCredentials(
                    host = profile.host,
                    port = profile.port,
                    username = profile.username,
                    authMode = profile.sshAuthType.toSshAuthMode(),
                    password = profile.password,
                    privateKeyPem = profile.sshPrivateKey,
                    privateKeyPassphrase = profile.sshPrivateKeyPassphrase,
                ),
                appContext = appContext,  // BUG-H FIX: needed for TOFU persistence
            )

            // ── RDP ───────────────────────────────────────────────────────
            ProtocolType.RDP -> {
                val rdpFactory = { host: String, port: Int ->
                    RdpRemoteAdapter(
                        credentials = RdpCredentials(
                            host = host,
                            port = port,
                            username = profile.username,
                            password = profile.password,
                            domain = profile.domain,
                            useNla = profile.useNla,
                            acceptSelfSignedCertificate = profile.acceptSelfSignedCertificate,  // BUG-3 FIX
                            gatewayEnabled = profile.gatewayEnabled,
                            gatewayHost = profile.gatewayHost,
                            gatewayPort = profile.gatewayPort,
                            gatewayUsername = profile.gatewayUsername,
                            gatewayPassword = profile.gatewayPassword,
                            gatewayDomain = profile.gatewayDomain,
                        ),
                        displayWidth       = displayWidth,
                        displayHeight      = displayHeight,
                        performanceMode    = profile.performanceFlags,
                        colorDepth         = profile.colorDepth,        // FIX #3
                        compressionQuality = compressionQuality,        // FIX #4
                    )
                }
                if (profile.sshTunnelEnabled && profile.sshTunnelHost.isNotBlank()) {
                    SshTunneledClient(
                        tunnelCredentials = profile.toSshTunnelCredentials(),
                        targetHost        = profile.host,
                        targetPort        = profile.port,
                        appContext        = appContext,  // BUG-H FIX
                        innerClientFactory = { localPort ->
                            // The inner RDP client connects to localhost:localPort
                            rdpFactory("127.0.0.1", localPort)
                        }
                    )
                } else {
                    rdpFactory(profile.host, profile.port)
                }
            }

            // ── VNC ───────────────────────────────────────────────────────
            ProtocolType.VNC -> {
                val vncFactory = { host: String, port: Int ->
                    VncClient(
                        credentials = VncCredentials(
                            host = host,
                            port = port,
                            password = profile.password,
                            viewOnly = profile.vncViewOnly,
                        ),
                        appContext = appContext,  // BUG-B FIX
                    )
                }
                if (profile.sshTunnelEnabled && profile.sshTunnelHost.isNotBlank()) {
                    SshTunneledClient(
                        tunnelCredentials = profile.toSshTunnelCredentials(),
                        targetHost        = profile.host,
                        targetPort        = profile.port,
                        appContext        = appContext,  // BUG-H FIX
                        innerClientFactory = { localPort ->
                            vncFactory("127.0.0.1", localPort)
                        }
                    )
                } else {
                    vncFactory(profile.host, profile.port)
                }
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun SshAuthType.toSshAuthMode() = when (this) {
        SshAuthType.PASSWORD    -> SshAuthMode.PASSWORD
        SshAuthType.PRIVATE_KEY -> SshAuthMode.PRIVATE_KEY
    }

    private fun RdpProfile.toSshTunnelCredentials() = SshTunnelCredentials(
        host                 = sshTunnelHost,
        port                 = sshTunnelPort,
        username             = sshTunnelUsername,
        authMode             = sshTunnelAuthType.toSshAuthMode(),
        password             = sshTunnelPassword,
        privateKeyPem        = sshTunnelPrivateKey,
        privateKeyPassphrase = sshTunnelPrivateKeyPassphrase,
    )
}

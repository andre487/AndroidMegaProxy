package net.megaproxy487

import androidx.annotation.StringRes
import net.megaproxy487.model.DnsProvider
import net.megaproxy487.model.FailoverMode
import net.megaproxy487.model.ProxyType
import net.megaproxy487.model.SshAuthMode
import net.megaproxy487.model.SshProfile
import net.megaproxy487.model.TlsProfile
import net.megaproxy487.vpn.BlockingSignal

@get:StringRes
internal val ProxyType.titleRes: Int
    get() = when (this) {
        ProxyType.HTTPS -> R.string.option_proxytype_https
        ProxyType.HTTPS_JUMP -> R.string.option_proxytype_https_jump
        ProxyType.SSH -> R.string.option_proxytype_ssh
        ProxyType.SSH_JUMP -> R.string.option_proxytype_ssh_jump
    }

@get:StringRes
internal val SshProfile.titleRes: Int
    get() = when (this) {
        SshProfile.DEFAULT -> R.string.option_sshprofile_default
        SshProfile.OPENSSH_TERMUX -> R.string.option_sshprofile_openssh_termux
        SshProfile.CONNECTBOT -> R.string.option_sshprofile_connectbot
        SshProfile.JUICESSH -> R.string.option_sshprofile_juicessh
        SshProfile.TERMIUS_ANDROID -> R.string.option_sshprofile_termius_android
    }

@get:StringRes
internal val SshAuthMode.titleRes: Int
    get() = when (this) {
        SshAuthMode.AUTO -> R.string.option_sshauthmode_auto
        SshAuthMode.PASSWORD_ONLY -> R.string.option_sshauthmode_password_only
        SshAuthMode.KEY_ONLY -> R.string.option_sshauthmode_key_only
    }

@get:StringRes
internal val FailoverMode.titleRes: Int
    get() = when (this) {
        FailoverMode.DISABLED -> R.string.option_failovermode_disabled
        FailoverMode.SELECTED -> R.string.option_failovermode_selected
        FailoverMode.ALL -> R.string.option_failovermode_all
    }

@get:StringRes
internal val DnsProvider.titleRes: Int
    get() = when (this) {
        DnsProvider.CLOUDFLARE -> R.string.option_dnsprovider_cloudflare
        DnsProvider.GOOGLE -> R.string.option_dnsprovider_google
        DnsProvider.QUAD9 -> R.string.option_dnsprovider_quad9
        DnsProvider.YANDEX -> R.string.option_dnsprovider_yandex
        DnsProvider.YANDEX_SAFE -> R.string.option_dnsprovider_yandex_safe
        DnsProvider.YANDEX_FAMILY -> R.string.option_dnsprovider_yandex_family
        DnsProvider.CUSTOM -> R.string.option_dnsprovider_custom
    }

@get:StringRes
internal val TlsProfile.titleRes: Int
    get() = when (this) {
        TlsProfile.DEFAULT -> R.string.option_tlsprofile_default
        TlsProfile.CHROME_ANDROID -> R.string.option_tlsprofile_chrome_android
        TlsProfile.FIREFOX_ANDROID -> R.string.option_tlsprofile_firefox_android
        TlsProfile.EDGE_ANDROID -> R.string.option_tlsprofile_edge_android
        TlsProfile.RANDOMIZED -> R.string.option_tlsprofile_randomized
        TlsProfile.SAMSUNG_INTERNET -> R.string.option_tlsprofile_samsung_internet
        TlsProfile.YANDEX_BROWSER -> R.string.option_tlsprofile_yandex_browser
        TlsProfile.CUSTOM -> R.string.option_tlsprofile_custom
    }

@get:StringRes
internal val BlockingSignal.titleRes: Int
    get() = when (this) {
        BlockingSignal.TCP_TIMEOUT -> R.string.blocking_tcp_timeout
        BlockingSignal.TLS_HANDSHAKE_TIMEOUT -> R.string.blocking_tls_handshake_timeout
        BlockingSignal.TLS_HANDSHAKE_RESET -> R.string.blocking_tls_handshake_reset
        BlockingSignal.CONNECT_RESPONSE_TIMEOUT -> R.string.blocking_connect_response_timeout
        BlockingSignal.CONNECT_RESPONSE_RESET -> R.string.blocking_connect_response_reset
        BlockingSignal.SSH_HANDSHAKE_TIMEOUT -> R.string.blocking_ssh_handshake_timeout
        BlockingSignal.CONNECTION_RESET -> R.string.blocking_connection_reset
        BlockingSignal.SILENT_DROP -> R.string.blocking_silent_drop
    }

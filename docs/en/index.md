# MegaProxy server setup

[Русская версия](../ru/index.md)

This guide describes production-oriented servers for MegaProxy on a current Ubuntu or Debian VPS.
It covers three transports:

1. HTTPS proxy with TLS, Basic authentication, and HTTP/2.
2. Direct SSH transport.
3. SSH through a dedicated jump host.

The examples favor standard protocols, a small attack surface, automatic certificate renewal, and
predictable recovery after a reboot. Replace every value written as `EXAMPLE_VALUE` before using a
configuration.

## Choose the topology

| Profile type | Public servers | Public ports | Best use |
| --- | ---: | --- | --- |
| HTTPS | 1 | TCP 443 and TCP 80 for certificate issuance | Efficient HTTP/2 multiplexing and simple credentials |
| SSH | 1 | TCP 22, or another chosen SSH port | Standard OpenSSH deployment and key authentication |
| SSH with Jump | 2 | Jump: TCP 22; destination: SSH reachable from jump | Separating the public entry point from the egress server |

Use different providers or networks for failover profiles. A second profile on the same VM does
not protect against a VM, provider, route, or datacenter failure.

## Common server baseline

The commands below assume a fresh server and a sudo-capable administrator account. Keep the
existing administrator SSH session open until a second session has successfully connected.

```shell
sudo apt update
sudo apt full-upgrade
sudo apt install --yes ca-certificates curl openssl ufw unattended-upgrades
sudo systemctl enable --now unattended-upgrades
```

Set an accurate clock; TLS and SSH diagnostics become confusing when it is wrong:

```shell
timedatectl status
sudo systemctl enable --now systemd-timesyncd
```

Apply a default-deny inbound firewall policy and open only the ports required by the selected
topology. Check the provider's network firewall as well as the OS firewall.

```shell
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 22/tcp
sudo ufw enable
sudo ufw status verbose
```

Before changing the SSH port, first open the new port and verify a new connection. Never remove the
only working administrative path to the server.

## HTTPS with HTTP/2

This setup uses [GOST v3](https://v3.gost.run/en/), whose HTTP/2 proxy mode accepts CONNECT streams
over TLS. MegaProxy negotiates `h2` with ALPN and reuses the HTTP/2 connection for multiple tunnels.

### 1. Prepare DNS and the firewall

Create an `A` record such as `proxy.example.com` pointing to the server. Add an `AAAA` record only
if the server has working public IPv6. Wait until public DNS returns the new address.

```shell
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
```

Port 80 is used only for the ACME HTTP challenge. Keep it available so certificates can renew.

### 2. Obtain a public certificate

Install Certbot and request a certificate while port 80 is unused:

```shell
sudo apt install --yes certbot
sudo certbot certonly --standalone \
  --domain proxy.example.com \
  --agree-tos \
  --email admin@example.com \
  --no-eff-email
sudo certbot renew --dry-run
```

Certbot installations normally configure automatic renewal. MegaProxy validates the system CA,
hostname, and validity dates but does not pin a particular certificate, so routine renewal works
without changing the profile.

### 3. Install GOST

Use a versioned GOST v3 binary or container from the
[official releases](https://github.com/go-gost/gost/releases). Verify its published checksum and
avoid unversioned third-party packages. The container example below keeps the installation easy to
audit:

```shell
sudo apt install --yes docker.io
sudo systemctl enable --now docker
sudo install -d -m 0700 /opt/megaproxy
```

Generate a long random password locally and store it in a password manager:

```shell
openssl rand -base64 32
```

Create `/opt/megaproxy/gost.yml`:

```yaml
services:
  - name: megaproxy-h2
    addr: ":443"
    handler:
      type: http2
      auth:
        username: EXAMPLE_USERNAME
        password: EXAMPLE_LONG_RANDOM_PASSWORD
    listener:
      type: http2
      tls:
        certFile: /etc/letsencrypt/live/proxy.example.com/fullchain.pem
        keyFile: /etc/letsencrypt/live/proxy.example.com/privkey.pem
        options:
          minVersion: VersionTLS12
          maxVersion: VersionTLS13
          alpn:
            - h2
```

Protect the configuration because it contains the Basic Auth password:

```shell
sudo chmod 600 /opt/megaproxy/gost.yml
```

Do not configure GOST's `mitm`, protocol sniffing, Web API, metrics endpoint, or profiling endpoint.
MegaProxy needs only CONNECT forwarding, and these features are unnecessary for this deployment.

Start a pinned GOST release. Replace `EXAMPLE_GOST_VERSION` with a reviewed v3 release tag:

```shell
sudo docker run --detach \
  --name megaproxy-gost \
  --restart unless-stopped \
  --network host \
  --volume /opt/megaproxy/gost.yml:/etc/gost/gost.yml:ro \
  --volume /etc/letsencrypt:/etc/letsencrypt:ro \
  --env GOST_LOGGER_LEVEL=warn \
  gogost/gost:EXAMPLE_GOST_VERSION \
  -C /etc/gost/gost.yml
```

Inspect startup without publishing credentials:

```shell
sudo docker ps --filter name=megaproxy-gost
sudo docker logs --tail 100 megaproxy-gost
```

Reload the renewed certificate by restarting the small container. Create
`/etc/letsencrypt/renewal-hooks/deploy/restart-megaproxy-gost`:

```shell
#!/bin/sh
exec /usr/bin/docker restart megaproxy-gost
```

Then secure and test the hook:

```shell
sudo chmod 750 /etc/letsencrypt/renewal-hooks/deploy/restart-megaproxy-gost
sudo certbot renew --dry-run
```

### 4. Verify and add the profile

Confirm certificate verification and HTTP/2 ALPN from another machine:

```shell
openssl s_client \
  -connect proxy.example.com:443 \
  -servername proxy.example.com \
  -alpn h2 \
  -verify_return_error </dev/null
```

The output should contain `ALPN protocol: h2` and `Verify return code: 0 (ok)`.

Create an **HTTPS** profile in MegaProxy:

- Host: `proxy.example.com`
- Port: `443`
- Username and password: values from `gost.yml`
- Allow invalid certificate: off
- IPv6: off unless the complete path and proxy destinations support it

Run **Test**. A successful connection should show HTTP/2 on the test or main screen. If the server
does not negotiate `h2`, MegaProxy can use HTTP/1.1 only when the endpoint also supports it. For an
HTTP/1.1-only GOST endpoint use an `http` handler with a `tls` listener; see the official
[GOST TLS configuration](https://v3.gost.run/en/tutorials/tls/).

## Direct SSH

MegaProxy uses the standard SSH protocol and opens `direct-tcpip` channels, the same mechanism used
by OpenSSH local forwarding. It does not require a SOCKS service on the server.

### 1. Install OpenSSH and create a dedicated user

```shell
sudo apt install --yes openssh-server
sudo systemctl enable --now ssh
sudo adduser --disabled-password --gecos "" megaproxy
sudo install -d -o megaproxy -g megaproxy -m 0700 /home/megaproxy/.ssh
sudo install -o megaproxy -g megaproxy -m 0600 /dev/null /home/megaproxy/.ssh/authorized_keys
```

Generate a dedicated key on a trusted computer. MegaProxy currently supports unencrypted private
keys, so create this key only for the profile, protect the device with a strong screen lock, and do
not reuse the key for administration:

```shell
ssh-keygen -t ed25519 -a 64 -f megaproxy_ed25519 -N ""
```

Append `megaproxy_ed25519.pub` to `/home/megaproxy/.ssh/authorized_keys` on the server. Prefix the
key with restrictions that still permit TCP forwarding:

```text
restrict,port-forwarding ssh-ed25519 EXAMPLE_PUBLIC_KEY MegaProxy
```

`restrict` disables optional SSH features and `port-forwarding` explicitly enables the feature
MegaProxy needs. Keep an administrator account separate from this service account.

### 2. Configure sshd

Create `/etc/ssh/sshd_config.d/60-megaproxy.conf`:

```text
Match User megaproxy
    AuthenticationMethods publickey
    PasswordAuthentication no
    KbdInteractiveAuthentication no
    AllowAgentForwarding no
    AllowTcpForwarding local
    X11Forwarding no
    PermitTTY no
    PermitTunnel no
    GatewayPorts no
    MaxSessions 0
    ClientAliveInterval 60
    ClientAliveCountMax 3
```

Do not replace the distribution's modern default ciphers, MACs, or key-exchange algorithms without
a measured compatibility reason. Validate the complete effective configuration before reloading:

```shell
sudo sshd -t
sudo sshd -T -C user=megaproxy,host="$(hostname)",addr=127.0.0.1 | \
  grep -E 'authenticationmethods|allowtcpforwarding|passwordauthentication|permittty'
sudo systemctl reload ssh
```

If password fallback is required, set a long random password with `sudo passwd megaproxy`, change
`AuthenticationMethods` to `any`, and set `PasswordAuthentication yes` inside this `Match` block.
Key-only authentication is preferable.

### 3. Verify and add the profile

First verify an ordinary SSH handshake from a trusted computer:

```shell
ssh -i ./megaproxy_ed25519 megaproxy@ssh.example.com
```

The hardened account cannot open shell, command, or subsystem sessions (`MaxSessions 0`) but can
still open forwarding channels. A successful authentication followed by a clean refusal of the
session channel is therefore expected.

Create an **SSH** profile in MegaProxy:

- Host: `ssh.example.com`
- Port: `22`
- Username: `megaproxy`
- Private key: contents of `megaproxy_ed25519`
- Authentication: Private key only, or Auto if password fallback was deliberately enabled
- SSH fingerprint: Default/OpenSSH
- Accept any host key: off

On the first test, compare the fingerprint shown by MegaProxy with the server value over a trusted
administrative channel:

```shell
sudo ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
```

Accept it only when the fingerprints match.

## SSH with Jump

Use two servers:

- **Jump host:** public entry point; it can open SSH only to the destination server.
- **Destination host:** accepts SSH from the jump host and opens final outbound TCP connections.

Prefer a private network between the servers. If that is unavailable, use stable public addresses
and firewall the destination SSH port to the jump host's address.

### 1. Configure the destination

Apply the Direct SSH setup to the destination using a dedicated user such as `megaproxy-dst`.
Allow `direct-tcpip` forwarding there because this server creates the final outbound connections.

Restrict its firewall so SSH is reachable from the jump host, plus a separate administrator source:

```shell
sudo ufw allow from EXAMPLE_JUMP_PRIVATE_IP to any port 22 proto tcp
```

### 2. Configure the jump host

Create a separate user and key named `megaproxy-jump`. In its `authorized_keys`, use:

```text
restrict,port-forwarding,permitopen="EXAMPLE_DESTINATION_PRIVATE_IP:22" ssh-ed25519 EXAMPLE_JUMP_PUBLIC_KEY MegaProxy-jump
```

Create `/etc/ssh/sshd_config.d/60-megaproxy-jump.conf`:

```text
Match User megaproxy-jump
    AuthenticationMethods publickey
    PasswordAuthentication no
    KbdInteractiveAuthentication no
    AllowAgentForwarding no
    AllowTcpForwarding local
    PermitOpen EXAMPLE_DESTINATION_PRIVATE_IP:22
    X11Forwarding no
    PermitTTY no
    PermitTunnel no
    GatewayPorts no
    MaxSessions 0
    ClientAliveInterval 60
    ClientAliveCountMax 3
```

Validate with `sudo sshd -t`, then reload SSH. `PermitOpen` must match the exact destination address
and port entered in MegaProxy. Hostnames can produce surprising results when DNS changes, so a
stable private IP is recommended.

### 3. Verify the chain

From a trusted computer, verify the same route conceptually used by MegaProxy:

```shell
ssh \
  -i ./megaproxy_destination_ed25519 \
  -o ProxyJump=megaproxy-jump@jump.example.com \
  megaproxy-dst@EXAMPLE_DESTINATION_PRIVATE_IP
```

Then create an **SSH with Jump** profile:

- Main host: the destination private IP or hostname as seen from the jump host
- Main credentials: the destination user and key
- Jump host: the public jump hostname
- Jump credentials: the jump user and key
- Same authentication: off when the two recommended dedicated keys are used
- Accept any host key: off for both hosts

MegaProxy confirms and stores the jump and destination host keys separately. Verify both before
accepting them.

## Reliability and operations

- Create at least two profiles on independent servers and enable **Selected profiles** failover.
- Keep DNS records, certificate-expiry monitoring, disk alerts, and provider status notifications.
- Install security updates regularly and reboot when the distribution reports that it is required.
- Pin server software versions, review release notes, and update deliberately.
- Keep the GOST configuration, SSH public keys, and infrastructure notes in an encrypted backup.
  Never back up private keys or passwords in plaintext.
- Use `journalctl -u ssh` and `docker logs megaproxy-gost` for server-side failures, but avoid debug
  logging during normal operation because proxy logs may contain destination metadata.
- Test profiles after certificate renewal, SSH host-key rotation, firewall changes, and upgrades.
- When an SSH host key intentionally changes, verify the new fingerprint through a separate trusted
  channel before replacing the stored key in MegaProxy.

## References

- [GOST v3 HTTP/2 proxy mode](https://v3.gost.run/en/tutorials/protocols/http2/)
- [GOST v3 TLS configuration](https://v3.gost.run/en/tutorials/tls/)
- [GOST v3 configuration and Linux service](https://v3.gost.run/en/getting-started/configuration-overview/)
- [Ubuntu OpenSSH server guide](https://ubuntu.com/server/docs/how-to/security/openssh-server/)
- [OpenSSH `sshd_config` manual](https://man.openbsd.org/sshd_config)
- [Certbot documentation](https://eff-certbot.readthedocs.io/en/stable/)

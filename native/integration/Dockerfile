FROM alpine:3.23.3

RUN apk add --no-cache openssh python3 && \
    adduser -D proxy && mkdir -p /home/proxy/.ssh /run/sshd && \
    printf '%s\n' 'Port 2222' 'HostKey /etc/ssh/ssh_host_ed25519_key' \
      'PasswordAuthentication yes' 'PubkeyAuthentication yes' \
      'KbdInteractiveAuthentication no' \
      'PermitRootLogin no' 'AllowUsers proxy' 'AllowTcpForwarding yes' \
      'PermitTTY no' 'X11Forwarding no' > /etc/ssh/sshd_config
COPY origin.py start-sshd.sh /fixture/
CMD ["sh", "/fixture/start-sshd.sh"]

#!/bin/sh
set -eu
# Disposable credentials and keys for this container only.
printf 'proxy:%s\n' "$TEST_PASSWORD" | chpasswd
ssh-keygen -q -t ed25519 -N '' -f /etc/ssh/ssh_host_ed25519_key
ssh-keygen -q -t ed25519 -N '' -f /home/proxy/.ssh/id_ed25519
cp /home/proxy/.ssh/id_ed25519.pub /home/proxy/.ssh/authorized_keys
chown -R proxy:proxy /home/proxy/.ssh
chmod 700 /home/proxy/.ssh
chmod 600 /home/proxy/.ssh/authorized_keys
exec /usr/sbin/sshd -D -e

# Настройка серверов для MegaProxy

[English version](../en/index.md)

В этом руководстве описана настройка надёжных серверов для MegaProxy на актуальном VPS с Ubuntu
или Debian. Рассмотрены три транспорта:

1. HTTPS-прокси с TLS, Basic Auth и HTTP/2.
2. Прямое SSH-подключение.
3. SSH через выделенный jump host.

Примеры используют стандартные протоколы, минимальную поверхность атаки, автоматическое продление
сертификатов и предсказуемое восстановление после перезагрузки. Перед использованием замените все
значения вида `EXAMPLE_VALUE`.

## Выбор топологии

| Тип профиля | Публичных серверов | Публичные порты | Когда выбирать |
| --- | ---: | --- | --- |
| HTTPS | 1 | TCP 443 и TCP 80 для выпуска сертификата | Эффективное HTTP/2-мультиплексирование и простые учётные данные |
| SSH | 1 | TCP 22 или другой выбранный SSH-порт | Стандартный OpenSSH и авторизация по ключу |
| SSH with Jump | 2 | Jump: TCP 22; destination: SSH доступен с jump | Разделение публичной точки входа и выходного сервера |

Для failover используйте серверы у разных провайдеров или хотя бы в независимых сетях. Второй
профиль на той же виртуальной машине не защищает от отказа VM, провайдера, маршрута или дата-центра.

## Общая подготовка сервера

Команды предполагают новый сервер и администратора с `sudo`. Не закрывайте существующую SSH-сессию,
пока не проверите вход во второй сессии.

```shell
sudo apt update
sudo apt full-upgrade
sudo apt install --yes ca-certificates curl openssl ufw unattended-upgrades
sudo systemctl enable --now unattended-upgrades
```

Проверьте время: неверные часы сильно затрудняют диагностику TLS и SSH.

```shell
timedatectl status
sudo systemctl enable --now systemd-timesyncd
```

Запретите входящие соединения по умолчанию и откройте только необходимые порты. Проверьте не только
UFW, но и сетевой firewall в панели провайдера.

```shell
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 22/tcp
sudo ufw enable
sudo ufw status verbose
```

Перед сменой SSH-порта сначала откройте новый порт и проверьте подключение. Не удаляйте единственный
рабочий административный доступ.

## HTTPS с HTTP/2

Используем [GOST v3](https://v3.gost.run/en/): его HTTP/2 proxy mode принимает CONNECT-потоки поверх
TLS. MegaProxy согласует `h2` через ALPN и повторно использует одно HTTP/2-соединение для нескольких
туннелей.

### 1. DNS и firewall

Создайте `A`-запись, например `proxy.example.com`, указывающую на сервер. Добавляйте `AAAA` только
при полностью рабочем публичном IPv6. Дождитесь обновления публичного DNS.

```shell
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
```

Порт 80 нужен только для ACME HTTP challenge. Оставьте его доступным для автоматического продления.

### 2. Публичный сертификат

Установите Certbot и запросите сертификат, пока порт 80 свободен:

```shell
sudo apt install --yes certbot
sudo certbot certonly --standalone \
  --domain proxy.example.com \
  --agree-tos \
  --email admin@example.com \
  --no-eff-email
sudo certbot renew --dry-run
```

Certbot обычно сразу настраивает автоматическое продление. MegaProxy проверяет системный CA,
имя хоста и срок действия, но не привязывается к конкретному сертификату, поэтому штатное
продление не требует изменения профиля.

### 3. GOST

Используйте версионированный бинарный файл или контейнер GOST v3 из
[официальных релизов](https://github.com/go-gost/gost/releases). Проверяйте опубликованную контрольную
сумму и не используйте непроверенные сторонние пакеты. Пример с контейнером проще сопровождать:

```shell
sudo apt install --yes docker.io
sudo systemctl enable --now docker
sudo install -d -m 0700 /opt/megaproxy
```

Создайте длинный случайный пароль на доверенной машине и сохраните его в менеджере паролей:

```shell
openssl rand -base64 32
```

Создайте `/opt/megaproxy/gost.yml`:

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

Защитите файл с паролем:

```shell
sudo chmod 600 /opt/megaproxy/gost.yml
```

Не включайте в GOST `mitm`, protocol sniffing, Web API, metrics или profiling. Для MegaProxy нужен
только CONNECT, а эти возможности здесь лишние.

Запустите закреплённую версию. Замените `EXAMPLE_GOST_VERSION` на проверенный тег релиза v3:

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

Проверьте запуск, не публикуя содержимое конфигурации:

```shell
sudo docker ps --filter name=megaproxy-gost
sudo docker logs --tail 100 megaproxy-gost
```

Чтобы GOST подхватывал продлённый сертификат, создайте
`/etc/letsencrypt/renewal-hooks/deploy/restart-megaproxy-gost`:

```shell
#!/bin/sh
exec /usr/bin/docker restart megaproxy-gost
```

Затем защитите и протестируйте hook:

```shell
sudo chmod 750 /etc/letsencrypt/renewal-hooks/deploy/restart-megaproxy-gost
sudo certbot renew --dry-run
```

### 4. Проверка и профиль

С другой машины проверьте сертификат и ALPN HTTP/2:

```shell
openssl s_client \
  -connect proxy.example.com:443 \
  -servername proxy.example.com \
  -alpn h2 \
  -verify_return_error </dev/null
```

В выводе должны быть `ALPN protocol: h2` и `Verify return code: 0 (ok)`.

Создайте профиль **HTTPS** в MegaProxy:

- Host: `proxy.example.com`
- Port: `443`
- Username и Password: значения из `gost.yml`
- Allow invalid certificate: выключено
- IPv6: выключено, пока весь маршрут и назначения прокси не поддерживают его

Запустите **Test**. При успешном соединении на экране теста или главном экране появится HTTP/2.
Если сервер не согласовал `h2`, MegaProxy использует HTTP/1.1 только когда endpoint также его
поддерживает. Для отдельного HTTP/1.1 endpoint GOST используйте handler `http` и listener `tls` —
см. [документацию GOST по TLS](https://v3.gost.run/en/tutorials/tls/).

## Прямой SSH

MegaProxy использует стандартный SSH и открывает каналы `direct-tcpip` — тот же механизм, что и
OpenSSH local forwarding. Отдельный SOCKS-сервис на сервере не нужен.

### 1. OpenSSH и отдельный пользователь

```shell
sudo apt install --yes openssh-server
sudo systemctl enable --now ssh
sudo adduser --disabled-password --gecos "" megaproxy
sudo install -d -o megaproxy -g megaproxy -m 0700 /home/megaproxy/.ssh
sudo install -o megaproxy -g megaproxy -m 0600 /dev/null /home/megaproxy/.ssh/authorized_keys
```

На доверенной машине создайте отдельный ключ. MegaProxy пока поддерживает только ключи без
passphrase, поэтому используйте его исключительно для этого профиля, включите надёжную блокировку
экрана телефона и не применяйте этот ключ для администрирования:

```shell
ssh-keygen -t ed25519 -a 64 -f megaproxy_ed25519 -N ""
```

Добавьте `megaproxy_ed25519.pub` в `/home/megaproxy/.ssh/authorized_keys`. Перед ключом укажите
ограничения, сохранив необходимый TCP forwarding:

```text
restrict,port-forwarding ssh-ed25519 EXAMPLE_PUBLIC_KEY MegaProxy
```

`restrict` отключает необязательные функции SSH, а `port-forwarding` явно включает необходимую
MegaProxy возможность. Административная учётная запись должна быть отдельной.

### 2. Настройка sshd

Создайте `/etc/ssh/sshd_config.d/60-megaproxy.conf`:

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

Не переопределяйте современные алгоритмы шифрования, MAC и key exchange дистрибутива без
измеримой причины: это ухудшает совместимость и делает SSH-сессию менее стандартной. Проверьте
полную эффективную конфигурацию перед reload:

```shell
sudo sshd -t
sudo sshd -T -C user=megaproxy,host="$(hostname)",addr=127.0.0.1 | \
  grep -E 'authenticationmethods|allowtcpforwarding|passwordauthentication|permittty'
sudo systemctl reload ssh
```

Если действительно нужен fallback на пароль, задайте длинный случайный пароль командой
`sudo passwd megaproxy`, поменяйте `AuthenticationMethods` на `any` и включите
`PasswordAuthentication yes` внутри этого `Match`. Авторизация только по ключу предпочтительнее.

### 3. Проверка и профиль

Сначала проверьте обычный SSH handshake с доверенной машины:

```shell
ssh -i ./megaproxy_ed25519 megaproxy@ssh.example.com
```

Усиленная учётная запись не может открывать shell, команды и subsystem (`MaxSessions 0`), но может
открывать forwarding channels. Поэтому после успешной авторизации ожидается корректный отказ
session channel.

Создайте профиль **SSH** в MegaProxy:

- Host: `ssh.example.com`
- Port: `22`
- Username: `megaproxy`
- Private key: содержимое `megaproxy_ed25519`
- Authentication: Private key only или Auto при намеренно включённом fallback на пароль
- SSH fingerprint: Default/OpenSSH
- Accept any host key: выключено

При первом тесте сравните fingerprint из MegaProxy со значением сервера, полученным по отдельному
доверенному административному каналу:

```shell
sudo ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
```

Принимайте ключ только при полном совпадении fingerprint.

## SSH with Jump

Используйте два сервера:

- **Jump host** — публичная точка входа, которая может подключаться по SSH только к destination.
- **Destination host** — принимает SSH от jump и создаёт конечные исходящие TCP-соединения.

Предпочтительна приватная сеть между серверами. Иначе используйте стабильные публичные адреса и
разрешите на destination SSH только с адреса jump.

### 1. Destination

Повторите настройку прямого SSH на destination для отдельного пользователя, например
`megaproxy-dst`. Здесь нужен `direct-tcpip`, потому что именно этот сервер создаёт конечные
исходящие соединения.

Ограничьте firewall: SSH с jump и отдельный административный источник.

```shell
sudo ufw allow from EXAMPLE_JUMP_PRIVATE_IP to any port 22 proto tcp
```

### 2. Jump host

Создайте отдельного пользователя и ключ `megaproxy-jump`. В `authorized_keys` используйте:

```text
restrict,port-forwarding,permitopen="EXAMPLE_DESTINATION_PRIVATE_IP:22" ssh-ed25519 EXAMPLE_JUMP_PUBLIC_KEY MegaProxy-jump
```

Создайте `/etc/ssh/sshd_config.d/60-megaproxy-jump.conf`:

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

Проверьте `sudo sshd -t` и перезагрузите конфигурацию SSH. `PermitOpen` должен точно совпадать с
адресом и портом destination в MegaProxy. DNS может измениться неожиданно, поэтому стабильный
приватный IP надёжнее hostname.

### 3. Проверка цепочки

С доверенной машины проверьте маршрут, концептуально совпадающий с работой MegaProxy:

```shell
ssh \
  -i ./megaproxy_destination_ed25519 \
  -o ProxyJump=megaproxy-jump@jump.example.com \
  megaproxy-dst@EXAMPLE_DESTINATION_PRIVATE_IP
```

Создайте профиль **SSH with Jump**:

- Main host: приватный IP или hostname destination, видимый с jump
- Main credentials: пользователь и ключ destination
- Jump host: публичное имя jump
- Jump credentials: пользователь и ключ jump
- Same authentication: выключено при использовании двух рекомендованных ключей
- Accept any host key: выключено для обоих серверов

MegaProxy подтверждает и сохраняет host key jump и destination отдельно. Проверьте оба ключа перед
принятием.

## Надёжность и эксплуатация

- Создайте минимум два профиля на независимых серверах и включите failover **Selected profiles**.
- Контролируйте DNS, срок сертификата, свободное место и сообщения о состоянии провайдера.
- Регулярно устанавливайте security updates и перезагружайте сервер, когда это требуется.
- Закрепляйте версии серверного ПО, читайте release notes и обновляйтесь осознанно.
- Храните конфигурацию GOST, публичные SSH-ключи и описание инфраструктуры в зашифрованной резервной
  копии. Не сохраняйте приватные ключи и пароли открытым текстом.
- Для серверной диагностики используйте `journalctl -u ssh` и `docker logs megaproxy-gost`, но не
  держите debug logging включённым постоянно: журналы прокси могут содержать метаданные назначений.
- Проверяйте профили после продления сертификата, ротации SSH host key, изменения firewall и
  обновления системы.
- При намеренной смене SSH host key сначала проверьте новый fingerprint через независимый
  доверенный канал и только затем заменяйте сохранённый ключ в MegaProxy.

## Официальная документация

- [GOST v3: HTTP/2 proxy mode](https://v3.gost.run/en/tutorials/protocols/http2/)
- [GOST v3: TLS](https://v3.gost.run/en/tutorials/tls/)
- [GOST v3: конфигурация и Linux service](https://v3.gost.run/en/getting-started/configuration-overview/)
- [Ubuntu: OpenSSH server](https://ubuntu.com/server/docs/how-to/security/openssh-server/)
- [OpenSSH: `sshd_config`](https://man.openbsd.org/sshd_config)
- [Certbot](https://eff-certbot.readthedocs.io/en/stable/)

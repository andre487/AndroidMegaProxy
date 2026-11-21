"""Build and compare the production core with pinned sing-box in private Linux networks."""

import argparse
import contextlib
import csv
import hashlib
import json
import os
import pathlib
import platform
import secrets
import shutil
import socket
import statistics
import subprocess
import tempfile
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
SING_BOX = "v1.14.4"
SING_BOX_NEXT = "v1.15.0-alpha.13"
GOST_COMMIT = "cb76f63754768c7b5d68895a0d51635b0141b80f"  # v3.3.0
TRANSPORTS = {
    "socks5": {"udp": True, "sing_box": True},
    "https-h1": {"udp": False, "sing_box": True},
    "https-h2": {"udp": False, "sing_box": True, "preview": True},
    "https-h3": {"udp": True, "sing_box": True, "preview": True},
    "masque": {"udp": True, "sing_box": True, "preview": True},
    "ssh": {"udp": False, "sing_box": True},
    "https-jump-h1": {"udp": False, "sing_box": True},
    "https-jump-h2": {"udp": False, "sing_box": True, "preview": True},
    "https-jump-h3": {"udp": True, "sing_box": True, "preview": True},
    "ssh-jump": {"udp": False, "sing_box": True},
}
ORIGIN = "198.19.0.1"
PROXY = "198.18.0.1"
CLIENT = "198.18.0.2"


def command(*args, **kwargs):
    kwargs.setdefault("check", True)
    return subprocess.run([str(arg) for arg in args], **kwargs)


def capture(*args):
    return command(*args, capture_output=True, text=True).stdout.strip()


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def build(output):
    output.mkdir(parents=True, exist_ok=False)
    env = dict(os.environ, CGO_ENABLED="0", GOTOOLCHAIN="local", GOFLAGS="")
    command(
        "go",
        "build",
        "-trimpath",
        "-o",
        output / "megaproxy-bench",
        "./cmd/corebench",
        cwd=ROOT / "native",
        env=env,
    )
    for binary, version in (("sing-box", SING_BOX), ("sing-box-next", SING_BOX_NEXT)):
        with tempfile.TemporaryDirectory(prefix="megaproxy-sing-build-") as temporary:
            command(
                "go",
                "install",
                "-trimpath",
                "-tags="
                + (
                    "with_gvisor,with_quic"
                    if version == SING_BOX_NEXT
                    else "with_gvisor"
                ),
                f"-ldflags=-X github.com/sagernet/sing-box/constant.Version={version[1:]}",
                f"github.com/sagernet/sing-box/cmd/sing-box@{version}",
                env=dict(env, GOBIN=temporary),
            )
            shutil.copy2(pathlib.Path(temporary) / "sing-box", output / binary)
    with tempfile.TemporaryDirectory(prefix="megaproxy-gost-source-") as temporary:
        source = pathlib.Path(temporary)
        command("git", "init", "-q", source)
        command(
            "git",
            "-C",
            source,
            "fetch",
            "-q",
            "--depth=1",
            "https://github.com/go-gost/gost.git",
            GOST_COMMIT,
            env=env,
        )
        command("git", "-C", source, "checkout", "-q", "--detach", "FETCH_HEAD")
        command(
            "go",
            "build",
            "-trimpath",
            "-o",
            output / "gost",
            "./cmd/gost",
            cwd=source,
            env=env,
        )
    save(
        output / "build.json",
        {
            "commit": capture("git", "-C", ROOT, "rev-parse", "HEAD"),
            "dirty": bool(capture("git", "-C", ROOT, "status", "--porcelain")),
            "sing_box": SING_BOX,
            "sing_box_next": SING_BOX_NEXT,
            "gost_commit": GOST_COMMIT,
            "go": capture("go", "version"),
            "binaries": {
                name: {
                    "sha256": hashlib.sha256((output / name).read_bytes()).hexdigest(),
                    "build_info": capture("go", "version", "-m", output / name),
                }
                for name in ("megaproxy-bench", "sing-box", "sing-box-next", "gost")
            },
        },
    )


@contextlib.contextmanager
def process(args, log, cpus):
    with log.open("w") as stream:
        child = subprocess.Popen(
            [str(arg) for arg in args],
            stdout=stream,
            stderr=subprocess.STDOUT,
            env=dict(os.environ, GOMAXPROCS="1"),
        )
        try:
            os.sched_setaffinity(child.pid, cpus)
            yield child
        finally:
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait()


def ready(child, check):
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if child.poll() is not None:
            raise RuntimeError("Benchmark process exited; inspect its log")
        if check():
            return
        time.sleep(0.05)
    raise RuntimeError("Benchmark process did not become ready")


def listening(port, host=ORIGIN):
    try:
        with socket.create_connection((host, port), timeout=0.1):
            return True
    except OSError:
        return False


def usage(pid):
    try:
        fields = pathlib.Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
    except FileNotFoundError:
        return None
    return (
        (int(fields[11]) + int(fields[12])) / os.sysconf("SC_CLK_TCK"),
        int(fields[21]) * os.sysconf("SC_PAGE_SIZE"),
    )


def measure(args, child):
    initial = usage(child.pid) if child else None
    rss = []
    stop = threading.Event()

    def sample():
        while not stop.is_set():
            current = usage(child.pid) if child else None
            if current:
                rss.append(current[1])
            stop.wait(0.02)

    thread = threading.Thread(target=sample)
    thread.start()
    started = time.monotonic()
    try:
        try:
            result = command(
                *args, capture_output=True, text=True, timeout=60, check=False
            )
            try:
                raw = json.loads(result.stdout)
            except ValueError:
                raw = {
                    "error": result.stderr.strip() or "Invalid measurement JSON",
                    "stdout": result.stdout,
                }
            if result.returncode:
                raw.setdefault(
                    "error", f"Measurement exited with code {result.returncode}"
                )
            if result.stderr:
                raw["stderr"] = result.stderr
        except subprocess.TimeoutExpired:
            raw = {"error": "Measurement timed out after 60 seconds"}
        final = usage(child.pid) if child else None
        if child and child.poll() is not None:
            raw["error"] = "Core exited during measurement"
    finally:
        stop.set()
        thread.join()
    elapsed = time.monotonic() - started
    cpu = final[0] - initial[0] if initial and final else None
    return raw, {
        "core_cpu_seconds": cpu,
        "core_cpu_percent": cpu / elapsed * 100 if cpu is not None else None,
        "core_peak_rss_bytes": max(rss, default=0) if child else None,
        "measurement_wall_seconds": elapsed,
    }


def iperf_metrics(data, udp):
    if "error" in data:
        raise RuntimeError(f"iperf3 failed: {data['error']}")
    end = data["end"]
    receiver = end["sum_received"]
    result = {"receiver_mbps": receiver["bits_per_second"] / 1_000_000}
    if udp:
        result.update(
            udp_loss_percent=receiver["lost_percent"],
            udp_jitter_ms=receiver["jitter_ms"],
        )
    else:
        result["tcp_retransmits"] = end["sum_sent"].get("retransmits")
    return result


def summary(rows, output):
    fields = [
        "transport",
        "engine",
        "scenario",
        "streams",
        "sample",
        "status",
        "error",
        "receiver_mbps",
        "udp_loss_percent",
        "udp_jitter_ms",
        "operations_per_second",
        "p50_ms",
        "p95_ms",
        "p99_ms",
        "core_cpu_seconds",
        "core_cpu_percent",
        "core_peak_rss_bytes",
        "measurement_wall_seconds",
    ]
    with (output / "samples.csv").open("w") as file:
        writer = csv.DictWriter(file, fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)
    report = [
        "# Core comparison",
        "",
        "Medians of passed samples; failed measurements remain explicit below and in raw JSON.",
        "",
        "| Transport | Engine | Scenario | Streams | Passed/total | Mbit/s | Ops/s | p95 ms | UDP loss % | Core CPU % | Peak RSS MiB |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    groups = sorted(
        {
            (row["transport"], row["engine"], row["scenario"], row["streams"])
            for row in rows
        }
    )
    for transport, engine, scenario, streams in groups:
        selected = [
            row
            for row in rows
            if (row["transport"], row["engine"], row["scenario"], row["streams"])
            == (transport, engine, scenario, streams)
        ]
        values = []
        for key in (
            "receiver_mbps",
            "operations_per_second",
            "p95_ms",
            "udp_loss_percent",
            "core_cpu_percent",
            "core_peak_rss_bytes",
        ):
            samples = [
                row[key]
                for row in selected
                if row["status"] == "passed" and row.get(key) is not None
            ]
            value = statistics.median(samples) if samples else None
            if value is not None and key == "core_peak_rss_bytes":
                value /= 1024 * 1024
            values.append(f"{value:.3f}" if value is not None else "—")
        report.append(
            f"| {transport} | {engine} | {scenario} | {streams} | {sum(row['status'] == 'passed' for row in selected)}/{len(selected)} | "
            + " | ".join(values)
            + " |"
        )
    report.extend(
        [
            "",
            "TCP forward/reverse and UDP: receiver goodput. UDP offered load is fixed across stream counts.",
            "Latency: 64-byte sequential echo; connect includes a new TCP connection plus echo.",
            "CPU/RSS cover only the core process; iperf runs include one omitted warm-up second.",
            "Direct bypasses both TUN and the proxy and is a ceiling/control, not a third proxy core.",
            "VM scheduling, shared server/load CPU and load-generator limits can dominate. No Android/JNI/energy inference.",
        ]
    )
    failures = [row for row in rows if row["status"] == "failed"]
    if failures:
        report.extend(["", "## Failed measurements", ""])
        for row in failures:
            report.append(
                f"- {row['transport']}/{row['engine']} {row['scenario']} streams={row['streams']} sample={row['sample']}: {row['error']}"
            )
    unsupported = sorted(
        {
            row["transport"]
            for row in rows
            if row["transport"] in TRANSPORTS
            and not TRANSPORTS[row["transport"]]["sing_box"]
        }
    )
    if unsupported:
        report.extend(
            [
                "",
                "No equivalent sing-box HTTP/2, HTTP/3 or MASQUE outbound; MegaProxy-only measurements: "
                + ", ".join(unsupported)
                + ".",
            ]
        )
    report.extend(
        [
            "",
            f"sing-box {SING_BOX}: original pairs; {SING_BOX_NEXT} (prerelease): HTTP/2, HTTP/3 and MASQUE pairs. No version fallback for prerelease HTTP outbounds.",
        ]
    )
    (output / "summary.md").write_text("\n".join(report) + "\n")


def transport_configs(name, material):
    """Use identical peers and credentials; chain exits are unreachable on client loopback."""
    native = dict(
        type="SOCKS5",
        host=PROXY,
        dialHost=PROXY,
        port=1080,
        dohUrl="https://dns.invalid/dns-query",
        bypassLocalNetworks=False,
    )
    outbound = dict(
        type="socks", tag="proxy", server=PROXY, server_port=1080, version="5"
    )
    if name == "socks5":
        return native, [outbound]
    if name.startswith("https") or name == "masque":
        version = 3 if name == "masque" else int(name[-1])
        jumped = name.startswith("https-jump")
        host, port = (
            ("127.0.0.1", 9442 + version) if jumped else (PROXY, 8442 + version)
        )
        native.update(
            type="HTTPS_JUMP" if jumped else "MASQUE" if name == "masque" else "HTTPS",
            host=host,
            dialHost=host,
            port=port,
            username="bench",
            password=material["password"],
            profile="CHROME_ANDROID",
            preferHttp3=version == 3 and name != "masque",
        )
        outbound = dict(
            type="http",
            tag="proxy",
            server=host,
            server_port=port,
            username="bench",
            password=material["password"],
            tls=dict(enabled=True, alpn=["http/1.1"]),
        )
        if TRANSPORTS[name].get("preview"):
            outbound.update(version=version, disable_version_fallback=True)
            outbound["tls"]["alpn"] = ["h2" if version == 2 else "h3"]
        outbounds = [outbound]
        if jumped:
            native.update(
                jumpHost=PROXY,
                jumpDialHost=PROXY,
                jumpPort=8442 + version,
                jumpUsername="bench",
                jumpPassword=material["password"],
            )
            outbound["detour"] = "jump"
            outbounds.append(
                dict(outbound, tag="jump", server=PROXY, server_port=8442 + version)
            )
            del outbounds[-1]["detour"]
        return native, outbounds if TRANSPORTS[name]["sing_box"] else None
    jumped = name == "ssh-jump"
    host, port, key = ("127.0.0.1", 2223, "exit") if jumped else (PROXY, 2222, "entry")
    native.update(
        type="SSH_JUMP" if jumped else "SSH",
        host=host,
        dialHost=host,
        port=port,
        username="root",
        privateKey=material["private_key"],
        trustedHostKey=material[f"{key}_fingerprint"],
        sshProfile="OPENSSH_TERMUX",
    )
    outbound = dict(
        type="ssh",
        tag="proxy",
        server=host,
        server_port=port,
        user="root",
        private_key_path=str(material["key_path"]),
        host_key=[material[f"{key}_public_key"]],
        cipher=["chacha20-poly1305@openssh.com"],
        kex_algorithm=["curve25519-sha256"],
        client_version="SSH-2.0-OpenSSH_8.4",
    )
    outbounds = [outbound]
    if jumped:
        native.update(
            jumpHost=PROXY,
            jumpDialHost=PROXY,
            jumpPort=2222,
            jumpUsername="root",
            jumpPrivateKey=material["private_key"],
            jumpTrustedHostKey=material["entry_fingerprint"],
        )
        outbound["detour"] = "jump"
        outbounds.append(
            dict(
                outbound,
                tag="jump",
                server=PROXY,
                server_port=2222,
                host_key=[material["entry_public_key"]],
            )
        )
        del outbounds[-1]["detour"]
    return native, outbounds


def start_transport_servers(stack, binaries, private, output, cpus):
    password = secrets.token_urlsafe(24)
    certificate, tls_key = private / "certificate.pem", private / "tls-key.pem"
    command(
        "openssl",
        "req",
        "-new",
        "-x509",
        "-newkey",
        "ec",
        "-pkeyopt",
        "ec_paramgen_curve:P-256",
        "-nodes",
        "-subj",
        "/CN=benchmark",
        "-days",
        "1",
        "-keyout",
        tls_key,
        "-out",
        certificate,
        "-addext",
        f"subjectAltName=IP:{PROXY},IP:127.0.0.1",
        "-addext",
        "basicConstraints=critical,CA:TRUE",
        capture_output=True,
    )
    # Both clients validate the same private test certificate without changing system trust.
    os.environ["SSL_CERT_FILE"] = str(certificate)
    services = []
    for host, base in ((PROXY, 8442), ("127.0.0.1", 9442)):
        for version, handler, listener, alpn in (
            (1, "http", "tls", "http/1.1"),
            (2, "http2", "http2", "h2"),
            (3, "masque", "http3", "h3"),
        ):
            services.append(
                dict(
                    name=f"{host}-{version}",
                    addr=f"{host}:{base + version}",
                    handler=dict(
                        type=handler, auth=dict(username="bench", password=password)
                    ),
                    listener=dict(
                        type=listener,
                        tls=dict(
                            certFile=str(certificate),
                            keyFile=str(tls_key),
                            options=dict(alpn=[alpn]),
                        ),
                        metadata=dict(enableDatagrams=True),
                    ),
                )
            )
    gost_config = private / "gost.json"
    save(gost_config, dict(services=services, log=dict(level="error")))
    gost = stack.enter_context(
        process([binaries / "gost", "-C", gost_config], output / "gost.log", cpus)
    )
    ready(
        gost,
        lambda: all(
            listening(port, host)
            for host, port in (
                (PROXY, 8443),
                (PROXY, 8444),
                ("127.0.0.1", 9443),
                ("127.0.0.1", 9444),
            )
        ),
    )
    ready(
        gost,
        lambda: all(
            any(
                token.endswith(f":{port}")
                for token in capture("ss", "-H", "-lun").split()
            )
            for port in (8445, 9445)
        ),
    )
    client_key = private / "ssh-client"
    command(
        "ssh-keygen",
        "-q",
        "-t",
        "ed25519",
        "-N",
        "",
        "-C",
        "benchmark",
        "-f",
        client_key,
        capture_output=True,
    )
    material = dict(
        password=password, key_path=client_key, private_key=client_key.read_text()
    )
    daemons = [gost]
    for name, host, port in (("entry", PROXY, 2222), ("exit", "127.0.0.1", 2223)):
        key = private / f"ssh-{name}"
        command(
            "ssh-keygen",
            "-q",
            "-t",
            "ed25519",
            "-N",
            "",
            "-C",
            "benchmark",
            "-f",
            key,
            capture_output=True,
        )
        material[f"{name}_public_key"] = key.with_suffix(".pub").read_text().strip()
        material[f"{name}_fingerprint"] = capture(
            "ssh-keygen", "-lf", key.with_suffix(".pub")
        ).split()[1]
        config = private / f"sshd-{name}.conf"
        config.write_text(f"""Port {port}
ListenAddress {host}
HostKey {key}
PidFile {private / (name + '.pid')}
AuthorizedKeysFile {client_key}.pub
StrictModes no
PermitRootLogin yes
UsePAM yes
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
AuthenticationMethods publickey
AllowTcpForwarding yes
PermitOpen {ORIGIN}:* 127.0.0.1:2223
MaxSessions 0
PermitTTY no
X11Forwarding no
UseDNS no
Ciphers chacha20-poly1305@openssh.com
KexAlgorithms curve25519-sha256
LogLevel ERROR
""")
        daemon = stack.enter_context(
            process(
                [shutil.which("sshd"), "-D", "-e", "-f", config],
                output / f"sshd-{name}.log",
                cpus,
            )
        )
        ready(daemon, lambda host=host, port=port: listening(port, host))
        daemons.append(daemon)
    return material, daemons


def run(args):
    if os.geteuid() != 0 or os.readlink("/proc/self/ns/net") == os.readlink(
        "/proc/1/ns/net"
    ):
        raise RuntimeError(
            "Run inside a private network namespace: sudo unshare --net -- python3 scripts/compare_cores.py run ..."
        )
    available = sorted(os.sched_getaffinity(0))
    if len(available) < 2:
        raise RuntimeError("At least two available CPUs are required")
    core_cpus, other_cpus = {available[0]}, set(available[1:])
    os.sched_setaffinity(0, other_cpus)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    binaries = args.binaries.resolve()
    metadata = json.loads((binaries / "build.json").read_text())
    for name, info in metadata["binaries"].items():
        if hashlib.sha256((binaries / name).read_bytes()).hexdigest() != info["sha256"]:
            raise RuntimeError(f"Binary hash mismatch: {name}")
    metadata.update(
        kernel=platform.release(),
        cpu_model=next(
            (
                line.split(":", 1)[1].strip()
                for line in pathlib.Path("/proc/cpuinfo").read_text().splitlines()
                if line.startswith("model name")
            ),
            "unknown",
        ),
        available_cpus=available,
        core_cpus=sorted(core_cpus),
        other_cpus=sorted(other_cpus),
        mtu=args.mtu,
        underlay_mtu=args.link_mtu,
        runner_sha256=hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),
        samples=args.samples,
        seconds=args.seconds,
        udp_mbps=args.udp_mbps,
        iperf_version=capture("iperf3", "--version"),
        sing_box_version=capture(binaries / "sing-box", "version"),
        sing_box_next_version=capture(binaries / "sing-box-next", "version"),
        scope="IPv4; gVisor sing-box stack; strict source validation off; numeric origins, no DNS",
        transport_selection=args.transport,
        gost_version=capture(binaries / "gost", "-V"),
        openssl_version=capture("openssl", "version"),
        openssh_version=command(
            "sshd", "-V", capture_output=True, text=True, check=False
        ).stderr.strip(),
        process_isolation="fresh warmed core per scenario/sample",
        tls_verification="private test CA via SSL_CERT_FILE; verification enabled",
        ssh_algorithms="curve25519-sha256; chacha20-poly1305@openssh.com; pinned Ed25519 host keys",
        load_before=pathlib.Path("/proc/loadavg").read_text().strip(),
    )
    save(output / "environment.json", metadata)
    selected = list(TRANSPORTS) if args.transport == "all" else [args.transport]
    save(output / "support.json", {name: TRANSPORTS[name] for name in selected})
    server_config = {
        "log": {"disabled": True},
        "inbounds": [{"type": "socks", "listen": PROXY, "listen_port": 1080}],
        "outbounds": [{"type": "direct"}],
    }
    save(output / "socks-server.json", server_config)
    rows = []
    try:
        with contextlib.ExitStack() as stack:
            keeper = stack.enter_context(
                process(
                    ["unshare", "--net", "sleep", "infinity"],
                    output / "namespace.log",
                    other_cpus,
                )
            )
            ready(
                keeper,
                lambda: os.readlink(f"/proc/{keeper.pid}/ns/net")
                != os.readlink("/proc/self/ns/net"),
            )
            prefix = ["nsenter", f"--net=/proc/{keeper.pid}/ns/net", "--"]

            def ip(*parts, client=False):
                command(
                    *(prefix if client else []),
                    "ip",
                    *parts,
                    capture_output=True,
                    text=True,
                )

            ip("link", "set", "lo", "up")
            ip("addr", "add", f"{ORIGIN}/32", "dev", "lo")
            ip("link", "add", "server0", "type", "veth", "peer", "name", "client0")
            ip("link", "set", "client0", "netns", str(keeper.pid))
            for name, address, client in (
                ("server0", PROXY, False),
                ("client0", CLIENT, True),
            ):
                ip("addr", "add", f"{address}/30", "dev", name, client=client)
                ip("link", "set", name, "mtu", str(args.link_mtu), "up", client=client)
            ip("link", "set", "lo", "up", client=True)
            echo = stack.enter_context(
                process(
                    [binaries / "megaproxy-bench", "-mode", "echo"],
                    output / "echo.log",
                    other_cpus,
                )
            )
            ready(echo, lambda: "READY" in (output / "echo.log").read_text())
            server = stack.enter_context(
                process(
                    ["iperf3", "-s", "-B", ORIGIN],
                    output / "iperf-server.log",
                    other_cpus,
                )
            )
            ready(server, lambda: listening(5201))
            proxy = stack.enter_context(
                process(
                    [binaries / "sing-box", "run", "-c", output / "socks-server.json"],
                    output / "socks-server.log",
                    other_cpus,
                )
            )

            def proxy_ready():
                try:
                    with socket.create_connection((PROXY, 1080), timeout=0.1):
                        return True
                except OSError:
                    return False

            ready(proxy, proxy_ready)
            private = pathlib.Path(
                stack.enter_context(
                    tempfile.TemporaryDirectory(prefix="megaproxy-comparison-secrets-")
                )
            )
            material, daemons = start_transport_servers(
                stack, binaries, private, output, other_cpus
            )
            cases = [
                (kind, streams)
                for kind in ("tcp_forward", "tcp_reverse", "udp", "connect")
                for streams in (1, 8)
            ] + [("tcp_latency", 1), ("udp_latency", 1)]
            combinations = [("direct", "direct")]
            for transport in selected:
                combinations.append((transport, "megaproxy"))
                if TRANSPORTS[transport]["sing_box"]:
                    combinations.append((transport, "sing-box"))
            for sample in range(args.samples):
                order = (
                    combinations if sample % 2 == 0 else list(reversed(combinations))
                )
                for transport, engine in order:
                    for scenario, streams in cases:
                        if (
                            engine != "direct"
                            and not TRANSPORTS[transport]["udp"]
                            and scenario in ("udp", "udp_latency")
                        ):
                            continue
                        with contextlib.ExitStack() as session:
                            child = None
                            startup_error = None
                            try:
                                if engine == "direct":
                                    ip(
                                        "route",
                                        "replace",
                                        f"{ORIGIN}/32",
                                        "via",
                                        PROXY,
                                        "dev",
                                        "client0",
                                        client=True,
                                    )
                                else:
                                    log = (
                                        output
                                        / f"{sample}-{transport}-{engine}-{scenario}-{streams}.log"
                                    )
                                    native_config, outbounds = transport_configs(
                                        transport, material
                                    )
                                    native_path = private / "native.json"
                                    save(native_path, native_config)
                                    client_path = private / "sing-client.json"
                                    save(
                                        client_path,
                                        {
                                            "log": {"disabled": True},
                                            "inbounds": [
                                                {
                                                    "type": "tun",
                                                    "interface_name": "bench0",
                                                    "address": ["198.18.1.1/30"],
                                                    "mtu": args.mtu,
                                                    "stack": "gvisor",
                                                    "auto_route": False,
                                                }
                                            ],
                                            "outbounds": outbounds,
                                            "route": {"final": "proxy"},
                                        },
                                    )
                                    core = (
                                        [
                                            binaries / "megaproxy-bench",
                                            "-mode",
                                            "core",
                                            "-mtu",
                                            str(args.mtu),
                                            "-config",
                                            str(native_path),
                                        ]
                                        if engine == "megaproxy"
                                        else [
                                            binaries
                                            / (
                                                "sing-box-next"
                                                if TRANSPORTS[transport].get("preview")
                                                else "sing-box"
                                            ),
                                            "run",
                                            "-c",
                                            client_path,
                                        ]
                                    )
                                    child = session.enter_context(
                                        process(prefix + core, log, core_cpus)
                                    )

                                    def tun_ready():
                                        return (
                                            subprocess.run(
                                                prefix
                                                + ["ip", "link", "show", "bench0"],
                                                stdout=subprocess.DEVNULL,
                                                stderr=subprocess.DEVNULL,
                                            ).returncode
                                            == 0
                                        )

                                    ready(child, tun_ready)
                                    if engine == "megaproxy":
                                        ready(child, lambda: "READY" in log.read_text())
                                    ip(
                                        "addr",
                                        "replace",
                                        "198.18.1.1/30",
                                        "dev",
                                        "bench0",
                                        client=True,
                                    )
                                    ip(
                                        "link",
                                        "set",
                                        "bench0",
                                        "mtu",
                                        str(args.mtu),
                                        "up",
                                        client=True,
                                    )
                                    ip(
                                        "route",
                                        "replace",
                                        f"{ORIGIN}/32",
                                        "dev",
                                        "bench0",
                                        client=True,
                                    )
                                # Warm the data path before measuring; this must succeed without a direct fallback.
                                command(
                                    *prefix,
                                    binaries / "megaproxy-bench",
                                    "-mode",
                                    "probe",
                                    "-duration",
                                    "200ms",
                                    capture_output=True,
                                    text=True,
                                    timeout=10,
                                )
                            except (RuntimeError, subprocess.SubprocessError) as error:
                                startup_error = (
                                    f"Startup/warm-up failed: {type(error).__name__}"
                                )
                                if (
                                    isinstance(error, subprocess.CalledProcessError)
                                    and error.stderr
                                ):
                                    startup_error += ": " + error.stderr.strip()
                            print(
                                f"Sample {sample + 1}/{args.samples}: {transport}/{engine} {scenario} streams={streams}",
                                flush=True,
                            )
                            if scenario in ("tcp_forward", "tcp_reverse", "udp"):
                                load = [
                                    "iperf3",
                                    "-c",
                                    ORIGIN,
                                    "-J",
                                    "-t",
                                    str(args.seconds),
                                    "-O",
                                    "1",
                                    "-P",
                                    str(streams),
                                ]
                                if scenario == "tcp_reverse":
                                    load += ["-R"]
                                if scenario == "udp":
                                    load += [
                                        "-u",
                                        "-b",
                                        str(int(args.udp_mbps * 1_000_000 / streams)),
                                        "-l",
                                        "1200",
                                    ]
                            else:
                                load = [
                                    str(binaries / "megaproxy-bench"),
                                    "-mode",
                                    "probe",
                                    "-duration",
                                    f"{args.seconds}s",
                                    "-streams",
                                    str(streams),
                                    "-network",
                                    "udp" if scenario == "udp_latency" else "tcp",
                                ]
                                if scenario == "connect":
                                    load += ["-connect"]

                            def counters():
                                link = json.loads(
                                    capture(
                                        *prefix,
                                        "ip",
                                        "-j",
                                        "-s",
                                        "link",
                                        "show",
                                        "bench0",
                                    )
                                )[0]["stats64"]
                                return [
                                    link[direction]["bytes"]
                                    for direction in ("rx", "tx")
                                ]

                            alive = startup_error is None and (
                                child is None or child.poll() is None
                            )
                            before = counters() if child and alive else None
                            raw, resource = (
                                measure(prefix + load, child)
                                if alive
                                else (
                                    {"error": startup_error or "Core already exited"},
                                    {},
                                )
                            )
                            if child and alive and child.poll() is None:
                                after = counters()
                                raw["tun_bytes_before_after"] = [before, after]
                                if any(
                                    end <= start for start, end in zip(before, after)
                                ):
                                    raw.setdefault(
                                        "error",
                                        "Measured traffic did not traverse TUN in both directions",
                                    )
                            if "error" in raw:
                                metrics = {"status": "failed", "error": raw["error"]}
                                print(
                                    f"FAILED: {transport}/{engine} {scenario}: {raw['error']}",
                                    flush=True,
                                )
                            else:
                                metrics = (
                                    iperf_metrics(raw, scenario == "udp")
                                    if load[0] == "iperf3"
                                    else dict(
                                        operations_per_second=raw[
                                            "operations_per_second"
                                        ],
                                        **dict(
                                            zip(
                                                ("p50_ms", "p95_ms", "p99_ms"),
                                                raw["latency_ms_p50_p95_p99"],
                                            )
                                        ),
                                    )
                                )
                                metrics["status"] = "passed"
                            row = dict(
                                transport=transport,
                                engine=engine,
                                scenario=scenario,
                                streams=streams,
                                sample=sample + 1,
                                **metrics,
                                **resource,
                            )
                            rows.append(row)
                            save(
                                output
                                / f"{sample}-{transport}-{engine}-{scenario}-{streams}.json",
                                {"command": load, "raw": raw, "measurement": row},
                            )
                            save(output / "samples.json", rows)
                            if any(
                                p.poll() is not None
                                for p in (echo, server, proxy, *daemons)
                            ):
                                raise RuntimeError(
                                    "Benchmark server exited during measurement"
                                )
            summary(rows, output)
            failed = sum(row["status"] == "failed" for row in rows)
            save(
                output / "status.json", {"passed": len(rows) - failed, "failed": failed}
            )
            if failed:
                raise RuntimeError(
                    f"{failed} measurements failed; inspect summary.md and raw JSON"
                )
    finally:
        # sudo should not leave benchmark artifacts owned by root in the caller's workspace.
        if "SUDO_UID" in os.environ:
            for path in [*output.rglob("*"), output]:
                os.chown(path, int(os.environ["SUDO_UID"]), int(os.environ["SUDO_GID"]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    prepare = commands.add_parser(
        "build", help="Build both cores with the same installed Go toolchain"
    )
    prepare.add_argument("--output", type=pathlib.Path, required=True)
    bench = commands.add_parser(
        "run", help="Run inside a fresh root network namespace; no Go toolchain needed"
    )
    bench.add_argument("--binaries", type=pathlib.Path, required=True)
    bench.add_argument("--output", type=pathlib.Path, required=True)
    bench.add_argument("--samples", type=int, default=5)
    bench.add_argument("--seconds", type=int, default=3)
    bench.add_argument("--mtu", type=int, default=1400)
    bench.add_argument("--link-mtu", type=int, default=1500)
    bench.add_argument("--udp-mbps", type=float, default=20)
    bench.add_argument("--transport", choices=["all", *TRANSPORTS], default="all")
    args = parser.parse_args()
    if args.action == "build":
        build(args.output.resolve())
    else:
        if not (
            1 <= args.samples <= 30
            and 1 <= args.seconds <= 30
            and 1280 <= args.mtu <= 65535
            and 1280 <= args.link_mtu <= 65535
            and 0 < args.udp_mbps <= 10000
        ):
            parser.error("Invalid benchmark samples, seconds, MTU or UDP offered load")
        for tool in (
            "ip",
            "nsenter",
            "unshare",
            "iperf3",
            "openssl",
            "ssh-keygen",
            "sshd",
            "ss",
        ):
            if not shutil.which(tool):
                parser.error(f"Missing benchmark tool: {tool}")
        run(args)


if __name__ == "__main__":
    main()

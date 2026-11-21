"""Build and compare the production core with pinned sing-box in private Linux networks."""

import argparse
import contextlib
import csv
import hashlib
import json
import os
import pathlib
import platform
import shutil
import socket
import statistics
import subprocess
import threading
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
SING_BOX = "v1.14.4"
ORIGIN = "198.19.0.1"
PROXY = "198.18.0.1"
CLIENT = "198.18.0.2"


def command(*args, **kwargs):
    return subprocess.run([str(arg) for arg in args], check=True, **kwargs)


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
    command(
        "go",
        "install",
        "-trimpath",
        "-tags=with_gvisor",
        f"-ldflags=-X github.com/sagernet/sing-box/constant.Version={SING_BOX[1:]}",
        f"github.com/sagernet/sing-box/cmd/sing-box@{SING_BOX}",
        env=dict(env, GOBIN=str(output)),
    )
    save(
        output / "build.json",
        {
            "commit": capture("git", "-C", ROOT, "rev-parse", "HEAD"),
            "dirty": bool(capture("git", "-C", ROOT, "status", "--porcelain")),
            "sing_box": SING_BOX,
            "go": capture("go", "version"),
            "binaries": {
                name: {
                    "sha256": hashlib.sha256((output / name).read_bytes()).hexdigest(),
                    "build_info": capture("go", "version", "-m", output / name),
                }
                for name in ("megaproxy-bench", "sing-box")
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


def listening(port):
    try:
        with socket.create_connection((ORIGIN, port), timeout=0.1):
            return True
    except OSError:
        return False


def usage(pid):
    fields = pathlib.Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
    return (int(fields[11]) + int(fields[12])) / os.sysconf("SC_CLK_TCK"), int(
        fields[21]
    ) * os.sysconf("SC_PAGE_SIZE")


def measure(args, child):
    before = usage(child.pid)[0] if child else 0
    rss = []
    stop = threading.Event()

    def sample():
        while not stop.is_set():
            if child:
                rss.append(usage(child.pid)[1])
            stop.wait(0.02)

    thread = threading.Thread(target=sample)
    thread.start()
    started = time.monotonic()
    try:
        result = command(*args, capture_output=True, text=True, timeout=60)
        elapsed = time.monotonic() - started
        after = usage(child.pid)[0] if child else 0
    finally:
        stop.set()
        thread.join()
    return json.loads(result.stdout), {
        "core_cpu_seconds": after - before,
        "core_cpu_percent": (after - before) / elapsed * 100 if child else None,
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
        "engine",
        "scenario",
        "streams",
        "sample",
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
        "Medians of independent samples; raw data in samples.csv and JSON files.",
        "",
        "| Engine | Scenario | Streams | Mbit/s | Ops/s | p95 ms | UDP loss % | Core CPU % | Peak RSS MiB |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    groups = sorted({(row["engine"], row["scenario"], row["streams"]) for row in rows})
    for engine, scenario, streams in groups:
        selected = [
            row
            for row in rows
            if (row["engine"], row["scenario"], row["streams"])
            == (engine, scenario, streams)
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
            samples = [row[key] for row in selected if row.get(key) is not None]
            value = statistics.median(samples) if samples else None
            if value is not None and key == "core_peak_rss_bytes":
                value /= 1024 * 1024
            values.append(f"{value:.3f}" if value is not None else "—")
        report.append(
            f"| {engine} | {scenario} | {streams} | " + " | ".join(values) + " |"
        )
    report.extend(
        [
            "",
            "TCP forward/reverse and UDP: receiver goodput. UDP offered load is fixed across stream counts.",
            "Latency: 64-byte sequential echo; connect includes a new TCP connection plus echo.",
            "CPU/RSS cover only the core process; iperf runs include one omitted warm-up second.",
            "Direct bypasses both TUN and SOCKS5 and is a ceiling/control, not a third proxy core.",
            "VM scheduling, shared server/load CPU and load-generator limits can dominate. No Android/JNI/energy inference.",
        ]
    )
    (output / "summary.md").write_text("\n".join(report) + "\n")


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
        samples=args.samples,
        seconds=args.seconds,
        udp_mbps=args.udp_mbps,
        iperf_version=capture("iperf3", "--version"),
        sing_box_version=capture(binaries / "sing-box", "version"),
        scope="IPv4, SOCKS5, gVisor sing-box stack; strict source validation off; numeric origins, no DNS",
        load_before=pathlib.Path("/proc/loadavg").read_text().strip(),
    )
    save(output / "environment.json", metadata)
    server_config = {
        "log": {"disabled": True},
        "inbounds": [{"type": "socks", "listen": PROXY, "listen_port": 1080}],
        "outbounds": [{"type": "direct"}],
    }
    client_config = {
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
        "outbounds": [
            {
                "type": "socks",
                "tag": "proxy",
                "server": PROXY,
                "server_port": 1080,
                "version": "5",
            }
        ],
        "route": {"final": "proxy"},
    }
    save(output / "socks-server.json", server_config)
    save(output / "sing-client.json", client_config)
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
                ip("link", "set", name, "mtu", str(args.mtu), "up", client=client)
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
            cases = [
                (kind, streams)
                for kind in ("tcp_forward", "tcp_reverse", "udp", "connect")
                for streams in (1, 8)
            ] + [("tcp_latency", 1), ("udp_latency", 1)]
            for sample in range(args.samples):
                engines = ["direct", "megaproxy", "sing-box"]
                # Rotate the first engine so machine drift is not always charged to the same core.
                engines = engines[sample % 3 :] + engines[: sample % 3]
                for engine in engines:
                    with contextlib.ExitStack() as session:
                        child = None
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
                            log = output / f"{sample}-{engine}.log"
                            core = (
                                [
                                    binaries / "megaproxy-bench",
                                    "-mode",
                                    "core",
                                    "-mtu",
                                    str(args.mtu),
                                ]
                                if engine == "megaproxy"
                                else [
                                    binaries / "sing-box",
                                    "run",
                                    "-c",
                                    output / "sing-client.json",
                                ]
                            )
                            child = session.enter_context(
                                process(prefix + core, log, core_cpus)
                            )

                            def tun_ready():
                                return (
                                    subprocess.run(
                                        prefix + ["ip", "link", "show", "bench0"],
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
                        for scenario, streams in cases:
                            print(
                                f"Sample {sample + 1}/{args.samples}: {engine} {scenario} streams={streams}",
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

                            before = counters() if child else None
                            raw, resource = measure(prefix + load, child)
                            if child:
                                after = counters()
                                if any(
                                    end <= start for start, end in zip(before, after)
                                ):
                                    raise RuntimeError(
                                        "Measured traffic did not traverse TUN in both directions"
                                    )
                                raw["tun_bytes_before_after"] = [before, after]
                            metrics = (
                                iperf_metrics(raw, scenario == "udp")
                                if load[0] == "iperf3"
                                else dict(
                                    operations_per_second=raw["operations_per_second"],
                                    **dict(
                                        zip(
                                            ("p50_ms", "p95_ms", "p99_ms"),
                                            raw["latency_ms_p50_p95_p99"],
                                        )
                                    ),
                                )
                            )
                            row = dict(
                                engine=engine,
                                scenario=scenario,
                                streams=streams,
                                sample=sample + 1,
                                **metrics,
                                **resource,
                            )
                            rows.append(row)
                            save(
                                output / f"{sample}-{engine}-{scenario}-{streams}.json",
                                {"command": load, "raw": raw, "measurement": row},
                            )
                            save(output / "samples.json", rows)
                            if child and child.poll() is not None:
                                raise RuntimeError("Core exited during measurement")
                            if any(p.poll() is not None for p in (echo, server, proxy)):
                                raise RuntimeError(
                                    "Benchmark server exited during measurement"
                                )
            summary(rows, output)
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
    bench.add_argument("--udp-mbps", type=float, default=20)
    args = parser.parse_args()
    if args.action == "build":
        build(args.output.resolve())
    else:
        if not (
            1 <= args.samples <= 30
            and 1 <= args.seconds <= 30
            and 1280 <= args.mtu <= 65535
            and 0 < args.udp_mbps <= 10000
        ):
            parser.error("Invalid benchmark samples, seconds, MTU or UDP offered load")
        for tool in ("ip", "nsenter", "unshare", "iperf3"):
            if not shutil.which(tool):
                parser.error(f"Missing benchmark tool: {tool}")
        run(args)


if __name__ == "__main__":
    main()

"""MegaProxy repository commands. Run with uv run manage.py --help."""

import os
import pathlib
import re
import shutil
import subprocess
import sys

import click

ROOT = pathlib.Path(__file__).resolve().parent
NATIVE = ROOT / "native"
REPORTS = ROOT / "test-results"


def run(*args, cwd=ROOT, capture=False):
    return subprocess.run(
        args,
        cwd=cwd,
        check=True,
        text=True,
        stdout=subprocess.PIPE if capture else None,
    ).stdout


def script(name, *args):
    run(sys.executable, ROOT / "scripts" / name, *args)


def android_build(*tasks):
    run(ROOT / "scripts/build-fdroid-native.sh")
    run(ROOT / "gradlew", "--no-daemon", *tasks)


@click.group()
def cli():
    """Build, test and release MegaProxy."""


@cli.group()
def android():
    """Build and test Android APKs."""


@android.command()
def checks():
    """Run JVM tests and lint, build debug and verify the unsigned release APK."""
    for var in (
        "KEYSTORE_PATH",
        "KEYSTORE_PASSWORD",
        "KEY_ALIAS",
        "KEY_PASSWORD",
        "KEY_PASSWORD_FILE",
    ):
        if os.environ.get("MEGAPROXY_" + var):
            raise click.ClickException(
                "Release signing configuration is present: MEGAPROXY_" + var
            )
    android_build("testDebugUnitTest", "lintDebug", "assembleDebug", "assembleRelease")
    apk = ROOT / "app/build/outputs/apk/release/app-release-unsigned.apk"
    if not apk.is_file():
        raise click.ClickException("Unsigned release APK missing")
    sdk = os.environ.get("ANDROID_HOME")
    if not sdk:
        raise click.ClickException("ANDROID_HOME is required to verify the APK")
    signer = pathlib.Path(sdk) / "build-tools/36.0.0/apksigner"
    if not os.access(signer, os.X_OK):
        raise click.ClickException(f"Android apksigner is unavailable: {signer}")
    if subprocess.run([signer, "verify", apk], capture_output=True).returncode == 0:
        raise click.ClickException("CI release APK is unexpectedly signed")
    click.echo(f"Verified unsigned release APK: {apk}")


@android.command()
def debug():
    """Build the debug APK."""
    android_build("assembleDebug")


@android.command()
def device_build():
    """Build the debug and instrumentation APKs."""
    android_build(
        ":app:assembleDebug",
        ":app:assembleDebugAndroidTest",
        ":device-tests:headless:assembleDebug",
    )


@android.command()
@click.option("--api", type=click.Choice(["26", "35"]), required=True)
def device_tests(api):
    """Run device tests on a disposable emulator."""
    script("android_device_tests.py", "--api", api)


@cli.group()
def native():
    """Test and benchmark the Go dataplane."""


def go_suite(report, *args):
    report = REPORTS / "native" / report
    report.parent.mkdir(parents=True, exist_ok=True)
    run(
        "go",
        "run",
        "gotest.tools/gotestsum@v1.13.0",
        "--junitfile",
        report,
        "--",
        "-race",
        *args,
        cwd=NATIVE,
    )


@native.command(name="tests")
def native_tests():
    """Run Go race tests, including uQUIC regressions."""
    go_suite("unit.xml", "./...", "github.com/refraction-networking/uquic")


@native.command()
def integration():
    """Exercise production dialers against disposable Docker servers."""
    go_suite(
        "integration.xml",
        "-tags=integration",
        "./mobile",
        "-run=^TestRealProxyServers$",
        "-count=1",
        "-timeout=15m",
        "-v",
    )


@native.command()
def fuzz():
    """Fuzz native parsers for 20 seconds."""
    run(
        "go",
        "test",
        "./mobile",
        "-run=^$",
        "-fuzz=FuzzNativeParsers",
        "-fuzztime=20s",
        "-parallel=2",
        cwd=NATIVE,
    )


@native.command()
@click.option("--bench", default="Benchmark(Core|SOCKS5ReadAllocation)")
@click.option("--count", default=10, type=click.IntRange(min=1))
@click.option("--benchtime", default="200ms")
@click.option("--cpu", default="1")
@click.option(
    "--output",
    type=click.Path(path_type=pathlib.Path),
    default="test-results/native/benchmarks",
)
@click.option("--profile", is_flag=True)
def benchmarks(bench, count, benchtime, cpu, output, profile):
    """Collect native benchmark samples and optional CPU/heap profiles."""
    output = (ROOT / output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    for name in ("bench.txt", "cpu.pprof", "heap.pprof", "mobile.test"):
        (output / name).unlink(missing_ok=True)
    metadata = "commit: " + run("git", "rev-parse", "HEAD", capture=True)
    metadata += "working tree:\n" + run("git", "status", "--porcelain", capture=True)
    metadata += run("go", "version", cwd=NATIVE, capture=True)
    metadata += run(
        "go",
        "env",
        "-json",
        "GOOS",
        "GOARCH",
        "GOAMD64",
        "GOARM64",
        "GOFLAGS",
        "GOTOOLCHAIN",
        "CGO_ENABLED",
        cwd=NATIVE,
        capture=True,
    )
    (output / "environment.txt").write_text(metadata)
    args = [
        "go",
        "test",
        "./mobile",
        "-run=^$",
        f"-bench={bench}",
        "-benchmem",
        f"-count={count}",
        f"-benchtime={benchtime}",
        f"-cpu={cpu}",
        "-timeout=15m",
    ]
    if profile:
        args.extend(
            [
                f"-cpuprofile={output/'cpu.pprof'}",
                f"-memprofile={output/'heap.pprof'}",
                "-o",
                str(output / "mobile.test"),
            ]
        )
    (output / "command.txt").write_text("\n".join(args) + "\n")
    samples = run(*args, cwd=NATIVE, capture=True)
    click.echo(samples)
    if not re.search(r"^Benchmark\S+\s+\d+\s+", samples, flags=re.M):
        raise click.ClickException("No native benchmarks matched")
    (output / "bench.txt").write_text(samples)


@cli.group()
def python():
    """Check and format the project's Python code."""


@python.command(name="tests")
def python_tests():
    """Run the Python tests and write JUnit XML."""
    reports = REPORTS / "python"
    if reports.exists():
        shutil.rmtree(reports)
    run(
        sys.executable,
        "-m",
        "xmlrunner",
        "discover",
        "-s",
        "scripts/tests",
        "-o",
        reports,
    )


@python.command(name="format")
def python_format():
    """Apply isort and Black."""
    for tool in ("isort", "black"):
        run(sys.executable, "-m", tool, "manage.py", "scripts")


@python.command(name="checks")
@click.pass_context
def python_checks(ctx):
    """Check import order and formatting, then run unit tests."""
    run(sys.executable, "-m", "isort", "--check-only", "manage.py", "scripts")
    run(sys.executable, "-m", "black", "--check", "manage.py", "scripts")
    ctx.invoke(python_tests)


@cli.command()
@click.pass_context
def test(ctx):
    """Run native race tests and Android checks."""
    ctx.invoke(native_tests)
    ctx.invoke(checks)


@cli.command()
@click.option("--ref", default="main")
def renew_config_schema(ref):
    """Update the pinned MegaProxyConfig snapshot."""
    script("renew_config_schema.py", "--ref", ref)


@cli.command()
@click.option("--ref", default="main")
def update_graphics(ref):
    """Regenerate artwork from MegaProxyIdentity."""
    script("update_graphics.py", "--ref", ref)


@cli.group()
def release():
    """Prepare, build, scan and finalize releases."""


@release.command()
@click.option("--version", envvar="RELEASE_VERSION", required=True)
def prepare(version):
    """Generate release notes and open a release PR."""
    script("release_automation.py", "prepare", "--version", version)


@release.command()
@click.option("--version", required=True)
@click.option("--ref", default="HEAD")
@click.option("--previous")
def preview(version, ref, previous):
    """Preview notes for a Git range."""
    args = ["--previous", previous] if previous else []
    script(
        "release_automation.py", "preview", "--version", version, "--ref", ref, *args
    )


@release.command()
@click.option("--version", envvar="RELEASE_VERSION", required=True)
@click.option("--pr", envvar="RELEASE_PR", required=True)
@click.option("--head", envvar="RELEASE_HEAD", required=True)
def finish(version, pr, head):
    """Verify release CI and merge/tag the specified PR head."""
    script(
        "release_automation.py",
        "finish",
        "--version",
        version,
        "--pr",
        pr,
        "--head",
        head,
    )


@release.command()
def artifacts():
    """Build signed release APKs and checksums."""
    run(ROOT / "scripts/build-release-apks.sh")


@release.command()
@click.option("--artifacts", default="dist/release")
def virus_total(artifacts):
    """Scan signed APKs and write VirusTotal reports."""
    script("virus_total.py", "--artifacts", artifacts)


if __name__ == "__main__":
    try:
        cli()
    except subprocess.CalledProcessError as exc:
        if exc.stdout:
            click.echo(exc.stdout, err=True)
        sys.exit(exc.returncode if exc.returncode > 0 else 128 - exc.returncode)

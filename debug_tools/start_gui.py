#!/usr/bin/env python3
"""Rebuild the JAR and launch the Central Indexer GUI directly on the host.

The indexer connects to the PostgreSQL container already running via docker compose
(exposed on localhost:5432).  The Swing window is displayed natively — no X11
forwarding required.

Run configure_and_start.py first to start the database and write the .env file.
"""

import os
import sys
import shutil
import subprocess
from pathlib import Path

SCRIPT_DIR   = Path(__file__).parent.resolve()
PROJECT_ROOT = SCRIPT_DIR.parent.resolve()   # ReviewToolCentralIndexer/
REPO_ROOT    = PROJECT_ROOT.parent.resolve()  # ServerlessReviewTool/
JAR_PATH     = PROJECT_ROOT / "target" / "central-indexer.jar"

_tty = sys.stdout.isatty()

def _c(text, code):
    return f"\033[{code}m{text}\033[0m" if _tty else text

def green(t):  return _c(t, 32)
def yellow(t): return _c(t, 33)
def gray(t):   return _c(t, 90)
def red(t):    return _c(t, 31)


def resolve_java_home():
    javac = "javac.exe" if sys.platform == "win32" else "javac"

    candidate = os.environ.get("JAVA_HOME", "").strip()
    if candidate and Path(candidate, "bin", javac).exists():
        return candidate

    if sys.platform == "win32":
        try:
            import winreg
            for key_path in (r"SOFTWARE\JavaSoft\JDK", r"SOFTWARE\JavaSoft\Java Development Kit"):
                try:
                    key = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, key_path)
                    ver = winreg.QueryValueEx(key, "CurrentVersion")[0]
                    sub = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, f"{key_path}\\{ver}")
                    home = winreg.QueryValueEx(sub, "JavaHome")[0]
                    if home and Path(home, "bin", "javac.exe").exists():
                        return home
                except OSError:
                    continue
        except ImportError:
            pass

    javac_exe = shutil.which(javac)
    if javac_exe:
        java_home = Path(javac_exe).resolve().parent.parent
        if (java_home / "bin" / javac).exists():
            return str(java_home)

    return None


def load_env_file(env_file: Path) -> dict:
    """Parse KEY=VALUE lines; utf-8-sig handles Windows BOM."""
    if not env_file.exists():
        return {}
    result = {}
    for line in env_file.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        result[key.strip()] = value.strip()
    return result


def run(cmd, cwd=None):
    resolved_cmd = [shutil.which(cmd[0]) or cmd[0]] + list(cmd[1:])
    result = subprocess.run(resolved_cmd, cwd=cwd)
    if result.returncode != 0:
        print(f"Command failed (exit {result.returncode}): {' '.join(str(c) for c in cmd)}", file=sys.stderr)
        sys.exit(result.returncode)


def main():
    if not shutil.which("mvn"):
        print(red("ERROR: Maven (mvn) is not installed or not on PATH."), file=sys.stderr)
        sys.exit(1)

    java_home = resolve_java_home()
    if not java_home:
        print(red("ERROR: Could not locate a JDK. Install a JDK and set JAVA_HOME."), file=sys.stderr)
        sys.exit(1)

    # Load .env into the live process environment so all subprocesses inherit.
    env_file = SCRIPT_DIR / ".env"
    loaded = load_env_file(env_file)
    if loaded:
        os.environ.update(loaded)
        print(gray(f"Loaded: {', '.join(loaded)}"))
    else:
        print(yellow(f"No .env found at {env_file} — run configure_and_start.py first."))

    # Override DB_HOST to localhost: PostgreSQL is Docker-exposed on localhost:5432,
    # not reachable as 'postgres' (the Docker service name) from the host JVM.
    os.environ["DB_HOST"] = "localhost"
    os.environ["JAVA_HOME"] = java_home
    print(gray(f"DB_HOST=localhost  JAVA_HOME={java_home}"))
    print()

    print(yellow("Building JAR..."))
    run(
        ["mvn", "-pl", "ReviewToolCentralIndexerPluginApi,ReviewToolCentralIndexer",
         "-am", "package", "-DskipTests", "--no-transfer-progress"],
        cwd=REPO_ROOT,
    )
    print(green("JAR built."))
    print()

    if not JAR_PATH.exists():
        print(red(f"ERROR: JAR not found at {JAR_PATH}"), file=sys.stderr)
        sys.exit(1)

    java_exe = str(Path(java_home) / "bin" / ("java.exe" if sys.platform == "win32" else "java"))

    # Stop the Docker indexer so its HTTP server doesn't hold port 8765.
    # Leave postgres running — the host JVM connects to it via localhost:5432.
    docker = shutil.which("docker")
    if docker:
        result = subprocess.run([docker, "info"], capture_output=True)
        if result.returncode == 0:
            print(yellow("Stopping Docker indexer container..."))
            subprocess.run([docker, "compose", "stop", "indexer"], cwd=PROJECT_ROOT)
            print(green("Docker indexer stopped."))
            print()

    print(yellow("Launching Central Indexer GUI..."))
    print(gray(f"  {java_exe} -jar {JAR_PATH} --gui"))
    print(gray(f"  config: {PROJECT_ROOT / 'config.json'}"))
    print()

    # Run in the foreground; the terminal stays attached until the window is closed.
    subprocess.run(
        [java_exe, "-jar", str(JAR_PATH), "--gui"],
        cwd=PROJECT_ROOT,   # config.json and repositories.json are resolved from here
    )

    # Restart the Docker indexer when the GUI is closed.
    if docker:
        result = subprocess.run([docker, "info"], capture_output=True)
        if result.returncode == 0:
            print()
            print(yellow("Restarting Docker indexer..."))
            subprocess.run([docker, "compose", "start", "indexer"], cwd=PROJECT_ROOT)
            print(green("Docker indexer restarted."))


if __name__ == "__main__":
    main()

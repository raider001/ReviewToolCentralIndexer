#!/usr/bin/env python3
"""Rebuild the JAR and Docker image, then restart the indexer service in place."""

import os
import sys
import shutil
import subprocess
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent.resolve()
PROJECT_ROOT = SCRIPT_DIR.parent.resolve()   # ReviewToolCentralIndexer/
REPO_ROOT = PROJECT_ROOT.parent.resolve()    # ServerlessReviewTool/ (parent pom lives here)

_tty = sys.stdout.isatty()

def _c(text, code):
    return f"\033[{code}m{text}\033[0m" if _tty else text

def green(t):  return _c(t, 32)
def yellow(t): return _c(t, 33)


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


def run(cmd, cwd=None, env=None):
    resolved_cmd = [shutil.which(cmd[0]) or cmd[0]] + list(cmd[1:])
    result = subprocess.run(resolved_cmd, cwd=cwd, env=env)
    if result.returncode != 0:
        print(f"Command failed (exit {result.returncode}): {' '.join(str(c) for c in cmd)}", file=sys.stderr)
        sys.exit(result.returncode)


def check_docker():
    docker = shutil.which("docker")
    if not docker:
        print("ERROR: Docker is not installed or not on PATH.", file=sys.stderr)
        sys.exit(1)
    result = subprocess.run([docker, "info"], capture_output=True)
    if result.returncode != 0:
        print("ERROR: Docker daemon is not running. Please start Docker Desktop and try again.", file=sys.stderr)
        sys.exit(1)


def main():
    check_docker()

    if not shutil.which("mvn"):
        print("ERROR: Maven (mvn) is not installed or not on PATH.", file=sys.stderr)
        sys.exit(1)

    java_home = resolve_java_home()
    if not java_home:
        print("ERROR: Could not locate a JDK. Install a JDK and set JAVA_HOME.", file=sys.stderr)
        sys.exit(1)

    env = os.environ.copy()
    env["JAVA_HOME"] = java_home

    print(yellow("Building JAR..."))
    run(
        ["mvn", "-pl", "ReviewToolCentralIndexerPluginApi,ReviewToolCentralIndexer",
         "-am", "package", "-DskipTests", "--no-transfer-progress"],
        cwd=REPO_ROOT,
        env=env,
    )

    print(yellow("Building Docker image..."))
    run(["docker", "build", "--pull=false", "-t", "reviewtoolcentralindexer-indexer", str(PROJECT_ROOT)],
        cwd=PROJECT_ROOT)

    print(yellow("Restarting indexer..."))
    run(["docker", "compose", "up", "-d", "--no-deps", "--force-recreate", "indexer"],
        cwd=PROJECT_ROOT)

    print()
    print(green("Indexer restarted."))
    print("Logs: docker compose logs -f indexer")
    print()


if __name__ == "__main__":
    main()

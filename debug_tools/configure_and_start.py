#!/usr/bin/env python3
"""
Builds and starts the Central Indexer service using Docker Compose.

Prompts for any required environment variables that are not already set,
writes a .env file, then runs docker compose up --build -d.

Required variables:
  POSTGRES_PASSWORD  - Password for the PostgreSQL database.
  BEARER_TOKEN       - Bearer token clients must supply to access /events.
  GITHUB_API_TOKEN   - GitHub personal access token (used for startup reconciliation).

The webhook secret is hardcoded to "banana" in config.json.
"""

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

def cyan(t):   return _c(t, 36)
def green(t):  return _c(t, 32)
def yellow(t): return _c(t, 33)
def gray(t):   return _c(t, 90)


def read_value_or_default(env_var, prompt, default=""):
    existing = os.environ.get(env_var, "").strip()
    if existing:
        return existing
    if default:
        value = input(f"{prompt} [Enter for default: {default}]: ").strip()
        return value or default
    return input(f"{prompt}: ").strip()


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
    result = subprocess.run(cmd, cwd=cwd, env=env)
    if result.returncode != 0:
        print(f"Command failed (exit {result.returncode}): {' '.join(str(c) for c in cmd)}", file=sys.stderr)
        sys.exit(result.returncode)


def main():
    print()
    print(cyan("================================================="))
    print(cyan("  Central Indexer - GitHub Setup"))
    print(cyan("  Webhook secret: banana"))
    print(cyan("================================================="))
    print()

    if not shutil.which("docker"):
        print("ERROR: Docker is not installed or not on PATH.", file=sys.stderr)
        sys.exit(1)

    if not shutil.which("mvn"):
        print("ERROR: Maven (mvn) is not installed or not on PATH.", file=sys.stderr)
        sys.exit(1)

    pg_password     = read_value_or_default("POSTGRES_PASSWORD", "PostgreSQL password", "changeme")
    bearer_token    = read_value_or_default("BEARER_TOKEN",      "Bearer token for /events")
    github_api_token = read_value_or_default("GITHUB_API_TOKEN", "GitHub API token (leave blank to skip reconciliation)", "")
    log_level       = read_value_or_default("LOG_LEVEL",         "Log level", "debug")

    env_file = SCRIPT_DIR / ".env"
    env_file.write_text(
        f"POSTGRES_PASSWORD={pg_password}\n"
        f"BEARER_TOKEN={bearer_token}\n"
        f"GITHUB_API_TOKEN={github_api_token}\n"
        f"LOG_LEVEL={log_level}\n",
        encoding="utf-8",
    )
    print()
    print(green("Written: .env"))
    print()

    java_home = resolve_java_home()
    if not java_home:
        print("ERROR: Could not locate a JDK. Install a JDK and set JAVA_HOME.", file=sys.stderr)
        sys.exit(1)

    env = os.environ.copy()
    env["JAVA_HOME"] = java_home
    print(gray(f"Using JAVA_HOME: {java_home}"))

    print(yellow("Building JAR (this may take a minute on first run)..."))
    run(
        ["mvn", "-pl", "ReviewToolCentralIndexerPluginApi,ReviewToolCentralIndexer",
         "-am", "package", "-DskipTests", "--no-transfer-progress"],
        cwd=REPO_ROOT,
        env=env,
    )
    print()
    print(green("JAR built successfully."))
    print()

    print(yellow("Building and starting services..."))
    run(["docker", "build", "--pull=false", "-t", "reviewtoolcentralindexer-indexer", str(SCRIPT_DIR)],
        cwd=PROJECT_ROOT)
    run(["docker", "compose", "up", "-d"], cwd=PROJECT_ROOT)

    print()
    print(green("================================================="))
    print(green("  Services started successfully!"))
    print(green("================================================="))
    print()
    print("  Health check : http://localhost:8765/health")
    print("  Events API   : http://localhost:8765/events")
    print("  SSE stream   : http://localhost:8765/events/stream")
    print("  Webhook URL  : http://localhost:8765/webhooks/github")
    print("  Secret       : banana")
    print()
    print("Logs: docker compose logs -f indexer")
    print()


if __name__ == "__main__":
    main()

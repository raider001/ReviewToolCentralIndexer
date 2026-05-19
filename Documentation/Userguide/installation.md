# Installation & Startup

## Prerequisites

| Requirement | Minimum version |
|---|---|
| Java (JRE or JDK) | 21 |
| PostgreSQL | 16 |
| Maven (build only) | 3.9 |

## Building the Fat JAR

From the repository root:

```bash
mvn -pl ReviewToolCentralIndexer package -DskipTests
```

The output is `ReviewToolCentralIndexer/target/central-indexer.jar` — a self-contained JAR with all dependencies included.

## Running Standalone

1. Place `config.json` next to the JAR (or specify a custom path — see [Config File Location](#config-file-location)).
2. Start the process:

```bash
java -jar central-indexer.jar
```

The server logs the bound port on startup:

```
INFO  com.kalynx.centralindexer.Main - Central Indexer started on port 8765
```

### Config File Location

The config file is resolved in priority order — the first match wins:

| Priority | Source |
|---|---|
| 1 | Java system property `-Dcri.config=/path/to/config.json` |
| 2 | Environment variable `CRI_CONFIG=/path/to/config.json` |
| 3 | `./config.json` relative to the working directory |

Example with a system property override:

```bash
java -Dcri.config=/etc/central-indexer/config.json -jar central-indexer.jar
```

### Plugin Directory Override

The plugins directory (for external provider JARs) can also be overridden without editing `config.json`:

```bash
java -Dcri.plugins.dir=/opt/central-indexer/plugins -jar central-indexer.jar
```

---

## Docker

### Single Container

Build the image from the `ReviewToolCentralIndexer` directory:

```bash
docker build -t central-indexer .
```

Run it, bind-mounting your config file:

```bash
docker run -d \
  -p 8765:8765 \
  -v /etc/central-indexer/config.json:/app/config.json \
  --name central-indexer \
  central-indexer
```

### Docker Compose (Indexer + PostgreSQL)

A ready-made `docker-compose.yml` is included in the `ReviewToolCentralIndexer` directory.

```bash
# Set the Postgres password (required)
export POSTGRES_PASSWORD=strong_password_here

# Start both services
docker compose up -d
```

The compose file mounts:
- `./config.json` → `/app/config.json`
- `./plugins/` → `/app/plugins/` (for external plugin JARs)

Set `database.url` in `config.json` to `jdbc:postgresql://postgres:5432/indexer` when using Docker Compose (the hostname `postgres` matches the service name).

---

## Linux — systemd Service

1. Copy the JAR and config to `/opt/central-indexer/`:

```bash
sudo mkdir -p /opt/central-indexer/plugins
sudo cp target/central-indexer.jar /opt/central-indexer/
sudo cp config.json /opt/central-indexer/
```

2. Create a dedicated system user:

```bash
sudo useradd -r -s /usr/sbin/nologin central-indexer
sudo chown -R central-indexer:central-indexer /opt/central-indexer
```

3. Create the systemd unit file at `/etc/systemd/system/central-indexer.service`:

```ini
[Unit]
Description=Serverless Review Tool — Central Indexer
After=network.target postgresql.service
Wants=postgresql.service

[Service]
Type=simple
User=central-indexer
WorkingDirectory=/opt/central-indexer
ExecStart=/usr/bin/java -Dcri.config=/opt/central-indexer/config.json -jar /opt/central-indexer/central-indexer.jar
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal
SyslogIdentifier=central-indexer

[Install]
WantedBy=multi-user.target
```

4. Enable and start the service:

```bash
sudo systemctl daemon-reload
sudo systemctl enable central-indexer
sudo systemctl start central-indexer
```

5. Check status and logs:

```bash
sudo systemctl status central-indexer
sudo journalctl -u central-indexer -f
```

### Passing Secrets via Environment Variables

To avoid writing secrets directly into `config.json`, use `${ENV_VAR}` placeholders (see [Configuration Reference — Environment Variable Substitution](configuration.md#environment-variable-substitution)) and supply values through the systemd unit:

```ini
[Service]
Environment="DB_PASSWORD=strong_password_here"
Environment="WEBHOOK_SECRET=my_secret"
Environment="BEARER_TOKEN=my_token"
```

---

## Windows — Running as a Service

### Option A: NSSM (Non-Sucking Service Manager)

[Download NSSM](https://nssm.cc/download), then from an elevated PowerShell prompt:

```powershell
nssm install CentralIndexer "C:\Program Files\Java\jdk-21\bin\java.exe"
nssm set CentralIndexer AppParameters "-Dcri.config=C:\central-indexer\config.json -jar C:\central-indexer\central-indexer.jar"
nssm set CentralIndexer AppDirectory "C:\central-indexer"
nssm set CentralIndexer DisplayName "Central Indexer"
nssm set CentralIndexer Description "Serverless Review Tool — Central Indexer"
nssm set CentralIndexer Start SERVICE_AUTO_START
nssm set CentralIndexer AppStdout "C:\central-indexer\logs\stdout.log"
nssm set CentralIndexer AppStderr "C:\central-indexer\logs\stderr.log"
nssm start CentralIndexer
```

Manage the service afterwards:

```powershell
nssm status CentralIndexer
nssm restart CentralIndexer
nssm stop CentralIndexer
```

### Option B: sc.exe (Windows Service Control Manager)

Create a wrapper script `C:\central-indexer\start.bat`:

```bat
@echo off
"C:\Program Files\Java\jdk-21\bin\java.exe" ^
  -Dcri.config=C:\central-indexer\config.json ^
  -jar C:\central-indexer\central-indexer.jar
```

Then register it as a service using NSSM pointing at the batch file, or use a Java service wrapper such as [WinSW](https://github.com/winsw/winsw).

#### WinSW Example

Create `CentralIndexer.xml` next to the JAR:

```xml
<service>
  <id>CentralIndexer</id>
  <name>Central Indexer</name>
  <description>Serverless Review Tool — Central Indexer</description>
  <executable>java</executable>
  <arguments>-Dcri.config=C:\central-indexer\config.json -jar C:\central-indexer\central-indexer.jar</arguments>
  <workingdirectory>C:\central-indexer</workingdirectory>
  <log mode="roll-by-size">
    <sizeThreshold>10240</sizeThreshold>
    <keepFiles>5</keepFiles>
  </log>
</service>
```

Install and start:

```powershell
.\WinSW.exe install
.\WinSW.exe start
```

---

## Verifying the Installation

Once running, check the health endpoint from any machine that can reach the server:

```bash
curl http://<host>:8765/health
```

Expected response:

```json
{"status":"UP","db":"UP"}
```

If `db` is `"DOWN"`, verify the `database` block in `config.json` and confirm PostgreSQL is reachable.


# PortKiller
A lightweight Windows command-line tool to detect and terminate processes
occupying specified ports, driven by an external YAML configuration.

This tool is designed for **local development and operations scenarios**,
such as cleaning up leftover Java/Tomcat/JMX processes before restarting services.

---

## Features

- 🔍 Detect processes by port using `netstat`
- 🎯 Optional filtering: only `LISTENING` ports
- 🛑 Kill processes via `taskkill` (optional `/F`)
- 🧪 Dry-run mode to prevent accidental termination
- 🌍 Automatic command output charset detection (GBK / UTF-8)
- ⚙️ Configuration-driven (no hardcoded ports)

---

## Environment Requirements

- **OS**: Windows 10 / Windows 11  
- **JDK**: Java 8 or higher  
- **Permissions**:  
  - Querying ports works for normal users  
  - Killing processes may require **Administrator** privileges  

---

## Project Structure

port-killer
├─ src
│ └─ main
│ ├─ java
│ │ └─ org.example
│ │ ├─ PortKiller.java
│ │ └─ ConfigLoader.java
│ └─ resources
│ └─ application.yml
├─ pom.xml
└─ README.md

yaml
Copy code

---

## Configuration

All behavior is controlled via `application.yml`:

yaml
portKiller:
  ports:
    - 8080
    - 8081
    - 1099
    - 1100
  onlyListening: true
  forceKill: true
  dryRun: true
Configuration Fields
Field	Type	Description
ports	list	Ports to check and process
onlyListening	boolean	Only handle ports in LISTENING state
forceKill	boolean	Use taskkill /F
dryRun	boolean	If true, do not actually kill processes

⚠️ Strongly recommended to run with dryRun: true first.

Usage
Run from IDE
Run PortKiller.main() directly in IntelliJ IDEA.

Run from command line
After packaging:

bash
Copy code
mvn clean package
java -jar target/port-killer.jar
Example Output
text
Copy code
[HIT] Port 8080 used by PIDs: [12345]
  PID=12345 PROC=java.exe
  [DRY-RUN] Would kill PID=12345
Charset Handling (Important)
This tool automatically detects the active Windows code page:

chcp 936 → GBK

chcp 65001 → UTF-8

This prevents garbled output on different Windows language or locale settings.

Safety Notes
This tool terminates processes by PID.

Misconfigured ports may kill unintended services.

Always verify:

Port list

Process name

dryRun output

Do not use blindly on production systems.

License
Internal use / personal use.

No warranty. Use at your own risk.

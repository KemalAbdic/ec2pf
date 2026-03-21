# ec2pf

[![Build](https://github.com/KemalAbdic/ec2pf/actions/workflows/build.yml/badge.svg)](https://github.com/KemalAbdic/ec2pf/actions/workflows/build.yml)
[![codecov](https://codecov.io/gh/KemalAbdic/ec2pf/graph/badge.svg)](https://codecov.io/gh/KemalAbdic/ec2pf)
[![Release](https://img.shields.io/github/v/release/KemalAbdic/ec2pf)](https://github.com/KemalAbdic/ec2pf/releases/latest)

[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://openjdk.org/projects/jdk/17/)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.32-4695EB.svg?logo=quarkus)](https://quarkus.io/)
[![Gradle](https://img.shields.io/badge/Gradle-9.4-02303A.svg?logo=gradle)](https://gradle.org/)
[![AWS](https://img.shields.io/badge/AWS-SSM-FF9900.svg?logo=amazonwebservices)](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager.html)
[![JSpecify](https://img.shields.io/badge/JSpecify-1.0-6A9955.svg)](https://jspecify.dev/)

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

CLI tool for managing AWS SSM port-forwarding sessions to EC2 instances. Define your AWS settings and services in an INI
config file, then start, stop, and monitor `aws ssm start-session` processes with automatic reconnection.

## Prerequisites

- [AWS CLI v2](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)
- [Session Manager plugin](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html)
- AWS credentials configured (`aws configure` or environment variables)
- Java 17+ (only required for the uber-jar)

## Installation

### Homebrew (macOS / Linux)

```bash
brew tap KemalAbdic/tap
brew install ec2pf
```

### Chocolatey (Windows)

```powershell
choco install ec2pf
```

### Scoop (Windows)

```powershell
scoop bucket add kemalabdic https://github.com/KemalAbdic/scoop-bucket
scoop install ec2pf
```

### Native binary (manual download)

Download a pre-built native binary from the [latest release](https://github.com/KemalAbdic/ec2pf/releases/latest):

| Platform       | File                                |
|----------------|-------------------------------------|
| Linux x86_64   | `ec2pf-<version>-linux-amd64`       |
| Linux ARM64    | `ec2pf-<version>-linux-arm64`       |
| macOS x86_64   | `ec2pf-<version>-darwin-amd64`      |
| macOS ARM64    | `ec2pf-<version>-darwin-arm64`      |
| Windows x86_64 | `ec2pf-<version>-windows-amd64.exe` |

On macOS/Linux, make it executable after downloading:

```bash
chmod +x ec2pf-*
./ec2pf-<version>-<platform>-<arch> start -c config.ini
```

### Uber-JAR (requires Java 17+)

Grab the uber-jar from the [latest release](https://github.com/KemalAbdic/ec2pf/releases/latest) - it's a single
self-contained JAR with all dependencies bundled:

```bash
java -jar ec2pf-<version>-runner.jar start -c config.ini
```

### Build from source

```bash
./gradlew build -Dquarkus.package.jar.type=uber-jar
java -jar build/ec2pf-<version>-runner.jar start -c config.ini
```

<details>
<summary>Other build options</summary>

#### Fast-jar (default)

The default `./gradlew build` produces a fast-jar at `build/quarkus-app/quarkus-run.jar`. To run it you need the
entire `build/quarkus-app/` directory (it contains `lib/`, `app/`, etc.):

```bash
java -jar build/quarkus-app/quarkus-run.jar start -c config.ini
```

#### Native executable

Requires GraalVM or a container runtime:

```bash
./gradlew build -Dquarkus.native.enabled=true
```

</details>

## Usage

### `start` - Start port-forwarding sessions

```bash
ec2pf start -c config.ini
ec2pf start -c config.ini --dry-run
ec2pf start -c config.ini --watch 15
ec2pf start -c config.ini --no-watch
```

| Option              | Description                                            |
|---------------------|--------------------------------------------------------|
| `-c, --config`      | Path to INI config file (required)                     |
| `--dry-run`         | Show what would be done without connecting             |
| `--watch [seconds]` | Enable watch mode with polling interval (default: 30s) |
| `--no-watch`        | Disable watch mode                                     |

### `stop` - Stop port-forwarding sessions

```bash
ec2pf stop -c config.ini
ec2pf stop --all
ec2pf stop --all --dry-run
```

| Option         | Description                              |
|----------------|------------------------------------------|
| `-c, --config` | Stop sessions for a specific config file |
| `--all`        | Stop all active sessions                 |
| `--dry-run`    | Show what would be stopped               |

### `status` - Show active sessions

```bash
ec2pf status -c config.ini
ec2pf status --all
```

| Option         | Description                              |
|----------------|------------------------------------------|
| `-c, --config` | Show sessions for a specific config file |
| `--all`        | Show all active sessions                 |

### `validate` - Validate a config file

```bash
ec2pf validate -c config.ini
```

## Config File Format

INI file with an `[aws]` section for AWS settings and a `[services]` section listing EC2 instances to forward:

```ini
[aws]
region = eu-west-1
profile = my-profile
remote_port = 8080

[services]
# name = local_port, skip [, remote_port_override]
my-service = 7001, false
other-service = 7002, true
custom-port = 7003, false, 9090
```

- **region** - AWS region
- **profile** - AWS CLI named profile
- **remote_port** - Default remote port for all services
- Services with `skip = true` are ignored during `start`
- Optional third value overrides the default remote port per service

## Configuration Properties

Configurable via `application.properties` or system properties:

| Property                                  | Default       | Description                                    |
|-------------------------------------------|---------------|------------------------------------------------|
| `ec2pf.watch.max-backoff-secs`            | 60            | Max backoff delay for watch mode reconnection  |
| `ec2pf.watch.default-interval-secs`       | 30            | Default watch mode polling interval            |
| `ec2pf.watch.min-interval-secs`           | 5             | Minimum allowed watch interval                 |
| `ec2pf.watch.port-release-attempts`       | 10            | Retries when waiting for a port to free up     |
| `ec2pf.watch.port-release-interval-ms`    | 500           | Delay between port release checks (ms)         |
| `ec2pf.session.startup-attempts`          | 10            | Number of attempts to verify session startup   |
| `ec2pf.session.startup-check-interval-ms` | 1000          | Delay between startup verification checks (ms) |
| `ec2pf.session.cli-timeout-secs`          | 30            | Timeout for AWS CLI subprocess calls           |
| `ec2pf.pid.directory`                     | `${user.dir}` | Directory for PID files                        |

## Limitations

- Concurrent use of the same config file from multiple processes is not supported. PID files are not locked, so running
  `ec2pf start -c config.ini` simultaneously from two terminals may cause session tracking corruption.

## Development

```bash
./gradlew build                # Build + all checks + tests
./gradlew test                 # Run tests only
./gradlew quarkusDev           # Dev mode (press Enter to restart)
./gradlew quarkusDev --quarkus-args='start -c config.ini'
```

Run a single test:

```bash
./gradlew test --tests "com.kemalabdic.config.parser.IniConfigParserTest"
```

### Code Quality

```bash
./gradlew checkstyleMain checkstyleTest   # Checkstyle (10.21.1)
./gradlew pmdMain pmdTest                 # PMD (7.22.0)
```

Checkstyle and PMD run automatically as part of `./gradlew build`.

## License

This project is licensed under [GPL v3](LICENSE).

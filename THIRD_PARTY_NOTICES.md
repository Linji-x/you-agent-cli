# Third-party notices

You Agent CLI is MIT-licensed. It uses the following libraries under their own
licenses. This summary is provided for convenience; the linked license text is
authoritative.

## Runtime dependencies

| Component | Version | License |
|---|---:|---|
| Jackson core, annotations, databind, jsr310 | 2.18.9 | Apache License 2.0 |
| OkHttp | 4.12.0 | Apache License 2.0 |
| Okio | Maven-resolved transitive version | Apache License 2.0 |
| Kotlin standard library (OkHttp transitive dependency) | Maven-resolved transitive version | Apache License 2.0 |
| JavaParser Core | 3.26.2 | Apache License 2.0 or LGPL 3.0; this project uses it under Apache 2.0 |
| Xerial SQLite JDBC | 3.46.1.0 | Apache License 2.0 |
| SLF4J API/NOP | 1.7.36 | MIT License |

## Test-only dependencies

| Component | Version | License |
|---|---:|---|
| JUnit Jupiter | 5.11.0 | Eclipse Public License 2.0 |
| MockWebServer | 4.12.0 | Apache License 2.0 |

## Build and quality tooling

| Component | Version | License |
|---|---:|---|
| Apache Maven | 3.9.11 bootstrap | Apache License 2.0 |
| Maven Compiler Plugin | 3.13.0 | Apache License 2.0 |
| Maven Surefire Plugin | 3.5.0 | Apache License 2.0 |
| Maven Shade Plugin | 3.6.0 | Apache License 2.0 |
| JaCoCo Maven Plugin | 0.8.12 | Eclipse Public License 2.0 |

License texts and project information:

- Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0
- Eclipse Public License 2.0: https://www.eclipse.org/legal/epl-v20.html
- MIT License: https://opensource.org/license/mit
- GNU LGPL 3.0: https://www.gnu.org/licenses/lgpl-3.0.html

Maven resolves exact transitive versions from `pom.xml`; run
`mvn dependency:tree` to inspect the dependency graph used for a build.

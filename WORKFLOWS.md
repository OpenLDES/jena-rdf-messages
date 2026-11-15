## Builds and releases

The GitHub Actions workflows follow the same stages as the other OpenLDES repositories
(Linked-Data-Interactions and LDESServer):

| Workflow                           | Purpose                                                                                                                                                                   |
|------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1.a. Build & Test Project          | Verify pull requests with Maven (`mvn verify -Pcoverage`), optionally analyze with SonarCloud, and upload the JARs, the JaCoCo reports and the RDF Messages conformance report. |
| 2.a. Build & Deploy Project        | Verify pushes to `main` and `develop`, and optionally publish Maven packages to Maven Central. Can also be run manually.                                                   |
| 3. Approve Snapshot                | Open a pull request against `main` that removes the `-SNAPSHOT` suffix, manually from `main` or when a release targeting `main` is published.                             |
| 4. Prepare New Version for Project | Prepare a patch, minor, or major version bump on `main` or `develop`, using the release SSH key.                                                                          |
| 5. Create Snapshot Version         | Manually bump the major, minor, or patch version, append `-SNAPSHOT`, and commit the updated Maven POMs directly to the selected `main` or `develop` branch.              |

`mvn verify` runs the unit tests (`*Test`), the integration tests (`*IT`, which start local HTTP servers) and the
vendored [RDF Messages test suite](parsers/src/test/resources/rdf-messages-tests/README.md). The conformance run writes
an EARL report (`parsers/target/rdf-messages-earl-report.ttl`) and a Markdown summary
(`parsers/target/rdf-messages-conformance.md`), which the workflows upload as artifacts.

The workflows use the `JAVA_DISTRIBUTION` and `JAVA_VERSION` variables, with `temurin` and `21` as defaults.
`ANALYSE_PACKAGE` and `DEPLOY_PACKAGE_TO_OSSRH` enable optional SonarCloud analysis and Maven
publication; both default to disabled when unset.
Maven publication configures the `central` and `snapshots` server IDs with Central Portal user-token
credentials. Ordinary verification does not sign artifacts; signing runs only with the `release`
profile during publication, using `MAVEN_GPG_PASSPHRASE` for non-interactive signing.
Snapshot promotion requires the repository setting that allows GitHub Actions to create pull
requests, and uses `GIT_CONFIG_USERNAME` and `GIT_CONFIG_EMAIL` for its commit.
Version preparation uses `SSH_PRIVATE_KEY`.

Snapshot creation uses `GIT_CONFIG_USERNAME` and `GIT_CONFIG_EMAIL` for its commit and
`GITHUB_TOKEN` with contents write permission to push. Branch protection must allow this push.
The selected component is incremented even if the current version already ends in `-SNAPSHOT`;
major and minor bumps reset the lower components to zero. Commits pushed with `GITHUB_TOKEN` do not
trigger the push-based build workflow.

Dependabot opens pull requests for Maven dependencies (daily) and GitHub Actions (weekly).

### GitHub Actions variables and secrets

Configure these under **Settings > Secrets and variables > Actions** (or inherit them from the
organization). "Required" refers to the workflows or features listed in that row.

| Name                            | Type             | Required                                            | Default when unset               | Explanation                                                                                                                                      |
|---------------------------------|------------------|-----------------------------------------------------|----------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| `JAVA_DISTRIBUTION`             | Variable         | No                                                  | `temurin`                        | JDK distribution used by `actions/setup-java`.                                                                                                   |
| `JAVA_VERSION`                  | Variable         | No                                                  | `21`                             | JDK version used by `actions/setup-java`; `21` matches this project's Maven configuration.                                                       |
| `ANALYSE_PACKAGE`               | Variable         | No                                                  | `false`                          | Set to `true` to enable SonarCloud analysis in workflows 1.a and 2.a. Fork and Dependabot pull requests skip analysis. Use a JSON boolean.      |
| `SONAR_ORGANIZATION`            | Variable         | When `ANALYSE_PACKAGE=true`                         | None                             | SonarCloud organization key passed to the Maven scanner.                                                                                         |
| `SONAR_PROJECT_KEY`             | Variable         | When `ANALYSE_PACKAGE=true`                         | None                             | SonarCloud project key identifying the analysis target.                                                                                          |
| `DEPLOY_PACKAGE_TO_OSSRH`       | Variable         | No                                                  | `false`                          | Set to `true` to sign and publish Maven artifacts in workflow 2.a using the `release` profile. Use a JSON boolean.                               |
| `GIT_CONFIG_USERNAME`           | Variable         | For snapshot promotion and snapshot creation        | None                             | Git committer name used by workflows 3 and 5. Workflow 4 uses its own fixed release-bot identity.                                                |
| `GIT_CONFIG_EMAIL`              | Variable         | For snapshot promotion and snapshot creation        | None                             | Git committer email used by workflows 3 and 5.                                                                                                   |
| `SONAR_TOKEN`                   | Secret           | When `ANALYSE_PACKAGE=true`                         | None                             | SonarCloud token authorized to analyze the configured project.                                                                                   |
| `OSSRH_USERNAME`                | Secret           | When `DEPLOY_PACKAGE_TO_OSSRH=true`                 | None                             | Central Portal user-token username, exposed as `MAVEN_USERNAME` for both Maven publishing servers.                                               |
| `OSSRH_TOKEN`                   | Secret           | When `DEPLOY_PACKAGE_TO_OSSRH=true`                 | None                             | Central Portal user-token password, exposed as `MAVEN_PASSWORD` for both Maven publishing servers.                                               |
| `OSSRH_GPG_SECRET_KEY`          | Secret           | When `DEPLOY_PACKAGE_TO_OSSRH=true`                 | None                             | ASCII-armored GPG private key imported by `actions/setup-java` to sign Maven artifacts.                                                          |
| `OSSRH_GPG_SECRET_KEY_PASSWORD` | Secret           | When Maven signing uses a passphrase-protected key  | None                             | Passphrase for the GPG private key, exposed as `MAVEN_GPG_PASSPHRASE` during publication.                                                        |
| `SSH_PRIVATE_KEY`               | Secret           | For version preparation in workflow 4               | None                             | SSH private key with repository write access for the Maven release action. Its public key must be authorized on GitHub, e.g. as a deploy key.   |
| `GITHUB_TOKEN`                  | Automatic secret | No manual configuration                             | Generated by GitHub for each job | Used for checkout, SonarCloud integration, snapshot promotion and snapshot creation. Permissions are controlled by each workflow/job.            |

`MAVEN_USERNAME`, `MAVEN_PASSWORD` and `MAVEN_GPG_PASSPHRASE` are environment variables populated by the
workflows from the secrets above; do not create separate GitHub variables or secrets for them.

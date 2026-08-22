# Security policy

## Supported version

Security fixes target the latest `main` branch.

## Reporting

Do not open a public issue containing credentials, private source code, or
internal data. Use GitHub's private vulnerability reporting feature when it is
available for this repository.

Before reporting, remove API keys, access tokens, cookies, private URLs, local
usernames, and proprietary file contents from logs and screenshots.

## Security boundaries

You Agent CLI applies a workspace path guard, basic command deny rules, command
timeouts, structured tool validation, and bounded outputs. These controls reduce
accidental damage; they are not a container or virtual-machine sandbox. Run the
agent with a least-privileged OS account and review changes before committing.

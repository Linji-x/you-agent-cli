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

MCP servers and model providers are separate trust boundaries. Environment placeholders
prevent credentials from being committed, but a configured remote server can still receive
the arguments intentionally sent to its tools. Review each server configuration and use
least-privileged tokens.

The command tool starts processes in the workspace and does not invoke a shell string. This
does not prevent those processes from reading other files or using the network when the OS
account permits it. Use a container or VM when executing untrusted tasks.

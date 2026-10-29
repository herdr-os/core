# @herdr-os/bridge

The bridge between a Rabbit R1 running [Herdr OS](https://herdr.dev) and the coding agents
[Herdr](https://herdr.dev) runs on your machines. It watches the herd, reads each agent's own
session files (Claude Code, Codex, Pi, OpenCode) for its model, context, subagents and background
commands, and gives the R1 a small authenticated API: answer questions, send messages and photos,
switch models, read the terminal.

Node 24 or newer. No dependencies.

```sh
npm install -g @herdr-os/bridge

herdr-bridge serve --tailscale                    # this machine's Herdr, on your tailnet
herdr-bridge serve --tailscale --machine namek    # plus every Herdr machine you've added
herdr-bridge enroll --tailscale --qr              # a one-time code; scan it on the R1
```

- `serve --fixtures` serves a demo herd, no Herdr needed.
- `devices` lists paired R1s; `revoke <device_id>` cuts one off.
- Data lives in `~/.config/herdr-bridge` (`--data` to move it).

It binds to loopback unless you give it TLS or `--tailscale` / `--private-network`, so the API is
never on an open network by accident. Remote machines are read over the same SSH Herdr uses.

# AllPurpose

Ranks-only Paper plugin (1.20 API): rank ladder, tags, LuckPerms sync, leaderboard, Discord relay, self-updater.

## Features

- **Rank ladder** (`/rank`): ladder view, create/delete/rename ranks, give/remove/upgrade, setprefix/color, recalc, staff overlay tags, freeze/unfreeze (`recalc-proof`).
- **Auto-rankup** on lifetime kills; `auto-rankup`, `rank-ladder`, `rankup-kills` in config (currently ladder is `[MEMBER]` with no kill thresholds set).
- **LuckPerms mirror**: hushrank → LP parent (`lp-sync-groups`, `lp-sync-minimums` so higher ranks keep lower kits); `/rank lpsync`; temp-group expiry notifier; `parent set/clear` guard (warn-only, never blocks).
- **Leaderboard** (`/lb`): kills/deaths/playtime, `/lb holo` hologram (killtop + Essentials baltop), `/lb removeholo`.
- **Nametag**: TAB / hologram / vanilla modes, `{rank} {prefix} {hearts} {hp} {kills} {deaths} {ping}` placeholders, ping colour bands.
- **Chat**: per-player anti-spam cooldown (OP/`rank.admin` bypass); bot command-log grouping (`group|hide|off`).
- **Discord**: webhook relay (joins/chat/deaths/duels/ranks/plugin); live status-board bot (edits one message, OFFLINE flip on shutdown); public chat-log bot; spawn snapshot renderer; invite auto-announce.
- **RCON spam filter**: hides RCON connect/poll noise, log-only, RCON keeps working.
- **Self-updater** (`/allpurpose update check|download|status`): GitHub Releases source of truth, staged download, swap on shutdown only (never hot-swaps the running jar), old jar backed up, SHA256-checked vs `checksums.txt`.
- **Linked-plugin API**: `AllPurposeAPI` (`src/com/hush/allpurpose/AllPurposeAPI.java`) is the read-only facade (rank reads, ladder, `lbKills/lbDeaths`). Use it, not `getRanks()` mutators.
- **Misc**: survival-mode flag, linked survival transfer (`/survival`), ping advancement, `allow-external-mutations: false` default.

## Limitations

- **Compile is currently broken** on the untracked WIP `DiscordChatLog.java` (`queueEmbed` errors at last check); official jar is built from a clean tree via `.github/workflows/release.yml`, not this working tree.
- **External-mutation block is a guardrail, not a sandbox**: `staffSet/setFrozen/lpSync` from outside `com.hush.allpurpose` are blocked + logged by default, but any plugin on the same server already has full Bukkit API (console dispatch, LP commands, file access), and a fork can delete the check. Real protection = run official builds.
- **Signing: wired, key not yet installed**: releases carry `checksums.txt` (SHA256); the workflow also publishes `.asc` detached signatures once a `GPG_PRIVATE_KEY` repo secret exists (see `release.yml`). Export the public key as `OFFICIAL_RELEASE_KEY.asc` when generated.
- **Cleaned for public review**: `google.key`, root live dumps/logs, `mcp/` creds, `deploytools/`, and `TODO.md` ops notes are untracked/deleted from the working tree. **Git history still contains old secrets** (RCON/SFTP passwords, live dumps), so either rewrite history or rotate those credentials before flipping the repo public.
- **Ops discipline required** (per TODO): never `/reload` a swapped jar (classloader poison), restarts owner-only, exactly one `AllPurpose.jar` in `plugins/`, one writer at a time.
- Needs softdepends for full function: TAB, PlaceholderAPI, LuckPerms, SkinsRestorer (+ Essentials for baltop).

## License

Source-available, review-only (see `LICENSE`): staff may read and privately test-build to verify official releases. Without written permission: no publishing/distributing modified jars, no forks posing as AllPurpose or its updates, no companion plugins built to bypass/escalate AllPurpose behavior. Official builds = GitHub Releases with matching `checksums.txt`. AS-IS, no warranty.

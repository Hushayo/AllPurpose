# AllPurpose

## Features

- **Rank ladder** (`/rank`): ladder view, create/delete/rename ranks, give/remove/upgrade, setprefix/color, recalc, staff overlay tags, freeze/unfreeze (`recalc-proof`).
- **Auto-rankup** on lifetime kills (`auto-rankup`, `rank-ladder`, `rankup-kills`).
- **LuckPerms mirror**: hushrank → LP parent (`lp-sync-groups`, `lp-sync-minimums` so higher ranks keep lower kits), `/rank lpsync` backfill, temp-group expiry notifier with optional join whisper, and a warn-only `parent set/clear` guard for the LP command that silently strips `default`.
- **Leaderboards** (`/lb`): kills/deaths/playtime plus Essentials baltop, paged chat board, and billboard-fixed holograms (`/lb holo`, `/lb removeholo`).
- **Nametags**: TAB / hologram / both / off modes with `{rank} {prefix} {name} {hearts} {hp} {maxhp} {abs} {total} {kills} {deaths} {ping}` placeholders and ping colour bands.
- **Chat moderation**: per-player anti-spam cooldown (OP / `rank.admin` bypass) and bot command-log grouping (`group|hide|off`) so bot `/...` command spam collapses into one summary line per window.
- **Coinflip** (`/coinflip`, alias `/cf`): GUI lobby of open wagers, escrow through Vault/EssentialsX on creation, 50/50 flip, winner takes the pot, wager clamps, house tax, and expiry refunds.
- **Bounties** (`/bounty`): board of hunted players, money added per contributor (persisted in `bounties.yml`), killer collects the whole escrowed pot, house tax, admin `clear` refunding every backer.
- **Chest shops**: sneak + right-click a chest/barrel/shulker to create a SELL or BUY shop, `/shop` browser, confirm screen on trade, house tax, per-player shop limit (`allpurpose.shop.limit.<n>`), explosion/piston/hopper protection, and on-demand floating TextDisplay labels that only exist near players.
- **Community fund / wealth tax** (`/tax`, alias `/wealthtax`): progressive marginal brackets, one random daily audit with a rolled per-event cap, virtual ledger treasury with earmarked buckets (insurance / starter-grant / prize), `/tax pay|prize|spend`, and audit trails (`/tax ledger|spends|grants|draws`). Ships disabled.
- **Starter grant**: one-time treasury-funded welcome payout out of the starter-grant bucket once a new player reaches a playtime threshold. Ships disabled.
- **Weekly prize draw**: one active, non-bot player drawn per week and paid out of the prize bucket. Ships disabled.
- **Text holograms**: `/setholotext` (alias `/holotext`, `/ht`) free-form floating text — fixed billboard, yaw baked at placement, per-hologram style keys (background, line width, opacity, shadow, glow, view range, scale), line editing, move/rotate/tilt, and crash-orphan sweep on boot.
- **Changelog dialogs** (`/changelog`): server changelog rendered as Minecraft dialogs (1.21.6+), viewable in the pause menu via a generated datapack and instantly via `/changelog show`. All text is in-game editable; edits require `rank.admin`.
- **Discord integration**: webhook relay (joins / chat / deaths / duels / ranks / plugin), a live status-board bot that edits one message and flips it to OFFLINE on shutdown, a public chat-log bot using speaker head emoji, a spawn snapshot renderer, an activity-gated invite auto-announce, and a stale-message guard for the spectator live-cam.
- **Spectator live-cam**: screenshot pipeline on a spare machine, with the Discord message deleted when it goes stale.
- **Command guard**: cancels `/effect`, `/summon`, `/tick rate` and `/enchant` for anyone without `allpurpose.commandguard.bypass` — including opped players, whom LuckPerms alone cannot stop.
- **RCON spam filter**: hides RCON connect/poll log noise and the Essentials `/list` polling echo, log-only, RCON keeps working.
- **Auto-login** for specific accounts via the LoginSecurity API, so a restart never leaves a bot account stuck at the login prompt.
- **Linked survival server**: `/survival` transfer to the configured host/port, plus the `survival-mode` flag.
- **Ping advancement**: "Time Traveler" advancement awarded when a player's ping crosses a configurable threshold.
- **Kill effects**: master switch plus optional per-rank effect config (`killfx`), ships off.
- **Self-updater** (`/allpurpose update check|download|status`): GitHub Releases as source of truth, staged download, swap on shutdown only (never hot-swaps the running jar), old jar backed up, SHA256-checked against `checksums.txt`.
- **Linked-plugin API**: `AllPurposeAPI` (`src/com/hush/allpurpose/AllPurposeAPI.java`) is the read-only facade (rank reads, ladder, `lbKills`/`lbDeaths`). Privileged `RankManager` calls from outside `com.hush.allpurpose` are blocked and logged unless `allow-external-mutations: true`.

## License

Source-available, review-only (see `LICENSE`): staff may read the source and privately test-build against official releases. Without written permission: no publishing or distributing modified jars built from this source, no forks presenting themselves as AllPurpose or an update to it, and no companion/linked plugins whose purpose is to bypass, escalate, or misuse AllPurpose rank/permission behavior. Official builds are only the GitHub Releases attached to this repo with matching `checksums.txt`. AS-IS, no warranty.

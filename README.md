# AllPurpose

<p align="center">
  <strong>The custom server plugin behind the <strong>Mcpvp</strong> survival-PvP network.</strong><br>
  Ranks and identity, in-game economy, holograms and Discord tooling — one plugin, one config.
</p>

<p align="center">
  <a href="https://github.com/Hushayo/AllPurpose/releases"><img src="https://img.shields.io/badge/release-2.1.0-royalblue?style=flat-square" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Minecraft-1.20%2B-green?style=flat-square" alt="Minecraft 1.20+">
  <img src="https://img.shields.io/badge/Paper-21-orange?style=flat-square" alt="Paper">
  <img src="https://img.shields.io/badge/Java-21-blue?style=flat-square" alt="Java 21">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-source--available%2C%20review--only-lightgrey?style=flat-square" alt="License"></a>
</p>

## Description

**AllPurpose** is the plugin that runs the Mcpvp server's day-to-day systems. It started out as a rank ladder and grew into the single home for the server's ranks, nametags, leaderboards, money, in-game content and Discord presence — one jar, one config file, no cross-plugin wiring for staff to babysit.

The plugin is written for Paper and leans on the ecosystem instead of reinventing it: **LuckPerms** for permissions and rank groups, **Vault** (with an EssentialsX economy behind it) for money, **TAB** for the tab list, **PlaceholderAPI** for placeholder expansion and **LoginSecurity** for the bot accounts. Every one of them is a soft dependency — a feature whose dependency is missing simply stays quiet instead of breaking the server.

Three rules run through the whole codebase:

- **Editable in game.** Staff-facing text — changelog lines, hologram text, rank prefixes, Discord messages — is edited from the game or from YAML, never baked into the jar.
- **Inert until enabled.** Every system has its own `enabled` flag, so a fresh install does nothing until someone deliberately turns it on.
- **Reversible.** Anything that takes power away from a player (a rank, a group) can be handed back with the same command that took it.

## Features

### 🏆 Ranks &amp; identity

<table>
<tr>
<td width="50%" valign="top">

<strong>🏆 Rank ladder</strong><br>
<code>/rank</code> — ladder view, create / delete / rename ranks, give / remove / upgrade, set prefix and colour, recalc, staff overlay tags, freeze and unfreeze.

</td>
<td width="50%" valign="top">

<strong>🎯 Auto-rankup</strong><br>
Ranks players up automatically on lifetime kills (<code>auto-rankup</code>, <code>rank-ladder</code>, <code>rankup-kills</code>).

</td>
</tr>
<tr>
<td width="50%" valign="top">

<strong>🔗 LuckPerms mirror</strong><br>
hushrank → LP parent (<code>lp-sync-groups</code>, <code>lp-sync-minimums</code> so higher ranks keep lower kits), <code>/rank lpsync</code> backfill, temp-group expiry notifier with an optional join whisper, and a warn-only guard for the LP <code>parent set/clear</code> command that silently strips <code>default</code>.

</td>
<td width="50%" valign="top">

<strong>🌈 Rank animation</strong><br>
Opt-in ranks whose prefix uses <code>&lt;gradient:...&gt;</code> get a smoothly hue-rotated gradient in the tab list (<code>rank-animation</code>: <code>enabled</code>, <code>interval-ticks</code>, <code>cycle-seconds</code>, <code>ranks</code>). The cycle loops seamlessly — chat messages stay a static snapshot.

</td>
</tr>
<tr>
<td width="50%" valign="top">

<strong>👤 Nametags</strong><br>
TAB / hologram / both / off modes with <code>{rank} {prefix} {name} {hearts} {hp} {maxhp} {abs} {total} {kills} {deaths} {ping}</code> placeholders and ping colour bands.

</td>
<td width="50%" valign="top">

<strong>📊 Leaderboards</strong><br>
<code>/lb</code> — kills / deaths / playtime plus the Essentials baltop, a paged chat board and billboard-fixed holograms (<code>/lb holo</code>, <code>/lb removeholo</code>).

</td>
</tr>
</table>

### 💰 Economy

<table>
<tr>
<td width="50%" valign="top">

<strong>🎰 Coinflip</strong><br>
<code>/coinflip</code> (alias <code>/cf</code>) — GUI lobby of open wagers, escrow through Vault on creation, 50/50 flip, winner takes the pot, wager clamps, house tax and expiry refunds.

</td>
<td width="50%" valign="top">

<strong>🎯 Bounties</strong><br>
<code>/bounty</code> — put money on a player's head (persisted in <code>bounties.yml</code>), the killer collects the whole escrowed pot, house tax, and an admin <code>clear</code> that refunds every backer.

</td>
</tr>
<tr>
<td width="50%" valign="top">

<strong>🧱 Chest shops</strong><br>
Sneak + right-click a chest / barrel / shulker to create a SELL or BUY shop, right-click a shop to trade (confirm screen, amount stepper, optional custom price), house tax, per-player shop limit (<code>allpurpose.shop.limit.&lt;n&gt;</code>), explosion / piston / hopper protection and floating TextDisplay labels that only exist near players.

</td>
<td width="50%" valign="top">

<strong>🏦 Community fund / wealth tax</strong><br>
<code>/tax</code> (alias <code>/wealthtax</code>) — progressive marginal brackets, one random daily audit with a rolled per-event cap, a virtual ledger treasury with earmarked buckets (insurance / starter-grant / prize), <code>/tax pay|prize|spend</code> and audit trails (<code>/tax ledger|spends|grants|draws</code>). Ships disabled.

</td>
</tr>
<tr>
<td width="50%" valign="top">

<strong>🎁 Starter grant</strong><br>
One-time, treasury-funded welcome payout out of the starter-grant bucket once a new player reaches a playtime threshold. Ships disabled.

</td>
<td width="50%" valign="top">

<strong>🏆 Weekly prize draw</strong><br>
One active, non-bot player drawn per week and paid out of the prize bucket. Ships disabled.

</td>
</tr>
</table>

### 🛠️ Content &amp; chat

<table>
<tr>
<td width="50%" valign="top">

<strong>🔤 Text holograms</strong><br>
<code>/setholotext</code> (aliases <code>/holotext</code>, <code>/ht</code>) — free-form floating text with a fixed billboard, yaw baked at placement, per-hologram style keys (background, line width, opacity, shadow, glow, view range, scale), line editing, move / rotate / tilt, <code>/setholotext select &lt;id&gt; [360|none]</code> and a crash-orphan sweep on boot.

</td>
<td width="50%" valign="top">

<strong>📜 Changelog dialogs</strong><br>
<code>/changelog</code> — the server changelog rendered as Minecraft dialogs (1.21.6+), viewable in the pause menu through a generated datapack and instantly via <code>/changelog show</code>. All text is in-game editable; edits require <code>rank.admin</code>.

</td>
</tr>
<tr>
<td width="50%" valign="top">

<strong>💬 Chat moderation</strong><br>
Per-player anti-spam cooldown (OP / <code>rank.admin</code> bypass) and bot command-log grouping (<code>group|hide|off</code>) so bot <code>/...</code> spam collapses into one summary line per window.

</td>
<td width="50%" valign="top">

<strong>🚫 Command guard</strong><br>
Cancels <code>/effect</code>, <code>/summon</code>, <code>/tick rate</code> and <code>/enchant</code> for anyone without <code>allpurpose.commandguard.bypass</code> — including opped players, whom LuckPerms alone cannot stop.

</td>
</tr>
<tr>
<td width="50%" valign="top">

<strong>💥 Kill effects</strong><br>
Master switch plus optional per-rank cosmetic celebrations (<code>killfx.&lt;RANK&gt;</code>: rockets, storms, puffs — never damage). Ships off.

</td>
<td width="50%" valign="top">

<strong>⏱️ Ping advancement</strong><br>
The “Time Traveler” advancement is awarded when a player's ping crosses a configurable threshold.

</td>
</tr>
</table>

### 🧰 Server plumbing

<table>
<tr>
<td width="50%" valign="top">

<strong>🧹 RCON spam filter</strong><br>
Hides RCON connect / poll log noise and the Essentials <code>/list</code> polling echo. Log-only — RCON keeps working.

</td>
<td width="50%" valign="top">

<strong>🔑 Auto-login</strong><br>
Specific accounts are logged in automatically through the LoginSecurity API, so a restart never leaves a bot account stuck at the login prompt.

</td>
</tr>
<tr>
<td width="50%" valign="top">

<strong>🌉 RCON bridge</strong><br>
<code>/apbridge exec</code> captures a dispatched command's output (as console or as a real online player) so operator tooling can read what plain RCON never returns.

</td>
<td width="50%" valign="top">

<strong>⬆️ Self-updater</strong><br>
<code>/allpurpose update check|download|status</code> — GitHub Releases are the source of truth, the download is staged, the swap happens on shutdown only (never a hot-swap of the running jar), the old jar is backed up and the SHA256 is checked against <code>checksums.txt</code>.

</td>
</tr>
</table>

### 📡 Discord

<table>
<tr>
<td width="50%" valign="top">

<strong>📨 Discord relay</strong><br>
A webhook forwards joins, chat, deaths, rank-ups and plugin messages; each feed is switchable under <code>discord-events</code>.

</td>
<td width="50%" valign="top">

<strong>🟢 Status board</strong><br>
A live bot edits one message every few seconds (online count, average ping, server version, session line) and flips it to OFFLINE on shutdown; a gateway presence keeps the bot online in the member list.

</td>
</tr>
<tr>
<td width="50%" valign="top">

<strong>🗨️ Public chat log</strong><br>
A bot posts chat, <code>/say</code>, <code>/broadcast</code> and join / quit / death lines to a channel, using the speaker's head emoji.

</td>
<td width="50%" valign="top">

<strong>🕵️ Command &amp; DM audit log</strong><br>
Console, RCON and player commands plus private messages are written to a local file and relayed to Discord, with UUIDs and exact coordinates kept out of Discord.

</td>
</tr>
<tr>
<td width="50%" valign="top">

<strong>🗺️ Spawn snapshot</strong><br>
A rendered map of the spawn area with player pins and a legend, refreshed on an interval.

</td>
<td width="50%" valign="top">

<strong>📸 Spectator live-cam</strong><br>
A screenshot pipeline on a spare machine, with the Discord message deleted when it goes stale.

</td>
</tr>
</table>

### 🧩 For plugin developers

<table>
<tr>
<td valign="top">

<strong>🔌 Linked-plugin API</strong><br>
<code>AllPurposeAPI</code> (<code>src/com/hush/allpurpose/AllPurposeAPI.java</code>) is the read-only facade (rank reads, ladder, <code>lbKills</code> / <code>lbDeaths</code>). Privileged <code>RankManager</code> calls from outside <code>com.hush.allpurpose</code> are blocked and logged unless <code>allow-external-mutations: true</code>.

</td>
</tr>
</table>

## Commands

| Command | Aliases | What it does |
| --- | --- | --- |
| `/rank` | `/ranks` | Ladder view and rank management (create, delete, rename, give, remove, upgrade, prefix, colour, recalc, freeze). |
| `/lb` | — | Kill / death / playtime and baltop boards, plus `/lb holo` and `/lb removeholo`. |
| `/coinflip` | `/cf` | Wager GUI against another player. |
| `/bounty` | `/bounties` | Put money on a player's head; the killer collects it. |
| `/tax` | `/wealthtax` | Community fund: balance, patrons, treasury, rates, ledger, payouts and spends. |
| `/setholotext` | `/holotext`, `/ht` | Free-form floating text holograms. |
| `/changelog` | `/changelogs` | Server changelog as dialogs (`/changelog show`, `create`, `add`, `lines`, `remove`). |
| `/allpurpose` | — | `reload`, `info`, `update check\|download\|status`. |
| `/apbridge` | `/bridgeexec` | Captured command dispatch for the operator bridge (console / RCON only). |

## Requirements

- **Paper** 1.20+ (built against Paper 26.1.2) running **Java 21**.
- Optional soft dependencies — each one only enables the features that need it: **LuckPerms**, **Vault** (EssentialsX economy), **TAB**, **PlaceholderAPI**, **LoginSecurity**, **SkinsRestorer**.

## Building from source

```powershell
.\compile.ps1    # javac -> build_test\   (Java 21 bytecode, fails loudly on any error)
.\package.ps1    # jar   -> build200\AllPurpose-2.1.0.jar (descriptor + class-count guards)
```

The build stamps a unique version (`2.1.0-b<n>`) into every packaged jar so `/version` can tell two builds apart.

## License

Source-available, review-only (see `LICENSE`): staff may read the source and privately test-build against official releases. Without written permission: no publishing or distributing modified jars built from this source, no forks presenting themselves as AllPurpose or an update to it, and no companion/linked plugins whose purpose is to bypass, escalate, or misuse AllPurpose rank/permission behavior. Official builds are only the GitHub Releases attached to this repo with matching `checksums.txt`. AS-IS, no warranty.

# ✦ Nexus Login

> **The Next-Generation, Zero-Lag, Argon2id PIN Gate for Paper 1.21.x**  
> *Say goodbye to outdated, chat-leaking authentication plugins.*

Nexus Login is a modern, enterprise-grade authentication plugin designed specifically for **Paper & Purpur (1.21.x)** running on **Java 21**. It replaces legacy chat-based registration with a sleek, clickable **Neon PIN keypad**, zero-dependency procedural resource pack generation, and military-grade **Argon2id** password hashing.

---

### ⚡ Why Nexus Login over Legacy Auth Plugins?

| Feature | 🦖 Legacy Auth Plugins (AuthMe) | ⚡ Nexus Login |
| :--- | :--- | :--- |
| **Password Security** | Outdated SHA256 / BCrypt (GPU crackable) | **OWASP Standard Argon2id** (Memory-hard) |
| **Chat Safety** | Prone to chat leaks (forgetting `/` exposes password) | **Clickable GUI PIN Pad** (Zero chat leak) |
| **Concurrency / TPS** | Heavy synchronous / blocking queries | **Java 21 Virtual Threads (Zero TPS loss)** |
| **Resource Pack** | Requires ItemsAdder / Oraxen / external host | **Self-contained (Procedural Java 2D + Built-in HTTP)** |
| **Multi-Language** | Clunky manual editing | **Instant English (`en`) & Turkish (`tr`) switcher** |
| **Memory Footprint** | Bloated fat JAR (~10 MB) | **Lightweight (~300 KB JAR)** |

---

## 🚀 Features

- **Clickable PIN Interface:** Supports 8–9 digit PINs with sequence and pattern analysis (blocks `12345678`, `11111111`, common PINs). Logins with a PIN created before the limit was raised still work.
- **Self-Hosted Resource Pack:** Procedurally draws a neon portal & chest texture in memory using `java.awt.Graphics2D` and serves it **over the Minecraft port itself** — no extra port, firewall rule, CDN, ItemsAdder or Oraxen required. Players who decline the pack automatically get the stained-glass fallback GUI.
- **Visible Diagnostics:** Every resource-pack status (accepted / downloaded / declined / failed) is logged, so "the pack doesn't work" is no longer silent.
- **Persistent Brute-Force Shield:** Failed attempts are counted **per account** (and optionally per IP) in the database, so reconnecting or restarting the server does not reset the counter. Each new lockout doubles the previous duration up to 24 hours.
- **Tamper-Evident Audit Log:** Every failed/successful login, PIN change, lockout and admin action is written to `plugins/NexusLogin/nexus-auth.log`; lockouts also raise a console `WARNING`.
- **Offline-mode Impersonation Protection:** Prevents cracked launchers from stealing names like "Notch" or taking over OP accounts.
- **Visual Feedback:** 48-frame pre-computed gradient BossBar animation, rotating portal dust particles, sounds, and blindfold isolation.
- **Safety First:** Temporary invulnerability, fire extinguishing, and inventory hiding during login, with state restoration on auth or disconnect (inventory is backed up to disk first, so a crash can be recovered on the next join).

---

## 🛠️ Building & Installation

### Requirements
- **Server:** Paper or Purpur **1.21.1+** (tested on 1.21.11)
- **Java:** **Java 21 LTS** or newer

### Building from Source
```powershell
.\gradlew.bat build
```
Outputs: `paper/build/libs/NexusLogin-<version>.jar` (Paper plugin) and `velocity/build/libs/NexusLogin-Velocity-<version>.jar` (optional proxy plugin, see [Velocity network](#-velocity-network)). The project has three Gradle modules: `core` (platform-independent code, bundled into both jars), `paper` and `velocity`.

### Installation
1. Drop `NexusLogin-<version>.jar` into your server's `plugins/` folder.
2. Start the server! Paper will automatically download the required libraries (`HikariCP`, `Argon2-jvm`, `MariaDB Connector/J`) on first boot. This needs internet access to Maven Central; if the download fails the plugin cannot start, and the server then has no login protection from this plugin, so watch the first-boot log.
3. Nothing else — the resource pack is served over the Minecraft port (`resource-pack.delivery: same-port`). On shared hosting (Pterodactyl / Aternos-like panels) **do not** open port `8164`; it is not needed.
4. The generated ZIP is also written to `plugins/NexusLogin/pack/nexus-pack.zip`, so you can upload it to any static host if needed.

> Behind a Velocity/Bungee proxy the same-port trick cannot work (the proxy answers the port, not the backend). Set `resource-pack.delivery: port` and expose `resource-pack.port`, or host the generated ZIP yourself and use `delivery: url`.

> **MCServerHost / relay-based panels:** MCSH puts a Minecraft-aware relay in front of the server (the public `25565` reads the handshake before forwarding), so HTTP requests to that port are dropped even though the plugin's local self-test passes. Use the panel's *Resource Packs* section (or any static host) to upload `plugins/NexusLogin/pack/nexus-pack.zip`, then set:
> ```yaml
> resource-pack:
>   delivery: url
>   public-url: "https://.../nexus-pack.zip"
> ```

---

## 🔀 Velocity network

Since 1.1.0 NexusLogin can protect a **single Velocity proxy** with several Paper servers. It is **off by default**; without it every Paper server simply runs its own PIN gate.

**How it works.** Players always land on an *auth server* (a Paper server with NexusLogin) and enter their PIN there. The auth server sends a signed confirmation to the proxy; only then may the player join other servers. Every other server waits for the proxy's signed "authenticated" state and kicks the player if it does not arrive within 5 s, or if the proxy stops refreshing it (every 15 s, valid 45 s). Anything that cannot be verified leaves the player locked (fail-closed).

### Setup

1. Velocity **modern forwarding** (`player-info-forwarding-mode = "MODERN"`), and on every Paper server `config/paper-global.yml` → `proxies.velocity.enabled: true` with the same secret. BungeeCord legacy forwarding is refused.
2. Put `NexusLogin-Velocity-<version>.jar` into Velocity's `plugins/` and start it once. It creates `plugins/nexuslogin/config.properties` and `plugins/nexuslogin/nexuslogin-sync.key`.
3. Copy `nexuslogin-sync.key` to `plugins/NexusLogin/` on **every** Paper server. Keep it secret; it is separate from `forwarding.secret`.
4. On a fresh install NexusLogin creates `plugins/NexusLogin/pepper.key` on the first server that starts (see *Pepper*). Copy that same file to **every** Paper server too; a server with a missing or different `pepper.key` refuses logins and logs why.
5. On every Paper server, `config.yml`:
   ```yaml
   velocity:
     enabled: true
     server-name: "auth"   # exactly the name in velocity.toml [servers]
   ```
6. In Velocity's `config.properties`, list the auth servers (`auth-servers=auth`). Players go to the first available one; after login they are sent to `after-login-server` (or the first non-auth server in `try`).
7. Restart everything and watch the logs: Paper prints `Velocity köprüsü aktif`, Velocity prints the auth servers. If a prerequisite is missing, the Paper server logs why and **stays in single-server mode** (local PIN on that server).

Tested on Velocity 3.4.0, 3.5.1 and 4.3.1 (4.x needs Java 25) with Paper 1.21.11.

### Network limits (read before going live)

- **Trust boundary:** whoever controls an auth server can approve logins. Keep auth servers as locked down as the proxy.
- **More than one auth server** is only safe when they all use the **same MariaDB database**; with separate databases someone could register another player's name on the second auth server while the first is down. The proxy refuses to start with several auth servers unless you set `auth-servers-share-database=true`. Since 1.2.1 wrong-PIN counters are shared through that database: each auth server re-reads the player's and the IP's counter when they connect (one small indexed query per join), so moving to another auth server gives no extra attempts. If the database cannot be read the join is refused.
- **Clocks:** signed messages are valid for ±5 s. Keep proxy and backends in sync with NTP.
- **Command filter is not the boundary.** Velocity's `forward-commands-if-rate-limited = true` (default) forwards fast repeated commands to the backend without the proxy seeing them; they then reach the auth server, where NexusLogin blocks them. Server switching is enforced separately and cannot be bypassed this way. Setting `forward-commands-if-rate-limited = false` is an optional hardening.
- **Single proxy only.** Several proxies, Redis and limbo servers are not supported yet.
- **Premium auto-login** behind a proxy uses only the proxy's signed online-mode result; this path has not been tested with real Microsoft accounts.
- Join/leave messages appear on each backend separately, and a non-auth server briefly applies the login restrictions (and spawn teleport) until the proxy's state arrives.

---

## 🔐 Two-factor authentication (TOTP)

Since 1.2.0 players can add an authenticator app (Google Authenticator, Microsoft Authenticator, Aegis, 2FAS, Bitwarden…) as a second step after the PIN. **Off by default.**

```yaml
two-factor:
  enabled: true
  issuer: "NexusLogin"   # name shown in the app; no ':'
```

- **Set up:** a logged-in player runs `/2fa` (alias `/totp`). The menu shows a 32-character key; the player adds it in the app as *manual entry* (time-based, 6 digits, 30 s) and types the code it shows. Since 1.2.1 the chat also gets a **[Copy setup link]** button with the `otpauth://` link for desktop/password-manager apps (the key is only in the click action, not in the chat text). Closing the menu keeps the pending key for 3 minutes, so the player can copy the link and run `/2fa` again to continue with the same key. Nothing is stored until that first valid code. `/2fa` again turns it off with a current code.
- **Login:** after the PIN, the keypad asks for the 6-digit code. Codes are typed on the keypad, never in chat or commands.
- **Required for staff:** players with `nexuslogin.2fa.required` (give it with your permission plugin) must set up 2FA before they can play and cannot turn it off.
- **Lost phone:** run `nexus 2fa-reset <player>` from the **server console**. It is refused in-game, even for OPs, so a hijacked admin account cannot strip someone else's 2FA. `/nexus unregister` also removes 2FA.

How it is protected:

- Wrong codes count against the **same** brute-force counter as wrong PINs, and a correct PIN does **not** reset that counter for 2FA accounts; only a full login (PIN + code) does. Otherwise someone who knows a PIN could reconnect forever and keep guessing codes.
- Every code works once: the last used 30-second step is stored and updated atomically, so the same code cannot be accepted twice, not even by two servers sharing the database.
- Secrets are encrypted with AES-256-GCM. The key lives in `plugins/NexusLogin/totp.key` (created on first start) or in the `NEXUSLOGIN_TOTP_KEY` environment variable (base64, 32 bytes), never in `config.yml`. A leaked database alone does not reveal the secrets. **Back up the key separately from the database: if it is lost, every 2FA setup is unusable** (reset players from the console). If 2FA accounts exist and the key is missing, the plugin does not create a new one; those players are refused with a clear message and everyone else can still log in.
- Turning `enabled` off disables 2FA for everyone who set it up; the server warns about this on startup.
- Velocity network: the code is checked on the auth server and the proxy lets the player through only after it. Players run `/2fa` on the lobby, so **every server must use the same MariaDB database and the same `totp.key`**.
- Keep the server clock synced (NTP); codes are accepted for ±1 step (±30 s).
- Not included yet: recovery codes and QR codes. A player who loses the phone needs a console reset.

---

## 🌐 Configuration (`config.yml`)

```yaml
# Language: "en" (English) or "tr" (Turkish)
language: en

database:
  type: SQLITE # SQLITE or MARIADB
  mariadb:
    # enable TLS for remote databases: disable | trust | verify-ca | verify-full
    ssl-mode: disable # use verify-full when the DB is not on localhost

auth:
  timeout-seconds: 120
  max-accounts-per-ip: 3
  # WARNING: IP + UUID based; can bypass the PIN behind relays/shared IPs or in offline-mode.
  session-remember-minutes: 0 # 0 = off (recommended)
  # WARNING: removes the second factor; a stolen Minecraft session logs in without a PIN.
  premium-auto-login: false
  teleport-to-spawn: true
  restore-previous-location: true
  hide-inventory: true
  pin:
    min-length: 8
    max-length: 9
    # Recommended: true. New PINs need at least 4 different digits (existing PINs keep working).
    require-mixed-digits: false
  # Persistent brute-force protection (stored in the database).
  throttle:
    window-minutes: 15
    account-threshold: 5    # always enforced; >= 1
    ip-threshold: 0         # 0 = OFF (see relay warning below)
    base-lockout-minutes: 1 # doubles on every new lockout
    max-lockout-minutes: 1440

resource-pack:
  enabled: true
  # same-port: served over the Minecraft port (no extra port needed)
  # port:      standalone HTTP server on `port`
  # url:       external address in `public-url`
  delivery: same-port
  port: 8164
  public-host: ""
  public-url: ""
  required: false

security:
  # The audit log rotates at this size and keeps this many backups.
  audit:
    max-size-kb: 1024 # 0 = rotation disabled (not recommended)
    keep-files: 5
  argon2:
    memory-kib: 65536
    iterations: 3
    parallelism: 1
    # Server-side secret mixed in with HMAC-SHA256 before Argon2 (>= 16 chars).
    # "" = use plugins/NexusLogin/pepper.key (created automatically on a fresh install).
    # WARNING: never change this after accounts exist — every PIN becomes invalid.
    pepper: ""
```

---

## 🛡️ Security & Brute-Force Protection

### Network trust model (read this first)

The PIN gate can only be as strong as the identity data it receives:

- **Backend servers must not be reachable directly.** With BungeeCord *legacy* forwarding the client supplies its own IP in the handshake, so `ip-threshold`, `max-accounts-per-ip` and `session-remember-minutes` can all be spoofed. Use **Velocity modern forwarding** (or PROXY protocol) and firewall the backend so only the proxy can connect.
- **`session-remember-minutes` defaults to `0` (off).** Remember-me is keyed by IP + UUID: behind a relay, MCSH, CGNAT or shared NAT every player looks like the same IP, and in offline-mode the UUID is derived from the name. Turning it on in those setups lets someone re-connect as another player without a PIN. Changing the PIN invalidates the remembered session.
- **`premium-auto-login` defaults to `false`.** It authenticates a player solely from the Minecraft session; a stolen Microsoft/Mojang session or a cracked client that can spoof the handshake has no second factor.
- **Pepper (set it from day one).** The pepper is a server-side secret mixed into every PIN hash: with it, a leaked database alone cannot be cracked offline. Since 1.2.1:
  - **Fresh install** (no accounts yet, no pepper configured): NexusLogin generates a random 256-bit pepper in `plugins/NexusLogin/pepper.key` and logs a warning. **Back it up separately from the database.** If it is lost, no PIN can be verified.
  - **Existing accounts without a pepper:** nothing is generated (that would break every PIN); the server only warns, as before.
  - `security.argon2.pepper` in `config.yml` still works and wins over `pepper.key`.
  - A fingerprint of the generated pepper is stored in `nexus_meta`. If `pepper.key` is later missing, replaced, or different on another server of the same network, NexusLogin refuses logins (security lockdown) and logs what to do, instead of rejecting every PIN as wrong.
- **MariaDB TLS.** Set `database.mariadb.ssl-mode: verify-full` for any database that is not on `localhost`; otherwise credentials and PIN hashes travel in clear text.

### Known limits (by design)

- **Targeted lockout:** anyone can type wrong PINs for a known name and lock that account (escalating up to `max-lockout-minutes`). This is the price of a per-account counter that survives reconnects. Release it with `/nexus unlock <player>` from the console.
- **`delivery: port`** uses the JDK's built-in HTTP server, which has no request-read timeout. Prefer `same-port` (10 s header / 60 s download limits, max 128 concurrent downloads) or `url`.
- **Online-mode name reuse:** when a player takes a name that an older account still holds, the old row is renamed to `#<uuid-prefix>`; it gets its real name back on that player's next login.

- **Audit log on crash:** `nexus-auth.log` is written by a background thread and flushed per line. A hard crash (power loss, `kill -9`) can lose the few lines still queued; a normal stop drains the queue.
- **MariaDB migrations are not transactional:** MariaDB commits implicitly on `CREATE INDEX`/`ALTER TABLE`, so a failed schema upgrade cannot be rolled back. NexusLogin first copies `nexus_accounts` into `nexus_accounts_backup_v<N>` (keys and indexes kept; tested on MariaDB 11.4). Still take your own dump before upgrading a production database.

### Upgrade notes

- **1.2.1:** no schema change. New optional `auth.pin.require-mixed-digits` (default `false`) and a longer list of rejected common PINs (both only for new PINs). Fresh installs get an automatic `pepper.key`; servers that already have accounts are not changed. In a Velocity network with a shared MariaDB, copy `pepper.key` (if one was created) to every server.
- **1.2.0:** database schema v3 adds the `nexus_totp` table (nothing else changes; the pre-upgrade backup runs as usual). To go back to 1.1.x: stop the server, run `UPDATE nexus_meta SET meta_value = '2' WHERE meta_key = 'schema_version';` and optionally `DROP TABLE nexus_totp;`, then start the old jar. New `two-factor:` section, off by default.
- **1.2.0 (security):** lockouts now really keep doubling up to `max-lockout-minutes`. Before, from the 16-minute level onward the level dropped again right after each lock, so repeated attacks stayed at about 16-minute locks.

- **1.1.0:** new `velocity:` section in `config.yml`, off by default; configs without it keep working in single-server mode. No database schema change. When building from source the jar is now in `paper/build/libs/`.

- **1.0.4:** `auth.teleport-to-spawn` now defaults to `true` (also for configs that do not contain the key). Players are teleported to spawn while on the login screen and returned to their previous location after login. Set `teleport-to-spawn: false` to keep the old behaviour.
- **1.0.4:** a player without a login session is treated as restricted (fail-closed). NPC plugins that mark their fake players with the `NPC` metadata (Citizens) are not affected.

### Schema upgrade (v1 → v2)

Starting with 1.0.1 the plugin enforces **case-insensitive unique player names**. On first start it migrates automatically: if several rows share the same name, the oldest row is kept and the duplicates are deleted (a warning is logged with the count). Back up the database before upgrading a heavily modified server. The plugin refuses to start on a schema newer than itself, so a downgrade requires restoring the backup.

### Persistent, escalating lockout

Failed PIN attempts are stored in the `nexus_login_attempts` table, not in memory:

- **Account counter (always on):** keyed by UUID. A player who enters 4 wrong PINs cannot escape the counter by reconnecting or by waiting for a server restart.
- **IP counter (optional):** keyed by the connecting address. **Leave `ip-threshold: 0` on relay-based hosts** (MCServerHost, TCPShield-like relays, shared proxies) — those show every player with the relay's IP, so enabling it would lock innocent players out and let a single attacker lock the whole server. Set `20–30` only if real client IPs are forwarded.
- **Escalation:** the first lockout lasts `base-lockout-minutes`; each new lockout doubles it, capped at `max-lockout-minutes` (default 24 h). A completely quiet window lowers the level by one step so an old incident does not punish a player forever.
- **Reset rule:** a successful login clears only the **account** counter. The IP counter is intentionally kept, otherwise an attacker could burn the IP budget and then clear it by logging into their own account.
- **Admin unlock:** `/nexus unlock <player>` clears the account counter and the account's known registration/last IP counters in both memory and the database, without deleting the account. If IP throttling is enabled and the player's current address differs from the stored ones, `/nexus unlockip <ip>` clears that IP counter too. `/nexus unregister` and `/nexus resetpass` also clear those counters when they delete the account. No manual database edit is needed to release an accidentally locked admin.

Because the check runs in `AsyncPlayerPreLoginEvent`, a locked account never reaches the world — it is rejected at the network layer.

### Audit log

`plugins/NexusLogin/nexus-auth.log` receives one line per event:

```
LOGIN_FAIL  name=…  uuid=…  ip=…  attempts=4/5
LOCKOUT     name=…  uuid=…  ip=…  minutes=8  level=4
LOGIN_OK    name=…  uuid=…  ip=…  kind=PIN
PIN_CHANGE  name=…  uuid=…  ip=…
PRELOGIN_DENY name=…  uuid=…  ip=…  reason=locked
ADMIN       actor=…  action=unregister  target=…
ADMIN       actor=…  action=unlock      target=…
```

Lockouts are also printed to the console as `WARNING`, so an attack is visible without reading the file. User-supplied values are sanitised to prevent log-line injection.

The log rotates at `security.audit.max-size-kb` (default 1 MiB) into `nexus-auth.log.1`, `.2` … and keeps at most `security.audit.keep-files` backups (default 5). A sustained attack therefore cannot fill the disk. Set `max-size-kb: 0` only if you rotate the file yourself.

> **Note on relay hosts:** behind MCSH the `ip=` field is the relay's address, so IP-based blocking cannot distinguish players. Account counters remain effective; IP counters should stay disabled.

### Upgrading from the old counter

If your `config.yml` still has `max-attempts` / `lockout-minutes` and **no** `throttle` section, those legacy values are reused so protection does not silently disappear. Add the `throttle` section to get the new behaviour, and set `pin.min-length: 8` — existing shorter PINs still work for login.

### Schema upgrades

The database schema version is stored in a small `nexus_meta` table. Missing tables are created automatically on startup (`CREATE TABLE IF NOT EXISTS`), so upgrading from an older build needs no manual SQL. If the database was written by a **newer** plugin version, startup aborts with a clear error instead of risking data corruption; update the plugin rather than downgrading it.

---

## 🎮 Commands & Permissions

| Command | Aliases | Description | Permission |
| :--- | :--- | :--- | :--- |
| `/giris` | `/login`, `/l` | Re-opens the PIN gate if closed | *Default* |
| `/pin` | `/changepin`, `/pindegistir` | Change PIN code (logged-in players) | *Default* |
| `/nexus info <player>` | - | View player registration details | `nexuslogin.admin` |
| `/nexus unregister <player>` | - | Delete account & reset PIN (also clears lockout) | `nexuslogin.admin` |
| `/nexus resetpass <player>` | - | Alias of `unregister` (reset registration + lockout) | `nexuslogin.admin` |
| `/nexus unlock <player>` | - | Clear a brute-force lockout without deleting the account | `nexuslogin.admin` |
| `/nexus unlockip <ip>` | - | Clear a brute-force penalty for a specific IP address | `nexuslogin.admin` |
| `/nexus forcelogin <player>` | - | Bypass PIN and authenticate player | `nexuslogin.admin` |
| `/2fa` | `/totp` | Set up or turn off two-factor authentication | *Default* |
| `nexus 2fa-reset <player>` | - | Remove a player's 2FA (**console only**) | console |
| `/nexus reload` | - | Reload configuration & language files | `nexuslogin.admin` |

### What `/nexus reload` actually reloads

Applied live: authentication rules (`auth.*`), PIN length limits for new PINs, brute-force thresholds, the pre-login command whitelist, and the active language/messages.

Requires a full server restart: database settings, Argon2 cost parameters, resource-pack delivery settings, and audit rotation settings. The command tells you when a restart is needed instead of pretending everything was applied.

> Existing PINs always remain usable for login, even if you later lower `pin.max-length`. The smaller limit applies only when creating or changing a PIN.


---

<details>
<summary><b>🇹🇷 Türkçe Açıklama ve Özellikler (Genişletmek için tıklayın)</b></summary>

### Nexus Login — Yeni Nesil Paper 1.21 PIN Giriş Sistemi

Eski, hantal ve oyuncuların `/` koymayı unutup şifrelerini tüm sunucuya ifşa ettiği AuthMe devrini kapatan modern giriş sistemi.

- **Argon2id Güvenliği:** OWASP standartlarında bellek zorlamalı şifreleme; sızsa bile kırılamaz.
- **Kalıcı Kaba Kuvvet Koruması:** Hatalı denemeler hesap bazında veritabanında tutulur; yeniden bağlanmak veya sunucuyu yeniden başlatmak sayacı sıfırlamaz. Her yeni kilit süresi ikiye katlanır (en fazla 24 saat). Yanlışlıkla kilitlenen bir hesap, `/nexus unlock <oyuncu>` ile hesap silinmeden açılabilir.
- **Denetim Günlüğü:** Tüm giriş/kilit/PIN değişimi ve yönetici işlemleri `plugins/NexusLogin/nexus-auth.log` dosyasına yazılır; kilitler konsola `WARNING` olarak da düşer. Günlük 1 MiB'da döndürülür ve en fazla 5 yedek tutulur; bir saldırı diski dolduramaz.
- **Sıfır Bağımlılık:** ItemsAdder veya Oraxen gerekmez; doku paketini bellekte kendisi çizer ve **Minecraft'ın kendi portu üzerinden** dağıtır — ek port açtırmak gerekmez.
- **Java 21 Sanal Thread'leri:** Sıfır TPS kaybı, sunucuyu yormayan asenkron veritabanı.
- **Türkçe Desteği:** `config.yml` dosyasında `language: tr` yaparak tüm mesajları anında Türkçeye çevirebilirsiniz (`messages_tr.yml`).

</details>

---

## 📄 License
This project is licensed under the [MIT License](LICENSE).

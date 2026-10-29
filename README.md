# Creeper Capital

**English** | [简体中文](README.zh-CN.md)

> Trade perpetual futures on vanilla Minecraft resources — inside Minecraft, on a Binance-style exchange.

![Trading screen](docs/images/overview.png)

*(Screenshots show the Simplified Chinese UI; an English UI is included and follows your game language.)*

Creeper Capital is a NeoForge mod that adds a shared, server-wide futures exchange to Minecraft. Contracts track vanilla resources (diamond, gunpowder) and settle in **Creeper Coin (CRP)**, a ledger balance on the server — never an item. Every player trades against a system **liquidity pool**, so there is always a counterparty.

> **Status: alpha (`0.1.0-alpha.1`).** This first release validates the trading rules and the market feel with two contracts. Expect rough edges, and back up your worlds — save formats may change between alphas.

## Features

- **Two perpetual contracts:** `DIAMONDCRP` (up to 125x) and `GUNPOWDERCRP` (up to 50x), each with its own volatility profile (GARCH, fat tails, Poisson jumps, bull/bear/ranging regimes). Contracts are configurable in a server config file.
- **Binance-style trading screen** (default key **K**): candlestick chart with MA and volume, order book, latest trades, order panel, position / order / trade / funding tables, and a green-up/red-up color switch.
- **Order types:** market, limit, take-profit / stop-loss, reduce-only.
- **Margin:** isolated and cross margin, tiered leverage and maintenance rates.
- **Risk engine:** funding rate settlement every 8 market hours, liquidation, a risk insurance fund, and auto-deleveraging (ADL) with a position cap per contract.
- **Slippage along a virtual liquidity curve:** the order book and fills share one source, so what you see is what you get.
- **Server-authoritative and offline-safe:** positions keep settling while you are offline; you get a summary when you log back in. The market and all positions freeze together when the server stops.
- **Fast market clock:** 1 real second = 6 market seconds; 30 market days of history are pre-generated on world creation.
- **Languages:** English and Simplified Chinese.

![Limit orders and order book](docs/images/limit-orders.png)

## Requirements

| | |
|---|---|
| Minecraft | 26.1.2 |
| Loader | NeoForge 26.1.2.124 or newer |
| Java | 25 |
| Sides | Both (install on the server **and** every client) |

## Install

1. Install NeoForge for Minecraft 26.1.2.
2. Download `creepercapital-<version>.jar` from the [Releases](https://github.com/djp440/CreeperCapital/releases) page.
3. Drop it into the `mods` folder of your client and (for multiplayer) your server.

## Getting started

1. Join a world. Press **K** to open the exchange (rebindable in the key settings).
2. There is no in-game way to earn CRP yet. An operator tops up an account with:
   ```
   /creeper dev deposit <player> <amount>
   ```
3. Pick a contract, choose leverage and margin mode, and open a position from the order panel.

Operator commands (all under `/creeper dev`, OP only): `deposit` / `withdraw` (account balance), `pool fund` (liquidity pool), `market pause|resume|advance|funding|jump` (drive the market for testing).

### Configuration

- **Server:** `config/creepercapital-contracts.json` defines the contracts (created with the two defaults if missing; restart to apply changes). Optional fields include `spread`, `max_leverage`, `quantity_decimals`, `depth_slippage`, `maintenance_tiers`, and `open_interest_ratio`.
- **Client:** `config/creepercapital-client.toml` — `colorScheme` is `GREEN_UP` (default) or `RED_UP`. It is also available in the mod list under *Creeper Capital → Config* and as a button in the trading screen.

## Known limitations (alpha)

- CRP can only be granted by operators; there is no earning or spending of CRP in the game world yet.
- Futures positions are one-way only (no hedge mode); only two contracts ship.
- If the liquidity pool cannot pay out a profit in full, the payout is truncated rather than triggering ADL (see ADR-0007).
- Liquidation and ADL fills appear, with the player name, in the public *Latest trades* panel.
- The chart cannot be dragged or zoomed yet, and indicators cannot be hidden or configured. See the [open issues](https://github.com/djp440/CreeperCapital/issues) for the roadmap.

Deferred ideas and open design decisions are tracked in [`HUM_MEMO.md`](HUM_MEMO.md).

## Building from source

```
./gradlew build          # jar in neoforge/build/libs/
./gradlew check          # core unit tests + JUnit + all GameTests
./gradlew :neoforge:runClient
```

The project has two Gradle modules that ship as one jar: `core/` (pure Java 17 domain logic, no Minecraft dependency) and `neoforge/` (game integration). See [`AGENTS.md`](AGENTS.md) for commands and conventions, [`GLOSSARY.md`](GLOSSARY.md) for the domain language, and [`docs/adr/`](docs/adr/) for architecture decisions.

## Contributing

Issues and ideas are welcome on the [issue tracker](https://github.com/djp440/CreeperCapital/issues).

## License

[GPL-3.0-only](LICENSE).

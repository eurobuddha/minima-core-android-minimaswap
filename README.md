# minimaSwap (native Android) — ⚠️ DEPRECATED

Trustless, non-custodial **cross-chain atomic swaps between native MINIMA and Ethereum ERC20 (USDT)** on
[Minima](https://minima.global), via hash-time-locked contracts (HTLC): an on-chain order book + swap engine,
no custodian, no bridge operator.

## ⚠️ No longer maintained — use AtomiX

Development of **minimaSwap stopped**, and several **stability and fund-safety fixes landed after this code was
frozen** — they are **not** in this repository. **Do not build or run fund-moving code from this repo as-is;** read
the current, maintained implementation instead.

**➡️ Superseded by AtomiX** — the single app that combines **minimaSwap + usdtSwap** (choose MINIMA or mxUSDT per
trade). AtomiX carries the up-to-date HTLC/swap engine and every later fix. **Refer to the AtomiX code:**

### 👉 https://github.com/eurobuddha/minima-core-android-atomix

Why the merge: minimaSwap and usdtSwap **clashed** when run on the same node — both derived the *same*
seed-derived Ethereum address, so their background swap-watchers could collide on the ETH nonce. AtomiX resolves
this by running one collector/poller with a single selectable trading currency. The later fixes (broadcast ≠
confirmation, nonce self-heal, ETH-leg fund-safety, preimage verification) live in AtomiX, not here.

## Status
Frozen for reference only. The last built APK was delisted from the PandaApps store; install
**[AtomiX](https://github.com/eurobuddha/minima-core-android-atomix)** instead.

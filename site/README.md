# Modding Accelerator website

The project's public site: home, get started, features, supported, bench, porting map, roadmap, docs and contribute. Every number is read from the repo (`coverage/ledger.json`, `coverage/changes/index.json`, `ROADMAP.md`, `SCOPE.md`, `README.md`) by `scripts/export_data.py` at build time, so the site can't claim more than the ledger does.

Built on [Rat Stack](https://ratstack.sh) (MIT, see `LICENSE.rat-stack`), trimmed: a Cloudflare Worker, Foldkit pages (server-rendered, prerendered), the oxlint/ultracite/effect-tsrs fence, Alchemy deploy config, and one typed contract. No auth, D1, Durable Objects or XState.

The same contract is served for tools and agents:

- `POST /api/portRoute` `{"from":"1.20.1","to":"1.21.6"}`
- `POST /api/modStatus` `{"mod":"AppleSkin"}`
- `GET /api/openapi` (OpenAPI description)
- `POST /mcp` (MCP tools `portRoute` and `modStatus`)

## Preview locally

Needs Node 24 and Python 3.12.

```sh
cd site
npx pnpm@11.3.0 install
npx pnpm@11.3.0 run preview   # builds, then serves the Worker at http://127.0.0.1:8787
```

`run dev` is the Vite dev server; `run check`, `run test` and `run build` are what CI runs on pull requests that touch `site/`.

Git hooks: none. Rat Stack ships lefthook, but installing it would put hooks on the whole repo, so the fence runs in CI (`.github/workflows/site.yml`) instead.

## Deploy (not done yet, needs Jon)

Deploying is manual only. Jon provides:

1. A Cloudflare account.
2. A Cloudflare API token (Workers edit) and the account id, saved as repo secrets `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`.
3. A domain choice (the Worker gets a `workers.dev` URL until a custom domain is added in Cloudflare).

Then run the **Website** workflow from the Actions tab with "deploy" ticked, or locally with those two variables set: `npx pnpm@11.3.0 run infra:plan` to see the change and `npx pnpm@11.3.0 run infra:deploy` to apply it.

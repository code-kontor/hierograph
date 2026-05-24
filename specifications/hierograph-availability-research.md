# HieroGraph: Name Availability Research

Practical checklist for confirming the name is available before committing. Ordered roughly by what matters most for a developer tool launching with MCP and OSS positioning.

## The critical checks

These are the ones that would actually block your launch if blocked. Do all of them.

### GitHub

**Username/organization:** `github.com/hierograph` — type the URL directly. If you get a 404, it's available; if you get a profile page, it's taken.

**Repository search:** Go to `github.com/search?q=hierograph` and look at all results. You're looking for:

- Active repos in dev tools / code analysis / AI tooling space (problematic)
- Personal experiments, stale projects, unrelated languages (probably fine)

A quick keyword search for `hierograph language:any` will surface anything significant.

### npm

If you'll ever publish a Node package or even just want to reserve the name: `npmjs.com/package/hierograph` — same logic, 404 means available.

Even if you're not planning to publish on npm, reserving the name there prevents anyone else from squatting it. You can publish a placeholder package with just a README pointing to your real project.

### MCP Registry

The MCP ecosystem has a registry where servers can list themselves. Search there too — this is *specifically* relevant for you since you're publishing an MCP server. The other Cartograph had `io.github.anthony-maio/cartograph` as their MCP registry namespace, which suggests the registry uses GitHub-based IDs. If your GitHub org is `hierograph` (or whatever username you choose), the MCP namespace likely follows.

### Domains

Several to check:

- **`hierograph.dev`** — the natural primary domain for a dev tool
- **`hierograph.io`** — historically popular for dev tools, slightly dated now
- **`hierograph.tools`** — fits the dev-tool category explicitly
- **`hierograph.com`** — least likely to be available, but worth knowing
- **`hierograph.app`** — if you ever build a hosted version

Use a registrar like Namecheap or Porkbun for the check — they show availability across many TLDs at once. Cloudflare also has a clean check interface.

If you find an available `.dev` or `.tools`, that's enough. You don't need all of them. But buying a couple of variants (typically $10–15 each) is cheap insurance against squatters.

### Social handles

- **`x.com/hierograph`** — even if you don't plan to actively use it, reserve it
- **`bsky.app/profile/hierograph.bsky.social`** — Bluesky is increasingly relevant for dev tools
- **`mastodon.social/@hierograph`** or your preferred Mastodon instance

The risk of not reserving these is that someone else later will, and they can't be reclaimed without dispute.

## Useful secondary checks

These won't block your launch but are worth knowing about.

### General web search

Google "hierograph" and "hierograph software" and "hierograph tool" — read through the first two pages of results. You're looking for:

- Active commercial products with that name
- Academic projects with that name in adjacent fields
- News stories, controversies, anything you'd want to know about before adopting the brand

For "hierograph" specifically, my strong expectation is that you'll find mostly historical/linguistic references (Wikipedia entries about ancient writing, etymology pages, perhaps some academic papers). Those are fine — they establish the word's legitimacy without competing with you.

### Trademark search (USPTO and EU equivalent)

This matters if you ever plan to commercialize or trademark the name. For an OSS launch, it's not blocking, but useful to know:

- **`tmsearch.uspto.gov`** for US trademarks
- **`tmview.europa.eu`** for the EU equivalent

You're looking for active trademarks in classes related to software (typically class 9 for software products and class 42 for software services). A casual search is fine; you don't need a lawyer for this stage.

If you find an existing trademark in your category, that's a real signal to reconsider — trademarks are stronger ownership claims than domain names. If you find trademarks in unrelated categories (a hieroglyph-themed clothing brand, say), that's irrelevant to you.

### Existing dev tools, more carefully

Beyond GitHub, check the major dev-tool aggregators:

- **`producthunt.com`** — search "hierograph"
- **`reddit.com/r/programming`** and **`r/devtools`** — search for any mentions
- **`news.ycombinator.com`** — Hacker News search via Algolia (`hn.algolia.com`) for "hierograph"

If nothing turns up across these, you're probably clean.

## The order to do these in

For maximum efficiency:

1. **Five minutes:** GitHub username and search, npm package search. These tell you almost immediately if there's a serious problem. If something significant is taken here, reconsider before going further.

2. **Five minutes:** A domain check on Namecheap or Porkbun for `hierograph.dev`, `.io`, `.tools`, `.com`. Note which are available and the prices.

3. **Five minutes:** Google search for "hierograph" plus "software," "tool," "library." Read what comes up.

4. **Five minutes:** Social handle availability — X, Bluesky, Mastodon. Reserve any that look clean.

5. **Five minutes:** Hacker News and Reddit search via Algolia and Reddit's search. Look for any prior mentions.

6. **(Optional, ten minutes):** Trademark search via USPTO and EU. Skip if you're not planning to commercialize.

Total: 30–45 minutes for a thorough check. Should give you a clear yes/no on whether HieroGraph is genuinely available.

## What "available" actually means

A small calibration: total absence of any prior use is rare and not actually required. What you want is:

- **No active competitor in your category** (dev tools, code analysis, AI tooling)
- **No existing trademark you'd be infringing on** (if commercializing)
- **No catastrophic existing meaning** (like the name being a slur in some language, or associated with something terrible)
- **The domains/handles/namespaces you care about most are claimable**

Some background usage is fine. If "hierograph" appears in a 2003 academic paper, or as an obscure word in a few dictionaries, or as a one-off art project from 2014 that's now defunct — none of that blocks you. The OSS world is full of legitimate name overlap with non-competing prior uses.

## What to do if you find a problem

A few specific scenarios:

**Someone owns `hierograph.com` but it's parked/unused.** Common situation. You can buy `hierograph.dev` and call it a day; `.com` doesn't matter much for OSS dev tools. Or, if you really want it, try contacting the owner — parked domains often have a "make an offer" link. Budget: usually $50–500 for a low-value parked domain.

**There's an existing repo called `hierograph` on GitHub but it's stale.** Look at last commit date and star count. A stale personal experiment from 2017 with 2 stars isn't a real conflict — you can use the name `hierograph-mcp` for your repo or pick a different organization name. Or claim a different org and let the stale repo coexist.

**There's an active product with that name in an unrelated space.** Generally fine. Microsoft uses "Office" for their productivity suite without conflicting with all the other "Office"s in the world. As long as your tool's domain is distinct (code analysis vs., say, a yoga app), there's no real conflict.

**There's an active product with that name in dev tools.** This is the actual concern. If you find this, you've found the third Cartograph and need to pick something else. But for HieroGraph specifically, this would be very surprising — the word is too obscure to have been claimed in your niche.

## My guess about what you'll find

Based on the word's obscurity:

- GitHub username: open
- GitHub significant repos: probably one or two stale personal experiments, nothing serious
- npm: open
- `hierograph.dev`: open
- `hierograph.io`: probably open
- `hierograph.com`: possibly taken by a domain squatter, possibly available
- Trademarks: none active in software

If this prediction is right, you can commit within an hour and move on. If something surprising turns up, adjust.

## After the check

If everything's clean:

1. **Buy the .dev domain immediately.** $15 of insurance. Don't agonize over which TLD; .dev is the best fit for a dev tool. You can add others later.

2. **Reserve the GitHub organization.** Create it tonight, even if the actual repository move waits a few days. Same for npm — publish a placeholder package if needed.

3. **Reserve social handles.** X, Bluesky, Mastodon. Five minutes of setup; permanent insurance.

4. **Then start the actual rename work.** Find-and-replace across documents, update the README, commit to the new identity.

The whole sequence — research, decisions, reservations — fits in an evening.

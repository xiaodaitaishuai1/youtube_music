# Search fixtures

`single`, `multi`, `trailing-card`, `append-commands` and `append-actions` are synthetic minimal regressions from the handoff. IDs and titles are invented.

`real/` contains anonymous YouTube Music responses captured on 2026-10-13 through `InnerTubeTransport`, using the library's default WEB_REMIX client, locale en/US, and the local system proxy only in opt-in test tools. Queries: 周杰伦, Jay Chou, Shape of You, and an intentionally meaningless query. The latter still receives fuzzy song results; its ARTISTS response is the verified no-results case.

Response context, visitor data, tracking fields, logging contexts, menus and buttons are removed. Continuation values are replaced with `fixture-continuation`; live requests use the unmodified response token before sanitization. No cookies, authentication headers or request sessions are stored here. Catalog IDs identify public songs, albums, artists, playlists and channels.

Verified containers include selected tab section lists, top music cards, item-section wrappers, filtered shelves, and music-shelf continuations. Live ALL results also include user channels (mapped to the existing Artist model) and podcast shows (counted as unsupported, with no invented Album model). Append action fixtures are synthetic; the captured real continuations use musicShelfContinuation.

Ordinary unit tests are offline. Re-capture explicitly with `YT_SEARCH_CAPTURE=1`; validate the public client's real search and pagination with `YT_SEARCH_VALIDATE=1`. Force test execution with `--rerun-tasks`. These opt-in tools currently use the verified local proxy `127.0.0.1:7897`; they do not modify production client defaults.

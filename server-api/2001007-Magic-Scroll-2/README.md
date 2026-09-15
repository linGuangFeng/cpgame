# Magic Scroll 2 Java API

Controller contract v3. Demo results are claimed from Redis `192.168.10.3:6379` db 15.
The process never generates a live board, never reads fixtures, and fails if the pool is empty.

Launch is platform-injected: `--config`, `--port` (50000-59999), `--publish`.
Main class: `com.hd.cpgame.magicscroll2.server.ServerMain`.

Original wire:

- `POST /web-api/auth/session/v3/verifySession`
- `POST /game-api/cp-magic-scroll2/v2/GameInfo/Get`
- `POST /game-api/cp-magic-scroll2/v2/Spin`
- `POST /game-api/cp-magic-scroll2/v2/History`

Paid Spin claims one complete Round member. Zero-bet continuations project the same member.
`HistoryDate` returns the captured HTTP 404. Feature buy / xBET / Magic Mining / Lucky Wagon
generation remain out of scope per protocol.

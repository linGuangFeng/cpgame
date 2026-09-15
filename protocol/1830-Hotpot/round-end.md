# 1830 Hotpot — how a Round ends

Game ID `1830`. Names and fields are from this game only.

## Paid start

A paid Round starts when `game_1830` is in `INIT` or `GAME_ENDED`, `isScatterIng` is false, and `RequestBet` calls `Game1830Mgr.RequestGameBet`.

Request:

- path: `POST /cp/single_game.Game/gameResult`
- body from this game: `token`, `bet_gold` (Bet Size / `BaseBetNum`), `level`, `gid=1830`, optional `act_id` for promotional free-bet
- HTTP layer may also inject `language` and `ai`

`RecBetClick` sets `PageIndex=0` and `gameStatus=GAMERESULT`.

## One HTTP spin vs cascade pages

One `gameResult` response already contains every cascade page of that spin in `data.props[]`. The client does not request the next page.

Page machine (`enum_Game1830RutStatus`):

1. show `props[PageIndex]`
2. if `win_arr.length>0` → `ELIMINATE` all cells whose `symbolId==win_arr[i].p`
3. remaining symbols fall down (`OLDMOVES`)
4. if not last page → fill vacancies from `props[PageIndex+1]` (`NEXTPAGE_ELIMINATE`) and eliminate again
5. last page (`PageIndex >= props.length-1`) → `setGameEnd`

Help (Game1830_6 / Game1830_7): winning symbols explode, symbols above cascade, continue until no more winning combination.

## Ordinary loss

Complete paid Round, no Scatter Free Spins, no positive win.

Classification from this game:

- `frees.st==0` so `checkScatterModel()` is false
- `total_win==0` (and last page `tw==0`, `win_arr=[]`)
- `change_gold` equals `-data.bet_gold`

That is `ORDINARY_LOSS`. Dirty fixture `fixtures/1830-Hotpot/spin-dirty-20260903` matches this shape and is quarantined — not counted.

## Ordinary win

Complete paid Round with positive `total_win` / last-page `tw`, still `frees.st==0`. Cascade pages inside that one response stay in the same Round. That is `ORDINARY_WIN`.

## Special (Scatter Free Spins)

This game's special mode is **Scatter Free Spins**, not a 1809/1407 "mali/Mary" name.

Trigger (Help Game1830_14 + BetDetails):

- 3 Scatter (symbol id 11) anywhere on the paid spin → 10 free spins
- each extra Scatter → +2
- frontend: `type!=2` and scatter count `h>=3`, award `10+2*(h-3)`

After the paid spin animation, `GetFreeTimesView` shows `frees.st`, then `isScatterIng=true`. Further `gameResult` calls are the same endpoint; client skips bet-limit and gold deduct while `isScatterIng`.

Retrigger (Help Game1830_17): 2 Scatter during free spins award 5 more (`type==2`, `h>=2`, `5+2*(h-2)`).

Feature ends when `frees.st==0`. Client shows `Game1830FreeSpinTotalWinView` when `frees.tt>0 && frees.st==0`, then `GAME_ENDED`.

The complete Round is the paid trigger plus every free `gameResult` until that terminal.

## Buy

**Not an independent mode.** No buy path, no buy form, no buy API in Game1830. Help never describes a purchase. Promo `FreeBetTimes` / `act_id` is an activity free-bet overlay, not Scatter Free Spins and not a buy feature.

Collection, generator and Demo must **not** create a BUY bucket. Scatter Free Spins is the only confirmed special.

## History (lightweight)

1. Record → `single_game_user_gold_history` day list
2. click day → `Game1830Daily` + `single_game_user_history`
3. click order → `Game1830BetDetails` (`data.results[]` groups, `result[]` pages)

Capture list+detail once per confirmed outcome. Do not attach full history to every spin. Do not use history to fill 200/200/30.

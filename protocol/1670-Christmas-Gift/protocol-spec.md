# Christmas Gift (1670) protocol

`POST /cp/single_game.Game/gameResult` starts one paid full Round. At the verified minimum it sends `bet=0.08`, `level=10`, producing `bet_gold=4`. Authentication/signature values are intentionally not persisted.

`data.type=1` is ordinary. `data.type=2` is the Christmas Gift feature: `luck_prop` selects the non-Wild target and `props[]` is the ordered respin lifecycle. Each step carries position-keyed symbols and `win_arrs`; the final response totals are authoritative.

History is advertised but not runtime-applicable in this original build: its external SDK bootstrap is empty and its derived `/order/log_list` route returns HTTP 404.

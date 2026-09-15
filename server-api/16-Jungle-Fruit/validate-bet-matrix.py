#!/usr/bin/env python3
"""Actual HTTP bet/mode regression. Oracle is archived raw Config, not Java outputs."""
from pathlib import Path
import collections, decimal, hashlib, json, time, urllib.request, urllib.parse, uuid
D=decimal.Decimal
base=Path('/Volumes/hd/cpgame')
raw=base/'captures/16-Jungle-Fruit/original-http-preflight.json'
table=json.loads(raw.read_text())['config']['symbol_pay_list']
host='http://127.0.0.1:50116'
def http(path,data=None):
    body=None if data is None else urllib.parse.urlencode(data).encode()
    with urllib.request.urlopen(urllib.request.Request(host+path,data=body),timeout=4) as response:
        return json.load(response)
def money(x): return D(x).quantize(D('.01'),rounding=decimal.ROUND_HALF_UP)
health=http('/health')
assert health['rulesHash']=='164088c0440871c9bc33b631a1b6bfa5e00d3012db3b0e3694bb59ac683e1743' and health['redisReachable']
report={'status':'RUNNING','rulesHash':health['rulesHash'],'oracle':str(raw),'oracleSha256':hashlib.sha256(raw.read_bytes()).hexdigest(),'checks':[]}
started=time.monotonic()
for mode in ['MARY','FREE']:
 for size in ['0.05','0.5','2.5']:
  for level in range(1,11):
    token='jf16-bet-regression-'+str(uuid.uuid4())
    paid=D(size)*level*20
    before=D(http('/api/balance?t='+token)['data']['balance'])
    balance=before-paid;accumulator=D(0);payout=D(0);previous=None;boards=[]
    for i in range(150):
      args={'gid':16,'t':token,'bet_level':level,'bet_size':size,'scenario':mode,'request_id':str(i)}
      response=http('/cp/api/v1/jungle-fruit/spin',args)
      step=response['data'];board=step['rand_symbol_key_list'];assert len(board)==36
      counts=collections.Counter(board)
      wins={symbol:[(ix//6)*10+ix%6 for ix,v in enumerate(board) if v==symbol] for symbol,n in counts.items()
            if symbol in table and n>=8 and table[symbol].get(str(n),0)>0}
      expected=sum((D(table[symbol][str(len(indices))])*D(size)*level for symbol,indices in wins.items()),D(0))
      assert D(str(step['win_amount']))==expected,(mode,size,level,i,'raw Config award')
      assert set(step['win_symbol_key_list'])==set(wins)
      assert {frozenset(x) for x in step['win_match_key_list']}=={frozenset(x) for x in wins.values()}
      assert step['spin_status']==(0 if expected else 1)
      assert D(str(step['bet_amount']))==(paid if i==0 else 0)
      assert D(str(step['bet_size']))==D(size) and step['bet_level']==level
      if previous and previous['spin_status']==0:
        old=previous['rand_symbol_key_list'];removed=set(previous['win_symbol_key_list'])
        for col in range(6):
          survivors=[x for x in old[col*6:col*6+6] if x not in removed]
          assert board[col*6+6-len(survivors):col*6+6]==survivors
      if mode=='FREE':
        if i==0:
          assert step['free_spin_num']=={3:10,4:12,5:14}[counts['Scat']]
          assert step['now_free_spin_count']==0
        else:
          assert step['now_free_spin_count']==previous['now_free_spin_count']+(1 if previous['spin_status']==1 else 0)
          added=5 if step['spin_status']==1 and counts['Scat']==2 else 0
          assert step['free_spin_num']==previous['free_spin_num']+added
      else:
        assert step['free_spin_num']==step['now_free_spin_count']==0
      if step['spin_status']==0:
        accumulator+=expected;segment=accumulator
      else:
        factor=sum(int(x[1:]) for x in board if x.startswith('X')) or 1
        segment=accumulator*factor;accumulator=D(0)
        payout+=segment;balance+=money(segment)
      assert D(str(step['round_win_amount']))==segment
      assert D(step['player']['balance'])==balance,(mode,size,level,i,'ledger')
      if i==0:
        assert http('/cp/api/v1/jungle-fruit/spin',args)==response
        assert D(http('/api/balance?t='+token)['data']['balance'])==balance
      boards.append(step);previous=step
      if step['spin_status']==1 and step['free_spin_num']==step['now_free_spin_count']:break
    else:raise AssertionError('No complete terminal within 150 deliveries')
    session=http('/api/session?t='+token)['data']
    assert session['activeRound'] is None and D(session['balance'])==before-paid+payout
    report['checks'].append({'mode':mode,'bet_size':size,'bet_level':level,'betAmount':str(paid),'payout':str(payout),'balance':str(balance),'steps':boards,'firstRequestReplayIdentical':True})
    if len(report['checks'])%10==0:print('VALIDATED',len(report['checks']),flush=True)
report.update(status='PASS',completeRounds=len(report['checks']),steps=sum(len(x['steps']) for x in report['checks']),failures=0,elapsedMs=round((time.monotonic()-started)*1000),scope='Actual API complete Rounds, all 30 Config combinations in MARY and FREE; browser exhaustive bet matrix is not claimed.')
(base/'reports/16-Jungle-Fruit/positive-bet-matrix-validation.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k!='checks'}))

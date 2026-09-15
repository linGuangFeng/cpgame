import json,collections,hashlib,pathlib
base=pathlib.Path('/Volumes/hd/cpgame')
source=base/'captures/16-Jungle-Fruit/original-http-spin-index.jsonl'
rs=collections.defaultdict(list)
for line in source.open():
 x=json.loads(line);rs[x['roundOrdinal']].append(x)
hold=set(sorted(rs,key=lambda n:hashlib.sha256(('jf16-holdout-v1:'+str(n)).encode()).hexdigest())[:100])
blocks=collections.Counter();entryCounts=collections.Counter();slotSymbols=collections.defaultdict(collections.Counter);holdlines=[];maximum=collections.Counter()
for rid,items in sorted(rs.items()):
 items.sort(key=lambda x:x['stepOrdinal']);ds=[x['response']['data'] for x in items]
 if rid in hold:
  for i,d in enumerate(ds):
   holdlines.append('|'.join(map(str,[rid,i,d['bet_amount'],d['bet_size'],d['bet_level'],d['spin_status'],d['free_spin_num'],d['now_free_spin_count'],d['small_game_type'],d['win_amount'],d['round_win_amount'],','.join(d['rand_symbol_key_list']),','.join(sorted(d['win_symbol_key_list']))])))
  continue
 cascades=0
 for i,d in enumerate(ds):
  if i==0:entry='PAID_FREE' if d['free_spin_num'] else 'PAID_CASCADE' if d['spin_status']==0 else 'PAID_LOSS'
  elif ds[i-1]['spin_status']==1:entry='FREE_INITIAL'
  else:entry='FREE_REFILL' if d['free_spin_num'] else 'BASE_REFILL'
  entryCounts[entry]+=1;board=d['rand_symbol_key_list']
  cascades=cascades+1 if d['spin_status']==0 else 0
  maximum['cascades']=max(maximum['cascades'],cascades);maximum['freeSpins']=max(maximum['freeSpins'],d['free_spin_num'])
  maximum['boardScat']=max(maximum['boardScat'],board.count('Scat'));maximum['boardX']=max(maximum['boardX'],sum(s.startswith('X') for s in board))
  for c in range(6):
   col=board[c*6:c*6+6];maximum['columnScat']=max(maximum['columnScat'],col.count('Scat'));maximum['columnX']=max(maximum['columnX'],sum(s.startswith('X') for s in col))
   n=6
   if entry.endswith('REFILL'):
    prev=ds[i-1];remove={int(v) for row in prev['win_match_key_list'] for v in row}
    survivor=[v for row,v in enumerate(prev['rand_symbol_key_list'][c*6:c*6+6]) if c*10+row not in remove];n=6-len(survivor)
    assert col[n:]==survivor,(rid,i,c)
   if n:
    block=tuple(col[:n]);blocks[entry,c,n,block]+=1
    for row,symbol in enumerate(block):slotSymbols[entry,c,n,row][symbol]+=1
denoms=collections.Counter()
for (e,c,n,b),count in blocks.items():denoms[e,c,n]+=count
lines=['# entry|column|replacementLength|symbols|observedCount']
for (e,c,n,block),count in sorted(blocks.items()):lines.append('|'.join([e,str(c),str(n),','.join(block),str(count)]))
model='\n'.join(lines)+'\n'
dest=base/'generator/16-Jungle-Fruit/src/main/resources'
dest.mkdir(parents=True,exist_ok=True)
(dest/'jf16-column-model.tsv').write_text(model)
report={'schemaVersion':1,'gameId':16,'status':'EMPIRICAL_MODEL_CANDIDATE_PENDING_GENERATION_VALIDATION','source':str(source.relative_to(base)),'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'trainingCompleteRounds':len(rs)-len(hold),'holdoutCompleteRounds':len(hold),'holdoutRoundIds':sorted(hold),'holdoutSelection':'lowest 100 SHA256(jf16-holdout-v1:roundOrdinal); fixed before distribution extraction','entries':dict(entryCounts),'observedTrainingMaxima':dict(maximum),'modelSha256':hashlib.sha256(model.encode()).hexdigest(),'policy':'Sample observed column blocks by entry, column and exact replacement length, weighted by counts; keep tumble survivors; reject whole candidates violating derived rules or observed special caps. No independent cell RNG, winner injection, constructed loss, or complete-Round replay. This is an empirical approximation, not original server probabilities.','blockDistributions':[{'entry':e,'column':c,'replacementLength':n,'symbols':list(block),'samples':count,'denominator':denoms[e,c,n],'percent':count*100/denoms[e,c,n]} for (e,c,n,block),count in sorted(blocks.items())],'slotSymbolDistributions':[{'entry':e,'column':c,'replacementLength':n,'row':row,'samples':dict(counts),'denominator':sum(counts.values()),'percent':{s:v*100/sum(counts.values()) for s,v in sorted(counts.items())}} for (e,c,n,row),counts in sorted(slotSymbols.items())],'gaps':['No complete exclusive ordinary WIN with small_game_type=0 observed in source; do not fabricate that mode.','Newer archive includes interrupted sequences and is excluded from this initial training model pending audit.']}
rp=base/'reports/16-Jungle-Fruit/distribution-model.json'
rp.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
hp=base/'fixtures/16-Jungle-Fruit/model-holdout-100.tsv';hp.write_text('\n'.join(holdlines)+'\n')
assert (dest/'jf16-column-model.tsv').read_text()==model
print(json.dumps({'model':str(dest/'jf16-column-model.tsv'),'sha256':report['modelSha256'],'blocks':len(blocks),'trainingRounds':len(rs)-len(hold),'holdoutRounds':len(hold),'entries':entryCounts,'trainingMaxima':maximum,'report':str(rp),'holdout':str(hp)}))

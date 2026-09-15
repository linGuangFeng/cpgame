#!/usr/bin/env python3
"""Offline build for game 2300 only. Requires a JDK and already-cached JUnit jars.
Does not modify other games, the Maven cache, the frontend, or Redis.
"""
import argparse,hashlib,json,os,pathlib,shutil,subprocess,time,zipfile
parser=argparse.ArgumentParser()
parser.add_argument('--java-home',default=os.environ.get('JAVA_HOME'))
parser.add_argument('--maven-cache',default=str(pathlib.Path.home()/'.m2/repository'))
args=parser.parse_args()
api=pathlib.Path(__file__).resolve().parent
base=api.parent.parent
gen=base/'generator/2300-Monster-Slayer'
reports=base/'reports/2300-Monster-Slayer'
java=lambda name:str(pathlib.Path(args.java_home)/'bin'/name) if args.java_home else shutil.which(name)
if not all(java(name) for name in ('java','javac','jar')):raise SystemExit('JDK tools unavailable')
g=gen/'target/repair-20260908/classes'
a=api/'target/repair-20260908/classes'
t=api/'target/repair-20260908/test-classes'
for p in (g,a,t):p.mkdir(parents=True,exist_ok=True)
def files(path,suffix):
    command=subprocess.run(['rg','--files',str(path)],capture_output=True,text=True,check=True,timeout=10)
    return [p for p in command.stdout.splitlines() if p.endswith(suffix)]
logs=[]
def run(label,command,timeout=60,env=None,expected=0):
    start=time.monotonic()
    result=subprocess.run(list(map(str,command)),capture_output=True,text=True,timeout=timeout,env=env,cwd='/tmp' if os.name!='nt' else str(api))
    item={'phase':label,'exitCode':result.returncode,'elapsedMs':round((time.monotonic()-start)*1000,2),'output':result.stdout+result.stderr}
    logs.append(item);print(json.dumps(item),flush=True)
    if result.returncode!=expected:raise SystemExit(result.returncode or 1)
    return result
jars=[]
for root,version in [('org/junit/jupiter','5.11.4'),('org/junit/platform','1.11.4'),('org/opentest4j','1.3.0'),('org/apiguardian','1.1.2')]:
    jars.extend(p for p in files(pathlib.Path(args.maven_cache)/root,'.jar')
                if '/'+version+'/' in p.replace(os.sep,'/') and not p.endswith('-sources.jar'))
original_api=api/'target/repair-20260908/controller-before-repair.jar'
original_loader=gen/'target/repair-20260908/loader-before-repair.jar'
for current,backup in [(api/'dist/controller.jar',original_api),(gen/'dist/monster-slayer-redis-loader.jar',original_loader)]:
    if not backup.exists():shutil.copy2(current,backup)
cp=os.pathsep.join(map(str,[g,a,original_api]+jars))
run('compile-generator',[java('javac'),'--release','17','-d',g]+files(gen/'src/main/java','.java'))
run('compile-controller',[java('javac'),'--release','17','-cp',cp,'-d',a]+files(api/'src/main/java','.java'))
run('compile-tests',[java('javac'),'--release','17','-cp',cp,'-d',t]+files(gen/'src/test/java','.java')+files(api/'src/test/java','.java'))
testcp=str(t)+os.pathsep+cp
run('junit-8-tests',[java('java'),'-DmonsterSlayer.evidence='+str(reports/'canonical-round-evidence-20260908.json'),'-cp',testcp,'com.cpgame.monsterslayer.server.JunitRepairLauncher'])
run('independent-oracle-10000-normal-rounds',[java('java'),'-cp',testcp,'com.cpgame.monsterslayer.server.RepairRegression',reports/'canonical-round-evidence-20260908.json',reports/'ordinary-repair-validation-20260908.json'])
env=dict(os.environ);env.pop('CPGAME_DEMO_PORT',None);env['PORT']='51731'
result=run('PORT-environment',[java('java'),'-cp',testcp,'com.cpgame.monsterslayer.server.ControllerContractTest'],env=env)
assert 'INJECTED_PORT=51731' in result.stdout
loader=gen/'target/repair-20260908/monster-slayer-redis-loader.jar'
run('package-loader',[java('jar'),'--create','--file',loader,'--main-class','com.cpgame.monsterslayer.loader.RedisLoader','-C',g,'.'])
controller=api/'target/repair-20260908/controller.jar'
with zipfile.ZipFile(original_api) as old,zipfile.ZipFile(controller,'w',zipfile.ZIP_DEFLATED) as out:
    for info in old.infolist():
        if info.filename.startswith('com/cpgame/monsterslayer/') or info.filename.upper().endswith(('.SF','.RSA','.DSA')):continue
        out.writestr(info,old.read(info.filename))
    for root in (a,g):
        for file in files(root,'.class'):
            name=os.path.relpath(file,root).replace(os.sep,'/')
            if 'RepairRegression' in name:continue
            if root==g and not(name.startswith('com/cpgame/monsterslayer/core/') or name=='com/cpgame/monsterslayer/redis/RedisKeyContract.class'):continue
            out.write(file,name)
for staged,destination in [(loader,gen/'dist/monster-slayer-redis-loader.jar'),(controller,api/'dist/controller.jar')]:
    shutil.copy2(staged,destination)
result=run('packaged-port-rejection',[java('java'),'-jar',api/'dist/controller.jar','--port','49999'],expected=1)
assert 'port must be 50000-59999' in result.stderr
result=run('packaged-loader-preflight',[java('java'),'-jar',gen/'dist/monster-slayer-redis-loader.jar'],expected=1)
assert 'no Redis operation performed' in result.stderr
artifacts={str(p):{'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
           for p in (api/'dist/controller.jar',gen/'dist/monster-slayer-redis-loader.jar')}
rules=json.loads((base/'protocol/2300-Monster-Slayer/rules-core.json').read_text())
rules_hash=hashlib.sha256(json.dumps(rules,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()).hexdigest()
report={'gameId':2300,'buildPass':True,'readyForAcceptance':False,'rulesVersion':rules['rulesVersion'],'rulesHash':rules_hash,
        'jdkReleaseTarget':17,'junitTests':8,'junitFailures':0,'PORTEnvironmentVerified':True,'packagedEarlyFailureGuards':True,
        'artifacts':artifacts,'phases':logs,'realRedisWritten':False,'browserAcceptance':'BLOCKED_ORIGINAL_ENTRY_PROVENANCE'}
(reports/'java-build-repair-20260908.json').write_text(json.dumps(report,indent=2))
print(json.dumps({'status':'BUILD_VERIFIED','rulesHash':rules_hash,'artifacts':artifacts}),flush=True)

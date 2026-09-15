#!/usr/bin/env python3
"""Build only game 16 with JDK 21+, without Maven or runtime fixtures."""
from pathlib import Path
import os, subprocess, shutil, hashlib, json
base=Path(__file__).resolve().parents[2]
game=base/'generator/16-Jungle-Fruit'
server=base/'server-api/16-Jungle-Fruit'
java_home=os.environ.get('JAVA_HOME')
def tool(name):
    return str(Path(java_home)/'bin'/name) if java_home else shutil.which(name)
gclasses=game/'target/reproducible-main'
sclasses=server/'target/reproducible-api'
gclasses.mkdir(parents=True,exist_ok=True)
sclasses.mkdir(parents=True,exist_ok=True)
sources=sorted((game/'src/main/java/com/cpgame/batcha/g16').glob('*.java'))
subprocess.run([tool('javac'),'--release','21','-d',str(gclasses)]+list(map(str,sources)),check=True)
shutil.copyfile(game/'src/main/resources/jf16-column-model.tsv',gclasses/'jf16-column-model.tsv')
subprocess.run([tool('javac'),'--release','21','-cp',str(gclasses),'-d',str(sclasses),str(server/'src/main/java/com/cpgame/junglefruit/api/ServerMain.java')],check=True)
artifacts=[]
for root,name,main,args in [
 (game,'jungle-fruit-loader.jar','com.cpgame.batcha.g16.LoaderMain',['-C',str(gclasses),'.']),
 (server,'controller.jar','com.cpgame.junglefruit.api.ServerMain',['-C',str(sclasses),'.','-C',str(gclasses),'com'])]:
    target=root/'target'/name
    subprocess.run([tool('jar'),'--create','--file',str(target),'--main-class',main]+args,check=True)
    dest=root/'dist'/name
    os.replace(target,dest)
    artifacts.append({'path':str(dest),'sha256':hashlib.sha256(dest.read_bytes()).hexdigest()})
print(json.dumps(artifacts,indent=2))

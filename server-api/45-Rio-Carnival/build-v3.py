from pathlib import Path
import zipfile,subprocess,shutil,io
base=Path(__file__).resolve().parent
old=base/'dist/rio-carnival-server.jar'
generator=base.parent.parent/'generator/45-Rio-Carnival/dist/rio-carnival-loader.jar'
work=base/'app/target/v3';work.mkdir(parents=True,exist_ok=True)
classes=work/'classes';classes.mkdir(exist_ok=True)
libs=work/'libs';libs.mkdir(exist_ok=True)
cp=[str(generator)];sources=[];class_names=[]
with zipfile.ZipFile(old) as z:
    for n in z.namelist():
        if n.startswith('BOOT-INF/lib/') and n.endswith('.jar'):
            path=libs/Path(n).name
            if not path.exists():path.write_bytes(z.read(n))
            cp.append(str(path))
        if n.startswith('BOOT-INF/classes/com/hd/cpgame/') and n.endswith('.class'):class_names.append(n[len('BOOT-INF/classes/'):])
        if n.startswith('BOOT-INF/classes/com/hd/cpgame/') and n.endswith('.class') and '$' not in n:
            sources.append(str(base/'src/main/java'/ (n[len('BOOT-INF/classes/'):-6]+'.java')))
sources.append(str(base/'src/main/java/com/hd/cpgame/riocarnival/server/service/RedisRoundPool.java'))
subprocess.run(['/usr/bin/javac','--release','8','-encoding','UTF-8','-cp',':'.join(cp),'-d',str(classes)]+sorted(set(sources)),check=True)
core=io.BytesIO()
with zipfile.ZipFile(generator) as z,zipfile.ZipFile(core,'w',zipfile.ZIP_DEFLATED) as out:
    for item in z.infolist():
        if item.filename.startswith('com/hd/cpgame/') or item.filename=='rio-dealing-model.json':out.writestr(item,z.read(item))
tmp=work/'controller.jar'
with zipfile.ZipFile(old) as z,zipfile.ZipFile(tmp,'w',zipfile.ZIP_DEFLATED) as out:
    for item in z.infolist():
        if item.filename.startswith('BOOT-INF/classes/com/hd/cpgame/') and not item.is_dir():continue
        if item.filename.startswith('BOOT-INF/lib/rio-carnival-generator'):
            out.writestr(item,core.getvalue(),compress_type=zipfile.ZIP_STORED)
        else:out.writestr(item,z.read(item))
    for n in sorted(set(class_names+['com/hd/cpgame/riocarnival/server/service/RedisRoundPool.class','com/hd/cpgame/riocarnival/server/service/RedisRoundPool$PoolException.class'])):
        path=classes/n
        if path.is_file():out.write(path,'BOOT-INF/classes/'+n)
shutil.copyfile(tmp,base/'dist/controller.jar')
print('BUILT',base/'dist/controller.jar')

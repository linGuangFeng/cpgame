from pathlib import Path
import zipfile,subprocess,shutil,os
base=Path(__file__).resolve().parent
jar=base/'dist/rio-carnival-loader.jar'
work=base/'target/v3';work.mkdir(parents=True,exist_ok=True)
classes=work/'classes';classes.mkdir(exist_ok=True)
with zipfile.ZipFile(jar) as z:
    class_names=[n for n in z.namelist() if n.startswith('com/hd/cpgame/') and n.endswith('.class')]
    names=sorted({n[:-6]+'.java' for n in z.namelist() if n.startswith('com/hd/cpgame/') and n.endswith('.class') and '$' not in n})
for n in ['com/hd/cpgame/riocarnival/core/DealingModel.java','com/hd/cpgame/riocarnival/core/CorePayout.java']:
    if n not in names:names.append(n)
sources=[str(base/'src/main/java'/n) for n in names]
subprocess.run(['/usr/bin/javac','--release','8','-encoding','UTF-8','-cp',str(jar),'-d',str(classes)]+sources,check=True)
tmp=work/'rio-carnival-loader.jar'
with zipfile.ZipFile(jar) as src,zipfile.ZipFile(tmp,'w',zipfile.ZIP_DEFLATED) as out:
    for item in src.infolist():
        if not item.filename.startswith('com/hd/cpgame/') and item.filename!='rio-dealing-model.json':out.writestr(item,src.read(item))
    for n in sorted(set(class_names+[n[:-5]+'.class' for n in names])):
        c=classes/n
        if c.is_file():out.write(c,n)
    out.write(base/'src/main/resources/rio-dealing-model.json','rio-dealing-model.json')
shutil.copyfile(tmp,jar)
print('BUILT',jar)

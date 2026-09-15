import http from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import path from 'node:path';

const root = 'D:/work/hd/cpgame/publish/2300-monster-slayer';
const port = Number(process.argv[2] ?? 52301);
const types = {'.html':'text/html; charset=utf-8','.js':'application/javascript; charset=utf-8','.json':'application/json; charset=utf-8','.css':'text/css','.png':'image/png','.webp':'image/webp','.mp3':'audio/mpeg','.wasm':'application/wasm','.bin':'application/octet-stream'};
http.createServer(async (request,response)=>{
  try {
    const pathname = decodeURIComponent(new URL(request.url,'http://localhost').pathname);
    const relative = pathname === '/' ? 'index.html' : pathname.replace(/^\/+/, '');
    const file = path.resolve(root, relative);
    if (!file.startsWith(path.resolve(root) + path.sep) && file !== path.resolve(root,'index.html')) throw new Error('outside root');
    if (!(await stat(file)).isFile()) throw new Error('not file');
    const body = await readFile(file);
    response.writeHead(200,{'content-type':types[path.extname(file).toLowerCase()]??'application/octet-stream','cache-control':'no-store'});
    response.end(request.method === 'HEAD' ? undefined : body);
  } catch {
    response.writeHead(404,{'content-type':'text/plain'});response.end('not found');
  }
}).listen(port,'127.0.0.1',()=>process.stdout.write(`STATIC_READY ${port}\n`));

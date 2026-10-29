import http from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { projectSession, mobileInput } from './projection.ts';
export { projectSession, mobileInput, questionData } from './projection.ts';

/** No general proxy: the mobile capability can only read learning records and submit learning inputs. */
export async function startMobileBridge({ desktopUrl, desktopSecret, token, port = 47832 }: {
  desktopUrl: string; desktopSecret: string; token: string; port?: number;
}) {
  const desktop = new URL(desktopUrl);
  if (desktop.hostname !== '127.0.0.1' || desktop.protocol !== 'http:' || desktop.pathname !== '/') throw new Error('Expected local desktop server');
  if (token.length < 40 || !desktopSecret) throw new Error('Missing private capabilities');
  const inflight = new Set<string>();
  const requests = new Map<string, {body: string; promise: Promise<any>}>();
  async function upstream(path: string, body?: unknown) {
    const response = await fetch(new URL(path, desktop), { method: body === undefined ? 'GET' : 'POST',
      headers: { 'Content-Type': 'application/json', 'x-learnharness-desktop': desktopSecret },
      redirect: 'error', signal: AbortSignal.timeout(180000), body: body === undefined ? undefined : JSON.stringify(body) });
    if (!response.ok) {
      const error = new Error(response.status === 503 ? 'Configure the model on your PC, then retry.' : response.status === 409 ? 'A PC turn is running. Wait and refresh.' : `Desktop request failed (${response.status}). Open LearnHarness on your PC.`);
      (error as any).status = response.status; throw error;
    }
    if (response.headers.get('content-type')?.includes('text/event-stream')) {
      // Drain the real desktop turn; its canonical record owns persistence and errors.
      const reader = response.body!.getReader();
      while (!(await reader.read()).done) { /* no transcript copies or provider credentials */ }
      return {};
    }
    return response.json();
  }
  const server = http.createServer(async (req, res) => {
    const reply = (status: number, value: any) => {
      res.writeHead(status, {'Content-Type': 'application/json', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff'});
      res.end(JSON.stringify(value));
    };
    const auth = Buffer.from(req.headers.authorization ?? '');
    const expected = Buffer.from(`Bearer ${token}`);
    if (req.headers.origin || auth.length !== expected.length || !timingSafeEqual(auth, expected)) return reply(401, {error: 'Pair this phone using the PC connection token.'});
    try {
      const pathname = new URL(req.url ?? '/', 'http://127.0.0.1').pathname;
      if (req.method === 'GET' && pathname === '/v1/status') {
        await upstream('/api/chats'); return reply(200, {version: 1, name: 'LearnHarness PC'});
      }
      if (req.method === 'GET' && pathname === '/v1/sessions') {
        const result: any = await upstream('/api/chats');
        return reply(200, {sessions: result.chats.map((s: any) => ({id:s.id, title:s.title, mode:s.kind === 'practice' ? 'practice':'learn', createdAt:Date.parse(s.createdAt)||0, busy:s.busy === true}))});
      }
      if (req.method === 'GET' && pathname === '/v1/graph') {
        const result: any = await upstream('/api/graph');
        const nodes = Array.isArray(result.nodes) ? result.nodes : Object.values(result.nodes ?? {});
        return reply(200, {nodes: nodes.map((n: any) => ({id:n.id, title:n.name ?? n.label ?? n.id, statement:n.statement ?? '', prerequisites:(result.edges ?? []).filter((e: any) => e.to === n.id).map((e:any)=>e.from)}))});
      }
      const match = pathname.match(/^\/v1\/sessions\/([a-zA-Z0-9_-]+)(\/turn)?$/);
      if (req.method === 'GET' && match && !match[2]) return reply(200, projectSession(await upstream(`/api/chats/${match[1]}`)));
      if (req.method !== 'POST' || !(pathname === '/v1/sessions' || match?.[2])) return reply(404, {error:'Unknown mobile route'});
      let raw = '';
      for await (const chunk of req) { raw += chunk.toString(); if (Buffer.byteLength(raw) > 64000) return reply(413, {error:'Request too large'}); }
      const body = JSON.parse(raw);
      const requestId = req.headers['x-request-id'];
      if (typeof requestId !== 'string' || !/^[a-zA-Z0-9-]{16,80}$/.test(requestId)) return reply(400, {error:'Missing request ID'});
      const identity = pathname + ':' + requestId;
      const cached = requests.get(identity);
      if (cached) {
        if (cached.body !== raw) return reply(409, {error:'Request ID was already used'});
        return reply(200, await cached.promise);
      }
      if (requests.size >= 10000) return reply(503, {error:'Restart the phone connection on your PC'});
      const run = async () => {
        if (pathname === '/v1/sessions') {
          if (typeof body.title !== 'string' || !body.title.trim() || body.title.length > 500) throw new Error('Enter a topic up to 500 characters');
          return upstream('/api/chats', {title:body.title, kind:body.mode === 'practice' ? 'practice':'teach'});
        }
        const id = match?.[1];
        if (!id) throw new Error('Missing session ID');
        if (inflight.has(id)) throw Object.assign(new Error('This session is busy. Refresh before sending again.'), {status:409});
        inflight.add(id);
        try {
          const record: any = await upstream(`/api/chats/${id}`);
          if (record.turnInFlight) throw Object.assign(new Error('A PC turn is running. Wait and refresh.'), {status:409});
          const input = mobileInput(record, body);
          await upstream('/api/chat', {chatId:id, input});
          return projectSession(await upstream(`/api/chats/${id}`));
        } finally { inflight.delete(id); }
      };
      const promise = run();
      requests.set(identity, {body: raw, promise});
      return reply(200, await promise);
    } catch (error: any) { return reply(error.status ?? 400, {error:error.message ?? 'Unable to reach your PC'}); }
  });
  server.requestTimeout = 200000;
  server.headersTimeout = 10000;
  await new Promise<void>((resolve,reject)=>{server.once('error',reject);server.listen(port,'127.0.0.1',resolve);});
  return server;
}

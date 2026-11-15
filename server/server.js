// 앞만봐 중계 서버
// 차량 앱(Pleos 에뮬레이터)과 좌석별 태블릿 앱을 WebSocket으로 이어 준다.
// - 차량 → 태블릿: {type:"cmd", seat:"left"|"right"|"all", action, ...}
// - 태블릿 → 차량: {type:"status", seat, battery, playing, title, ...}
// - 서버 → 차량: {type:"presence", seats:{left:bool, right:bool}}
// 태블릿 화면(public/tablet.html)도 같은 포트에서 제공한다.

const http = require("http");
const fs = require("fs");
const path = require("path");
const os = require("os");
const { WebSocketServer } = require("ws");
const { spawn } = require("child_process");

const PORT = Number(process.env.PORT || 8765);
const PUBLIC = path.join(__dirname, "public");
// 시연 영상 자막용 이벤트 기록 (ms 단위 시각)
const EVENTS = path.join(__dirname, "events.jsonl");
function record(ev) { fs.appendFile(EVENTS, JSON.stringify({ ts: Date.now(), ...ev }) + "\n", () => {}); }
const TYPES = { ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml", ".png": "image/png" };

const server = http.createServer((req, res) => {
  const url = new URL(req.url, "http://x");
  let file = url.pathname === "/" ? "/tablet.html" : url.pathname;
  file = path.normalize(file).replace(/^(\.\.[/\\])+/, "");
  const full = path.join(PUBLIC, file);
  if (!full.startsWith(PUBLIC) || !fs.existsSync(full)) { res.writeHead(404); return res.end("not found"); }
  res.writeHead(200, { "Content-Type": TYPES[path.extname(full)] || "application/octet-stream", "Cache-Control": "no-store" });
  fs.createReadStream(full).pipe(res);
});

const wss = new WebSocketServer({ server });
const clients = new Set(); // ws with ws.role, ws.seat

function send(ws, msg) { if (ws.readyState === 1) ws.send(JSON.stringify(msg)); }
function cars() { return [...clients].filter(c => c.role === "car"); }
function tablets(seat) { return [...clients].filter(c => c.role === "tablet" && (seat === "all" || c.seat === seat)); }
function presence() {
  const seats = { left: tablets("left").length > 0, right: tablets("right").length > 0 };
  cars().forEach(c => send(c, { type: "presence", seats }));
}
function log(...a) { console.log(new Date().toLocaleTimeString("ko-KR"), ...a); }

wss.on("connection", (ws, req) => {
  clients.add(ws);
  ws.on("message", raw => {
    let msg; try { msg = JSON.parse(raw); } catch { return; }
    if (msg.type === "hello") {
      ws.role = msg.role; ws.seat = msg.seat;
      log(`연결: ${msg.role}${msg.seat ? " / " + msg.seat : ""} (${req.socket.remoteAddress})`);
      presence();
      if (ws.role === "tablet") cars().forEach(c => send(c, { type: "hello_tablet", seat: ws.seat }));
      return;
    }
    if (msg.type === "cmd" && ws.role === "car") {
      const targets = tablets(msg.seat || "all");
      log(`명령 → ${msg.seat}: ${msg.action}${msg.text ? " \"" + msg.text + "\"" : ""} (${targets.length}대)`);
      targets.forEach(t => send(t, msg));
      record({ kind: "cmd", seat: msg.seat, action: msg.action, sticker: !!msg.sticker, text: msg.text || "", delta: msg.delta || 0 });
      return;
    }
    // 차량 음성 엔진을 쓸 수 없는 에뮬레이터용 대체 음성: 맥북 스피커로 말한다 (macOS say, 한국어 Yuna)
    if (msg.type === "speak" && ws.role === "car" && typeof msg.text === "string") {
      log(`음성(대체): ${msg.text}`);
      if (process.platform === "darwin") spawn("say", ["-v", process.env.VOICE || "Yuna", msg.text.slice(0, 200)]);
      return;
    }
    if (msg.type === "event" && ws.role === "car") {
      record({ kind: "car", event: msg.kind, text: msg.text || "" });
      return;
    }
    if (msg.type === "status" && ws.role === "tablet") {
      cars().forEach(c => send(c, { ...msg, seat: ws.seat }));
    }
  });
  ws.on("close", () => { clients.delete(ws); if (ws.role) log(`끊김: ${ws.role}${ws.seat ? " / " + ws.seat : ""}`); presence(); });
});

server.listen(PORT, "0.0.0.0", () => {
  const ips = Object.values(os.networkInterfaces()).flat().filter(i => i && i.family === "IPv4" && !i.internal).map(i => i.address);
  log(`앞만봐 중계 서버 시작: 포트 ${PORT}`);
  ips.forEach(ip => log(`  태블릿 화면: http://${ip}:${PORT}/tablet.html?seat=left`));
  log(`  차량 에뮬레이터 접속 주소: ws://10.0.2.2:${PORT}`);
});

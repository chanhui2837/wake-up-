// 깨워알람 서버 — Express + MongoDB(Mongoose) + JWT 로그인
const express = require("express");
const mongoose = require("mongoose");
const bcrypt = require("bcryptjs");
const jwt = require("jsonwebtoken");
const cors = require("cors");
const path = require("path");

const PORT = process.env.PORT || 3000;
const MONGODB_URI = process.env.MONGODB_URI;
const JWT_SECRET = process.env.JWT_SECRET || "dev-secret-change-me";
const DB_NAME = process.env.DB_NAME || "wakeup";

const app = express();
app.use(cors()); // 안드로이드 앱(WebView)에서도 호출하므로 허용
app.use(express.json({ limit: "200kb" }));

/* ---------- 모델 ---------- */
const User = mongoose.model("User", new mongoose.Schema({
  username: { type: String, required: true, unique: true, trim: true },
  passwordHash: { type: String, required: true },
}, { timestamps: true }));

const MissionSchema = new mongoose.Schema({
  type: { type: String, enum: ["math", "tiles", "order", "squat", "walk", "shake"], required: true },
  count: { type: Number, min: 1, max: 500, default: 3 },
  level: { type: Number, min: 1, max: 4, default: 1 },
}, { _id: false });

const Alarm = mongoose.model("Alarm", new mongoose.Schema({
  userId: { type: mongoose.Schema.Types.ObjectId, ref: "User", index: true, required: true },
  clientId: { type: String, required: true },
  time: { type: String, match: /^\d{2}:\d{2}$/, required: true },
  label: { type: String, maxlength: 30, default: "" },
  days: [{ type: Number, min: 0, max: 6 }],
  limit: { type: Number, min: 30, max: 1800, default: 120 },
  on: { type: Boolean, default: true },
  missions: [MissionSchema],
  sound: { type: String, default: "system", maxlength: 60 },
  volume: { type: Number, min: 10, max: 100, default: 80 },
  order: Number,
}, { timestamps: true }));

// 사용자가 올린 알람음 (MP3 등, 최대 8MB)
const Sound = mongoose.model("Sound", new mongoose.Schema({
  userId: { type: mongoose.Schema.Types.ObjectId, ref: "User", index: true, required: true },
  name: { type: String, maxlength: 60 },
  mime: String,
  size: Number,
  data: Buffer,
}, { timestamps: true }));
const MAX_SOUND = 8 * 1024 * 1024, MAX_SOUNDS_PER_USER = 10;

/* ---------- 유틸 ---------- */
const dbReady = () => mongoose.connection.readyState === 1;
function needDb(req, res, next) {
  if (!dbReady()) return res.status(503).json({ error: "DB에 연결되지 않았어요. 잠시 후 다시 시도해 주세요." });
  next();
}
function auth(req, res, next) {
  const h = req.headers.authorization || "";
  const token = h.startsWith("Bearer ") ? h.slice(7) : null;
  if (!token) return res.status(401).json({ error: "로그인이 필요해요." });
  try { req.user = jwt.verify(token, JWT_SECRET); next(); }
  catch { return res.status(401).json({ error: "로그인이 만료됐어요. 다시 로그인해 주세요." }); }
}
const sign = (u) => jwt.sign({ id: String(u._id), username: u.username }, JWT_SECRET, { expiresIn: "180d" });
const toClient = (a) => ({ id: a.clientId, time: a.time, label: a.label, days: a.days, limit: a.limit, on: a.on, missions: a.missions, sound: a.sound || "system", volume: a.volume || 80 });

/* ---------- 인증 ---------- */
app.post("/api/auth/register", needDb, async (req, res) => {
  const username = String(req.body.username || "").trim();
  const password = String(req.body.password || "");
  if (!/^[a-zA-Z0-9가-힣_]{3,20}$/.test(username)) return res.status(400).json({ error: "아이디는 3~20자 (한글/영문/숫자/_) 로 해 주세요." });
  if (password.length < 4) return res.status(400).json({ error: "비밀번호는 4자 이상이어야 해요." });
  if (await User.exists({ username })) return res.status(409).json({ error: "이미 사용 중인 아이디예요." });
  const user = await User.create({ username, passwordHash: await bcrypt.hash(password, 10) });
  res.json({ token: sign(user), username: user.username });
});

app.post("/api/auth/login", needDb, async (req, res) => {
  const username = String(req.body.username || "").trim();
  const user = await User.findOne({ username });
  if (!user || !(await bcrypt.compare(String(req.body.password || ""), user.passwordHash)))
    return res.status(401).json({ error: "아이디 또는 비밀번호가 틀렸어요." });
  res.json({ token: sign(user), username: user.username });
});

app.get("/api/me", auth, (req, res) => res.json({ username: req.user.username }));

/* ---------- 알람 ---------- */
app.get("/api/alarms", auth, needDb, async (req, res) => {
  const list = await Alarm.find({ userId: req.user.id }).sort({ order: 1, time: 1 });
  res.json(list.map(toClient));
});

// 전체 목록을 한 번에 저장 (앱이 가진 목록이 기준)
app.put("/api/alarms", auth, needDb, async (req, res) => {
  const arr = Array.isArray(req.body) ? req.body.slice(0, 50) : null;
  if (!arr) return res.status(400).json({ error: "잘못된 형식이에요." });
  const docs = arr.map((a, i) => ({
    userId: req.user.id,
    clientId: String(a.id || "a" + Date.now() + i).slice(0, 40),
    time: String(a.time || "07:00"),
    label: String(a.label || "").slice(0, 30),
    days: (Array.isArray(a.days) ? a.days : []).map(Number).filter((d) => d >= 0 && d <= 6),
    limit: Math.min(1800, Math.max(30, Number(a.limit) || 120)),
    on: !!a.on,
    missions: (Array.isArray(a.missions) ? a.missions : []).slice(0, 20).map((m) => ({
      type: m.type, count: Math.max(1, Math.min(500, Number(m.count) || 1)), level: Math.max(1, Math.min(4, Number(m.level) || 1)),
    })),
    sound: String(a.sound || "system").slice(0, 60),
    volume: Math.min(100, Math.max(10, Number(a.volume) || 80)),
    order: i,
  }));
  try {
    for (const d of docs) await new Alarm(d).validate();
  } catch (e) { return res.status(400).json({ error: "알람 데이터가 올바르지 않아요." }); }
  await Alarm.deleteMany({ userId: req.user.id });
  if (docs.length) await Alarm.insertMany(docs);
  res.json({ ok: true, count: docs.length });
});

/* ---------- 알람음 ---------- */
app.get("/api/sounds", auth, needDb, async (req, res) => {
  const list = await Sound.find({ userId: req.user.id }, { data: 0 }).sort({ createdAt: 1 });
  res.json(list.map((x) => ({ id: "snd_" + x._id, name: x.name, size: x.size, mime: x.mime })));
});

app.post("/api/sounds", auth, needDb, express.raw({ type: () => true, limit: MAX_SOUND }), async (req, res) => {
  if (!req.body || !req.body.length) return res.status(400).json({ error: "파일이 비어 있어요." });
  const mime = String(req.headers["content-type"] || "audio/mpeg");
  if (!mime.startsWith("audio/")) return res.status(400).json({ error: "음악 파일(mp3 등)만 올릴 수 있어요." });
  if ((await Sound.countDocuments({ userId: req.user.id })) >= MAX_SOUNDS_PER_USER)
    return res.status(400).json({ error: `알람음은 최대 ${MAX_SOUNDS_PER_USER}개까지 올릴 수 있어요.` });
  const name = String(req.query.name || "내 알람음").slice(0, 60);
  const s = await Sound.create({ userId: req.user.id, name, mime, size: req.body.length, data: req.body });
  res.json({ id: "snd_" + s._id, name: s.name, size: s.size, mime: s.mime });
});

const soundQuery = (req) => {
  const raw = String(req.params.id || "").replace(/^snd_/, "");
  return mongoose.isValidObjectId(raw) ? { _id: raw, userId: req.user.id } : null;
};
app.get("/api/sounds/:id", auth, needDb, async (req, res) => {
  const q = soundQuery(req); if (!q) return res.status(404).end();
  const s = await Sound.findOne(q); if (!s) return res.status(404).end();
  res.set("Content-Type", s.mime || "audio/mpeg").set("Cache-Control", "private, max-age=31536000").send(s.data);
});
app.delete("/api/sounds/:id", auth, needDb, async (req, res) => {
  const q = soundQuery(req); if (!q) return res.status(404).end();
  await Sound.deleteOne(q);
  await Alarm.updateMany({ userId: req.user.id, sound: req.params.id }, { $set: { sound: "system" } });
  res.json({ ok: true });
});
app.use((err, req, res, next) => {
  if (err && err.type === "entity.too.large") return res.status(413).json({ error: "파일이 너무 커요 (최대 8MB)." });
  next(err);
});

app.get("/healthz", (req, res) => res.json({ ok: true, db: dbReady() }));

/* ---------- 정적 파일 ---------- */
app.use(express.static(path.join(__dirname, "public"), { extensions: ["html"] }));
app.get("*", (req, res) => res.sendFile(path.join(__dirname, "public", "index.html")));

app.listen(PORT, () => console.log(`깨워알람 서버 실행: ${PORT}`));

if (MONGODB_URI) {
  mongoose.connect(MONGODB_URI, { dbName: DB_NAME })
    .then(() => console.log("MongoDB 연결됨"))
    .catch((e) => console.error("MongoDB 연결 실패:", e.message));
} else {
  console.warn("MONGODB_URI 환경변수가 없어요. 로그인/저장이 동작하지 않습니다.");
}

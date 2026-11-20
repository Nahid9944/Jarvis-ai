/**
 * NAHID AI — Independent Firebase-Free Knowledge API Server (Node.js)
 * Zero Firebase SDKs. Implements bearer token auth, sliding-window rate limiting,
 * SSRF/private-IP guard, idempotent ingestion, search, research job queue, and audit logs.
 */
const http = require("node:http");
const crypto = require("node:crypto");
const { URL } = require("node:url");

const MAX_BODY_BYTES = 1024 * 1024; // 1 MB

function isBlockedPrivateHost(hostname) {
  const host = String(hostname || "").trim().toLowerCase().replace(/^\[|\]$/g, "");
  if (!host) return true;
  if (
    host === "localhost" ||
    host === "::1" ||
    host === "0.0.0.0" ||
    host.endsWith(".local") ||
    host.endsWith(".internal")
  ) {
    return true;
  }
  if (host === "169.254.169.254" || host.startsWith("169.254.")) return true;
  if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.")) return true;
  if (host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80")) return true;
  const m172 = /^172\.(\d{1,3})\./.exec(host);
  if (m172) {
    const second = Number(m172[1]);
    if (second >= 16 && second <= 31) return true;
  }
  return false;
}

function validatePublicUrl(rawUrl) {
  try {
    const parsed = new URL(String(rawUrl || "").trim());
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
      return { valid: false, reason: "UNSUPPORTED_SCHEME" };
    }
    if (isBlockedPrivateHost(parsed.hostname)) {
      return { valid: false, reason: "SSRF_PRIVATE_HOST_BLOCKED" };
    }
    return { valid: true, url: parsed.toString(), domain: parsed.hostname.replace(/^www\./, "") };
  } catch {
    return { valid: false, reason: "INVALID_URL" };
  }
}

function createKnowledgeServer(options = {}) {
  const apiToken = options.apiToken ?? process.env.KNOWLEDGE_API_TOKEN ?? "";
  const rateLimitPerMin = Number(options.rateLimitPerMin ?? process.env.RATE_LIMIT_PER_MIN ?? 60);

  const documents = new Map();
  const idempotencyKeys = new Map();
  const researchJobs = new Map();
  const feedbackList = [];
  const auditLogs = [];
  const rateBuckets = new Map();

  const sources = [
    { sourceId: "bd_portal", domain: "bangladesh.gov.bd", authorityWeight: 0.96, enabled: true },
    { sourceId: "bb_org", domain: "bb.org.bd", authorityWeight: 0.96, enabled: true },
    { sourceId: "pubmed", domain: "pubmed.ncbi.nlm.nih.gov", authorityWeight: 0.95, enabled: true },
    { sourceId: "wiki_bn", domain: "bn.wikipedia.org", authorityWeight: 0.88, enabled: true },
    { sourceId: "wiki_en", domain: "en.wikipedia.org", authorityWeight: 0.88, enabled: true },
    { sourceId: "android_dev", domain: "developer.android.com", authorityWeight: 0.94, enabled: true }
  ];

  function recordAudit(action, resourceId, statusCode, details = {}) {
    auditLogs.unshift({
      id: auditLogs.length + 1,
      action,
      resourceId,
      statusCode,
      details,
      timestamp: new Date().toISOString()
    });
    if (auditLogs.length > 500) auditLogs.pop();
  }

  function checkRateLimit(clientKey) {
    const now = Date.now();
    const windowStart = now - 60_000;
    const prev = (rateBuckets.get(clientKey) || []).filter((ts) => ts > windowStart);
    if (prev.length >= rateLimitPerMin) {
      rateBuckets.set(clientKey, prev);
      return false;
    }
    prev.push(now);
    rateBuckets.set(clientKey, prev);
    return true;
  }

  function sendJson(res, code, body) {
    const raw = JSON.stringify(body);
    res.writeHead(code, {
      "Content-Type": "application/json; charset=utf-8",
      "Content-Length": Buffer.byteLength(raw),
      "X-Content-Type-Options": "nosniff"
    });
    res.end(raw);
  }

  function readJsonBody(req) {
    return new Promise((resolve, reject) => {
      let size = 0;
      const chunks = [];
      req.on("data", (chunk) => {
        size += chunk.length;
        if (size > MAX_BODY_BYTES) {
          reject(new Error("PAYLOAD_TOO_LARGE"));
          req.destroy();
          return;
        }
        chunks.push(chunk);
      });
      req.on("end", () => {
        try {
          const text = Buffer.concat(chunks).toString("utf8");
          resolve(text ? JSON.parse(text) : {});
        } catch {
          reject(new Error("INVALID_JSON"));
        }
      });
      req.on("error", reject);
    });
  }

  const server = http.createServer(async (req, res) => {
    const reqUrl = new URL(req.url, "http://localhost");
    const pathname = reqUrl.pathname;

    if (req.method === "GET" && pathname === "/healthz") {
      return sendJson(res, 200, {
        ok: true,
        service: "nahid-knowledge-server",
        firebaseUsed: false,
        documentCount: documents.size,
        jobCount: researchJobs.size
      });
    }

    // Bearer Token Authentication when token is configured
    if (apiToken) {
      const auth = String(req.headers["authorization"] || "");
      const provided = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
      if (!provided || provided !== apiToken) {
        recordAudit("AUTH_REJECTED", pathname, 401);
        return sendJson(res, 401, { error: "UNAUTHORIZED" });
      }
    }

    const clientKey = req.headers["x-client-id"] || req.socket.remoteAddress || "default";
    if (!checkRateLimit(String(clientKey))) {
      recordAudit("RATE_LIMITED", pathname, 429);
      return sendJson(res, 429, { error: "RATE_LIMIT_EXCEEDED" });
    }

    try {
      if (req.method === "GET" && pathname === "/v1/knowledge/search") {
        const q = String(reqUrl.searchParams.get("q") || "").trim();
        if (!q) return sendJson(res, 400, { error: "QUERY_REQUIRED" });
        const tokens = q.toLowerCase().split(/\s+/).filter((t) => t.length >= 2);
        const matches = [];
        for (const doc of documents.values()) {
          const haystack = `${doc.query} ${doc.answerSummaryBn} ${(doc.citations || []).map((c) => `${c.title} ${c.snippet}`).join(" ")}`.toLowerCase();
          if (tokens.some((t) => haystack.includes(t))) {
            matches.push(doc);
          }
        }
        const top = matches.slice(0, 5);
        const citations = top.flatMap((d) => d.citations || []).slice(0, 6);
        const topDoc = top[0];
        recordAudit("SEARCH", q.slice(0, 80), 200, { hits: top.length });
        return sendJson(res, 200, {
          query: q,
          hitCount: top.length,
          answerSummaryBn: topDoc ? topDoc.answerSummaryBn : "",
          verificationStatus: topDoc ? topDoc.verificationStatus : "UNVERIFIED",
          results: citations
        });
      }

      if (req.method === "GET" && pathname.startsWith("/v1/knowledge/documents/")) {
        const docId = decodeURIComponent(pathname.slice("/v1/knowledge/documents/".length));
        const doc = documents.get(docId);
        if (!doc) return sendJson(res, 404, { error: "DOCUMENT_NOT_FOUND" });
        return sendJson(res, 200, doc);
      }

      if (req.method === "POST" && pathname === "/v1/knowledge/ingest") {
        const body = await readJsonBody(req);
        const idempotencyKey = String(req.headers["x-idempotency-key"] || body.idempotencyKey || "").trim();
        if (idempotencyKey && idempotencyKeys.has(idempotencyKey)) {
          const existingId = idempotencyKeys.get(idempotencyKey);
          return sendJson(res, 200, { status: "IDEMPOTENT_REPLAY", documentId: existingId });
        }
        const query = String(body.query || "").trim();
        const summary = String(body.answerSummaryBn || "").trim();
        if (!query || !summary) {
          return sendJson(res, 400, { error: "QUERY_AND_SUMMARY_REQUIRED" });
        }
        const validatedCitations = [];
        for (const c of Array.isArray(body.citations) ? body.citations : []) {
          const check = validatePublicUrl(c.url);
          if (!check.valid) {
            recordAudit("SSRF_OR_INVALID_URL_BLOCKED", String(c.url || ""), 400, { reason: check.reason });
            return sendJson(res, 400, { error: check.reason, url: c.url });
          }
          validatedCitations.push({
            title: String(c.title || ""),
            url: check.url,
            domain: c.domain || check.domain,
            snippet: String(c.snippet || ""),
            contentExtracted: Boolean(c.contentExtracted),
            contentHash: String(c.contentHash || "")
          });
        }

        const docId = crypto.createHash("sha256").update(idempotencyKey || `${query}:${Date.now()}`).digest("hex").slice(0, 16);
        const record = {
          documentId: docId,
          query,
          topicCategory: String(body.topicCategory || "GENERAL"),
          answerSummaryBn: summary,
          verificationStatus: String(body.verificationStatus || "UNVERIFIED"),
          citations: validatedCitations,
          createdAt: new Date().toISOString()
        };
        documents.set(docId, record);
        if (idempotencyKey) idempotencyKeys.set(idempotencyKey, docId);
        recordAudit("INGEST", docId, 201, { citations: validatedCitations.length });
        return sendJson(res, 201, { status: "INGESTED", documentId: docId });
      }

      if (req.method === "POST" && pathname === "/v1/knowledge/research/jobs") {
        const body = await readJsonBody(req);
        const query = String(body.query || "").trim();
        if (!query) return sendJson(res, 400, { error: "QUERY_REQUIRED" });
        const targetUrls = Array.isArray(body.targetUrls) ? body.targetUrls : [];
        for (const u of targetUrls) {
          const check = validatePublicUrl(u);
          if (!check.valid) {
            recordAudit("RESEARCH_SSRF_BLOCKED", String(u), 400, { reason: check.reason });
            return sendJson(res, 400, { error: check.reason, url: u });
          }
        }
        const jobId = crypto.randomUUID();
        const job = {
          jobId,
          query,
          mode: String(body.mode || "DEEP_RESEARCH"),
          targetUrls,
          status: "QUEUED",
          createdAt: new Date().toISOString()
        };
        researchJobs.set(jobId, job);
        recordAudit("RESEARCH_JOB_CREATED", jobId, 202);
        return sendJson(res, 202, job);
      }

      if (req.method === "GET" && pathname.startsWith("/v1/knowledge/research/jobs/")) {
        const jobId = decodeURIComponent(pathname.slice("/v1/knowledge/research/jobs/".length));
        const job = researchJobs.get(jobId);
        if (!job) return sendJson(res, 404, { error: "JOB_NOT_FOUND" });
        return sendJson(res, 200, job);
      }

      if (req.method === "GET" && pathname === "/v1/knowledge/sources/status") {
        return sendJson(res, 200, { sources, auditCount: auditLogs.length });
      }

      if (req.method === "POST" && pathname === "/v1/knowledge/feedback") {
        const body = await readJsonBody(req);
        if (!body.query || !body.rating) {
          return sendJson(res, 400, { error: "QUERY_AND_RATING_REQUIRED" });
        }
        const entry = {
          id: feedbackList.length + 1,
          query: String(body.query),
          rating: String(body.rating),
          correctionNote: String(body.correctionNote || ""),
          createdAt: new Date().toISOString()
        };
        feedbackList.push(entry);
        recordAudit("FEEDBACK", String(entry.id), 201);
        return sendJson(res, 201, entry);
      }

      return sendJson(res, 404, { error: "NOT_FOUND" });
    } catch (err) {
      if (err && err.message === "PAYLOAD_TOO_LARGE") {
        return sendJson(res, 413, { error: "PAYLOAD_TOO_LARGE" });
      }
      if (err && err.message === "INVALID_JSON") {
        return sendJson(res, 400, { error: "INVALID_JSON" });
      }
      return sendJson(res, 500, { error: "INTERNAL_ERROR" });
    }
  });

  return server;
}

if (require.main === module) {
  const port = Number(process.env.PORT || 8088);
  const srv = createKnowledgeServer();
  srv.listen(port, "0.0.0.0", () => {
    console.log(`NAHID Knowledge Server listening on port ${port} (Firebase-free)`);
  });
}

module.exports = { createKnowledgeServer, validatePublicUrl, isBlockedPrivateHost };

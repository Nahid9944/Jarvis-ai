const test = require("node:test");
const assert = require("node:assert/strict");
const { createKnowledgeServer, validatePublicUrl } = require("./server");

async function startTestServer(opts = {}) {
  const server = createKnowledgeServer(opts);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const addr = server.address();
  return { server, baseUrl: `http://127.0.0.1:${addr.port}` };
}

test("Knowledge Server enforces bearer token auth (401 without token) and healthz check", async () => {
  const { server, baseUrl } = await startTestServer({ apiToken: "secret-token-123" });
  try {
    const health = await fetch(`${baseUrl}/healthz`);
    assert.equal(health.status, 200);
    const hJson = await health.json();
    assert.equal(hJson.firebaseUsed, false);

    const unauth = await fetch(`${baseUrl}/v1/knowledge/sources/status`);
    assert.equal(unauth.status, 401);

    const auth = await fetch(`${baseUrl}/v1/knowledge/sources/status`, {
      headers: { Authorization: "Bearer secret-token-123" }
    });
    assert.equal(auth.status, 200);
  } finally {
    server.close();
  }
});

test("Knowledge Server ingest supports idempotency, document fetch, and search", async () => {
  const { server, baseUrl } = await startTestServer({ apiToken: "tok" });
  const headers = {
    Authorization: "Bearer tok",
    "Content-Type": "application/json",
    "X-Idempotency-Key": "idem-001"
  };
  try {
    const res1 = await fetch(`${baseUrl}/v1/knowledge/ingest`, {
      method: "POST",
      headers,
      body: JSON.stringify({
        query: "Bangladesh GDP 2026",
        topicCategory: "POLICY_LAW",
        answerSummaryBn: "বাংলাদেশ পরিসংখ্যান ব্যুরোর প্রতিবেদন অনুযায়ী প্রবৃদ্ধি স্থিতিশীল।",
        verificationStatus: "VERIFIED",
        citations: [
          {
            title: "BBS Report",
            url: "https://bbs.gov.bd/report-2026",
            snippet: "GDP growth data 2026",
            contentExtracted: true
          }
        ]
      })
    });
    assert.equal(res1.status, 201);
    const b1 = await res1.json();

    const resReplay = await fetch(`${baseUrl}/v1/knowledge/ingest`, {
      method: "POST",
      headers,
      body: JSON.stringify({ query: "Bangladesh GDP 2026", answerSummaryBn: "dup" })
    });
    assert.equal(resReplay.status, 200);
    const bReplay = await resReplay.json();
    assert.equal(bReplay.status, "IDEMPOTENT_REPLAY");
    assert.equal(bReplay.documentId, b1.documentId);

    const docRes = await fetch(`${baseUrl}/v1/knowledge/documents/${b1.documentId}`, {
      headers: { Authorization: "Bearer tok" }
    });
    assert.equal(docRes.status, 200);

    const searchRes = await fetch(`${baseUrl}/v1/knowledge/search?q=Bangladesh`, {
      headers: { Authorization: "Bearer tok" }
    });
    assert.equal(searchRes.status, 200);
    const searchJson = await searchRes.json();
    assert.equal(searchJson.hitCount, 1);
    assert.equal(searchJson.verificationStatus, "VERIFIED");
  } finally {
    server.close();
  }
});

test("Knowledge Server blocks SSRF private/loopback/metadata IPs and enforces rate limits (429)", async () => {
  assert.equal(validatePublicUrl("http://127.0.0.1/admin").valid, false);
  assert.equal(validatePublicUrl("http://169.254.169.254/latest/meta-data").valid, false);
  assert.equal(validatePublicUrl("http://10.0.0.8/internal").valid, false);
  assert.equal(validatePublicUrl("https://en.wikipedia.org/wiki/Dhaka").valid, true);

  const { server, baseUrl } = await startTestServer({ apiToken: "tok", rateLimitPerMin: 2 });
  try {
    const ssrfRes = await fetch(`${baseUrl}/v1/knowledge/research/jobs`, {
      method: "POST",
      headers: { Authorization: "Bearer tok", "Content-Type": "application/json" },
      body: JSON.stringify({
        query: "ssrf test",
        targetUrls: ["http://169.254.169.254/latest/meta-data"]
      })
    });
    assert.equal(ssrfRes.status, 400);

    const okRes = await fetch(`${baseUrl}/v1/knowledge/sources/status`, {
      headers: { Authorization: "Bearer tok" }
    });
    assert.equal(okRes.status, 200);

    const limitedRes = await fetch(`${baseUrl}/v1/knowledge/sources/status`, {
      headers: { Authorization: "Bearer tok" }
    });
    assert.equal(limitedRes.status, 429);
  } finally {
    server.close();
  }
});

import { test } from "node:test";
import assert from "node:assert/strict";
import { bridge, sid } from "./helpers.ts";

test("the interactive terminal presses only the allowed keys", async () => {
  const b = await bridge();
  try {
    const ok = await b.act(`/v1/sessions/${sid("w2:p1")}/actions/keys`, { keys: ["up", "enter"] });
    assert.equal(ok.body.receipt?.status, "accepted", JSON.stringify(ok));
    assert.deepEqual(b.mac.sent.at(-1), { pane: "w2:p1", kind: "keys", value: "up enter" });
    const bad = await b.act(`/v1/sessions/${sid("w2:p1")}/actions/keys`, { keys: ["ctrl+d"] });
    assert.equal(bad.body.receipt.status, "rejected");
    assert.match(bad.body.receipt.detail, /bad_keys/);
    assert.equal(b.mac.sent.length, 1, "a refused key never reaches Herdr");
  } finally {
    await b.close();
  }
});

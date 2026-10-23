// Unit tests for the identify race (preferred = Gemini, backup = Claude). Run: npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import { racePreferred } from "../lib/lib/race.js";

const GRACE = 300; // scaled down from 3 s
/** A fake producer that resolves/rejects after `ms`, and records whether it was aborted. */
const fake = (ms, value, { fail = false } = {}) => {
  const state = { aborted: false };
  const fn = (signal) =>
    new Promise((resolve, reject) => {
      signal.addEventListener("abort", () => (state.aborted = true));
      setTimeout(() => (fail ? reject(new Error(value)) : resolve(value)), ms);
    });
  return { fn, state };
};
const near = (actual, expected, slack = 60) => assert.ok(Math.abs(actual - expected) <= slack, `${actual} ms not ≈ ${expected} ms`);

test("preferred answers first → accepted at once, backup aborted", async () => {
  const g = fake(100, "gemini"), c = fake(200, "claude");
  const r = await racePreferred(g.fn, c.fn, GRACE);
  assert.equal(r.value, "gemini");
  assert.equal(r.winner, "preferred");
  near(r.acceptedAtMs, 100);
  assert.equal(c.state.aborted, true);
});

test("backup first but preferred lands inside the grace window → preferred wins", async () => {
  const g = fake(250, "gemini"), c = fake(80, "claude");
  const r = await racePreferred(g.fn, c.fn, GRACE);
  assert.equal(r.value, "gemini");
  near(r.acceptedAtMs, 250);
});

test("preferred slower than the grace → backup accepted when the grace ends, preferred aborted", async () => {
  const g = fake(1000, "gemini"), c = fake(80, "claude");
  const r = await racePreferred(g.fn, c.fn, GRACE);
  assert.equal(r.value, "claude");
  assert.equal(r.winner, "backup");
  near(r.acceptedAtMs, GRACE);
  assert.equal(g.state.aborted, true);
});

test("backup arriving after the grace is accepted immediately", async () => {
  const g = fake(1000, "gemini"), c = fake(450, "claude");
  const r = await racePreferred(g.fn, c.fn, GRACE);
  assert.equal(r.value, "claude");
  near(r.acceptedAtMs, 450);
});

test("preferred fails → backup accepted without waiting for the grace", async () => {
  const g = fake(50, "boom", { fail: true }), c = fake(120, "claude");
  const r = await racePreferred(g.fn, c.fn, GRACE);
  assert.equal(r.value, "claude");
  near(r.acceptedAtMs, 120);
});

test("preferred fails after the backup already arrived → backup accepted right then", async () => {
  const g = fake(150, "boom", { fail: true }), c = fake(50, "claude");
  const r = await racePreferred(g.fn, c.fn, GRACE);
  assert.equal(r.value, "claude");
  near(r.acceptedAtMs, 150);
});

test("backup fails → preferred still wins whenever it arrives", async () => {
  const g = fake(500, "gemini"), c = fake(50, "nope", { fail: true });
  const r = await racePreferred(g.fn, c.fn, GRACE);
  assert.equal(r.value, "gemini");
  near(r.acceptedAtMs, 500);
});

test("both fail → the preferred error is thrown", async () => {
  const g = fake(80, "gemini failed", { fail: true }), c = fake(40, "claude failed", { fail: true });
  await assert.rejects(racePreferred(g.fn, c.fn, GRACE), /gemini failed/);
});

test("no backup → plain preferred call", async () => {
  const g = fake(60, "gemini");
  const r = await racePreferred(g.fn, null, GRACE);
  assert.equal(r.winner, "preferred");
  await assert.rejects(racePreferred(fake(30, "x", { fail: true }).fn, null, GRACE), /x/);
});

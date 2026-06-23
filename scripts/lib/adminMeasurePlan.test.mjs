import assert from 'node:assert/strict';
import { test } from 'node:test';
import { buildGatePhases, buildRequestPlans } from './adminMeasurePlan.mjs';

test('buildRequestPlans builds history and search admin URLs', () => {
  const plans = buildRequestPlans({
    baseUrl: 'http://localhost/api/',
    scenario: 'both',
    roomId: 30001,
    query: 'hello searchable admin keyword',
    searchMode: 'FTS',
    limit: 50,
    from: '2026-06-14T00:00:00',
    to: '2026-06-15T00:00:00',
  });

  assert.equal(plans.length, 2);
  assert.equal(
    plans[0].url,
    'http://localhost/api/admin/chat-rooms/30001/messages?limit=50&from=2026-06-14T00%3A00%3A00&to=2026-06-15T00%3A00%3A00',
  );
  assert.equal(
    plans[1].url,
    'http://localhost/api/admin/messages/search?q=hello+searchable+admin+keyword&mode=FTS&limit=50&roomId=30001&from=2026-06-14T00%3A00%3A00&to=2026-06-15T00%3A00%3A00',
  );
});

test('buildGatePhases keeps cold samples before warm samples for both gate mode', () => {
  assert.deepEqual(buildGatePhases('warm'), ['warm']);
  assert.deepEqual(buildGatePhases('cold'), ['cold']);
  assert.deepEqual(buildGatePhases('both'), ['cold', 'warm']);
});

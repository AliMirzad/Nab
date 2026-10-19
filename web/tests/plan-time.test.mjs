import test from 'node:test';
import assert from 'node:assert/strict';
import { planInstant, localPlanDate, nextPlanWeek } from '../src/planTime.ts';
test('Tehran input is independent of the manager device timezone',()=>{
  const prior=process.env.TZ;
  try{for(const tz of ['UTC','America/Los_Angeles','Asia/Tokyo']){process.env.TZ=tz;assert.equal(planInstant('2026-11-02T08:00'),'2026-11-02T04:30:00.000Z');assert.equal(localPlanDate(new Date('2026-11-02T04:30:00Z')),'2026-11-02T08:00');assert.equal(nextPlanWeek('2026-11-02T08:00'),'2026-11-09T08:00');}}finally{if(prior===undefined)delete process.env.TZ;else process.env.TZ=prior;}
});
test('midnight conversion preserves the Tehran day',()=>{assert.equal(planInstant('2026-11-02T00:00'),'2026-11-01T20:30:00.000Z');assert.equal(nextPlanWeek('2026-12-28T00:00'),'2027-01-04T00:00');});
test('invalid and incomplete dates are rejected',()=>{for(const value of ['','2026-02-31T08:00','2026-11-02T25:00','2026-11-02','2026-11-02T08:'])assert.throws(()=>planInstant(value));});

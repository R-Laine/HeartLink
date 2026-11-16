import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { ref, get, set, update, remove, serverTimestamp } from 'firebase/database';
import { readFileSync } from 'fs';

const env = await initializeTestEnvironment({
  projectId: 'demo-heartlink',
  database: { rules: readFileSync(new URL('../database.rules.json', import.meta.url), 'utf8'), host: '127.0.0.1', port: 9000 },
});
const db = uid => env.authenticatedContext(uid).database();
const anon = env.unauthenticatedContext().database();
const now = Date.now(), DAY = 86400000;
let pass = 0, fail = 0;
async function check(name, p) { try { await p; pass++; console.log('ok   ', name); } catch (e) { fail++; console.log('FAIL ', name, '-', e.message); } }

const C = 'coupleX';
// Alice creates couple + partner invite atomically
await check('create couple', assertSucceeds(update(ref(db('alice')), {
  [`couples/${C}/devices/alice`]: { role: 'a' },
  [`couples/${C}/members/a/name`]: 'Alice',
  [`couples/${C}/pendingInvite`]: 'AAAAAA',
  [`invites/AAAAAA`]: { couple: C, role: 'b', expires: now + 7 * DAY, from: 'Alice' },
})));
await check('cannot hijack existing couple as creator', assertFails(set(ref(db('mallory'), `couples/${C}/devices/mallory`), { role: 'a' })));
await check('stranger cannot read couple', assertFails(get(ref(db('mallory'), `couples/${C}`))));
await check('unauthenticated cannot read invite', assertFails(get(ref(anon, 'invites/AAAAAA'))));
await check('cannot list invites', assertFails(get(ref(db('mallory'), 'invites'))));
await check('cannot list couples', assertFails(get(ref(db('mallory'), 'couples'))));
await check('authed can read single invite', assertSucceeds(get(ref(db('bob'), 'invites/AAAAAA'))));
await check('join without consuming invite fails', assertFails(update(ref(db('bob')), {
  [`couples/${C}/devices/bob`]: { role: 'b', invite: 'AAAAAA' },
  [`couples/${C}/members/b/name`]: 'Bob',
})));
await check('join with wrong role fails', assertFails(update(ref(db('bob')), {
  [`couples/${C}/devices/bob`]: { role: 'a', invite: 'AAAAAA' }, [`invites/AAAAAA`]: null,
})));
await check('bob joins with invite', assertSucceeds(update(ref(db('bob')), {
  [`couples/${C}/devices/bob`]: { role: 'b', invite: 'AAAAAA' },
  [`invites/AAAAAA`]: null,
  [`couples/${C}/members/b/name`]: 'Bob',
  [`couples/${C}/pendingInvite`]: null,
})));
await check('invite cannot be reused', assertFails(update(ref(db('mallory')), {
  [`couples/${C}/devices/mallory`]: { role: 'b', invite: 'AAAAAA' }, [`invites/AAAAAA`]: null,
})));
await check('bob reads couple', assertSucceeds(get(ref(db('bob'), `couples/${C}`))));
await check('bob cannot rename alice', assertFails(set(ref(db('bob'), `couples/${C}/members/a/name`), 'X')));
await check('bob writes own beat', assertSucceeds(set(ref(db('bob'), `couples/${C}/beats/b`), { bpm: 70, ts: serverTimestamp(), source: 'watch' })));
await check('bob cannot write alice beat', assertFails(set(ref(db('bob'), `couples/${C}/beats/a`), { bpm: 70, ts: serverTimestamp() })));
await check('past timestamp ok (health connect)', assertSucceeds(set(ref(db('bob'), `couples/${C}/beats/b`), { bpm: 66, ts: now - 600000, source: 'health' })));
await check('future timestamp rejected', assertFails(set(ref(db('bob'), `couples/${C}/beats/b`), { bpm: 66, ts: now + 3600000 })));
await check('bad bpm rejected', assertFails(set(ref(db('bob'), `couples/${C}/beats/b`), { bpm: 900, ts: serverTimestamp() })));
await check('alice adds her watch', assertSucceeds(set(ref(db('alice'), `couples/${C}/devices/alicewatch`), { role: 'a' })));
await check('alice cannot add device as bob', assertFails(set(ref(db('alice'), `couples/${C}/devices/x2`), { role: 'b' })));
await check('alice watch writes alice beat', assertSucceeds(set(ref(db('alicewatch'), `couples/${C}/beats/a`), { bpm: 80, ts: serverTimestamp(), source: 'watch' })));
await check('device invite created by member', assertSucceeds(set(ref(db('bob'), 'invites/BBBBBB'), { couple: C, role: 'b', expires: now + 900000, name: 'Bob', from: 'Bob' })));
await check('stranger cannot create invite', assertFails(set(ref(db('mallory'), 'invites/CCCCCC'), { couple: C, role: 'a', expires: now + 900000 })));
await check('new phone joins via device invite', assertSucceeds(update(ref(db('bobphone2')), {
  [`couples/${C}/devices/bobphone2`]: { role: 'b', invite: 'BBBBBB' }, [`invites/BBBBBB`]: null,
})));
await check('expired invite unusable', (async () => {
  await env.withSecurityRulesDisabled(c => set(ref(c.database(), 'invites/DDDDDD'), { couple: C, role: 'a', expires: now - 1000 }));
  await assertFails(update(ref(db('mallory')), { [`couples/${C}/devices/mallory`]: { role: 'a', invite: 'DDDDDD' }, [`invites/DDDDDD`]: null }));
})());
await check('anyone can clean up expired invite', assertSucceeds(remove(ref(db('mallory'), 'invites/DDDDDD'))));
await check('bob leaves (removes own device)', assertSucceeds(remove(ref(db('bob'), `couples/${C}/devices/bob`))));
await check('bob no longer reads', assertFails(get(ref(db('bob'), `couples/${C}`))));
await check('alice cannot remove bobphone2', assertFails(remove(ref(db('alice'), `couples/${C}/devices/bobphone2`))));
await check('renew partner invite flow', (async () => {
  const C2 = 'coupleY';
  await assertSucceeds(update(ref(db('carol')), {
    [`couples/${C2}/devices/carol`]: { role: 'a' }, [`couples/${C2}/members/a/name`]: 'Carol',
    [`couples/${C2}/pendingInvite`]: 'EEEEEE', [`invites/EEEEEE`]: { couple: C2, role: 'b', expires: now + DAY, from: 'Carol' },
  }));
  await assertSucceeds(update(ref(db('carol')), {
    [`couples/${C2}/pendingInvite`]: 'FFFFFF', [`invites/FFFFFF`]: { couple: C2, role: 'b', expires: now + DAY, from: 'Carol' }, [`invites/EEEEEE`]: null,
  }));
})());
await check('invite with long expiry rejected', assertFails(set(ref(db('alice'), 'invites/GGGGGG'), { couple: C, role: 'a', expires: now + 30 * DAY })));

// ---- presence, messages, buzz, watching (alice = a, alicewatch = a, bobphone2 = b) ----
await check('alice sets own presence', assertSucceeds(update(ref(db('alice'), `couples/${C}/presence/a`), { connected: true, tz: 'Europe/London', lastSeen: serverTimestamp() })));
await check('alice cannot set bob presence', assertFails(update(ref(db('alice'), `couples/${C}/presence/b`), { connected: true })));
await check('alice watch reports asleep', assertSucceeds(update(ref(db('alicewatch'), `couples/${C}/presence/a`), { asleep: true, asleepAt: serverTimestamp() })));
await check('alice phone reports moving', assertSucceeds(update(ref(db('alice'), `couples/${C}/presence/a`), { movingAt: serverTimestamp() })));
await check('alice phone reports a call', assertSucceeds(update(ref(db('alice'), `couples/${C}/presence/a`), { callAt: serverTimestamp() })));
await check('bob cannot report alice moving', assertFails(update(ref(db('bobphone2'), `couples/${C}/presence/a`), { movingAt: serverTimestamp() })));
await check('alice phone publishes her sleep', assertSucceeds(set(ref(db('alice'), `couples/${C}/presence/a/sleep`), { state: 'wokeUp', reason: 'use', at: serverTimestamp(), since: Date.now() - 60000, basis: Date.now() - 3600000, lastUse: Date.now() })));
await check('unknown sleep state rejected', assertFails(set(ref(db('alice'), `couples/${C}/presence/a/sleep`), { state: 'dreaming', reason: 'use', at: serverTimestamp() })));
await check('sleep without reason rejected', assertFails(set(ref(db('alice'), `couples/${C}/presence/a/sleep`), { state: 'awake', at: serverTimestamp() })));
await check('future sleep time rejected', assertFails(set(ref(db('alice'), `couples/${C}/presence/a/sleep`), { state: 'awake', reason: 'use', at: serverTimestamp(), since: Date.now() + 3600000 })));
await check('bob cannot publish alice sleep', assertFails(set(ref(db('bobphone2'), `couples/${C}/presence/a/sleep`), { state: 'asleep', reason: 'use', at: serverTimestamp() })));
await check('unknown presence field rejected', assertFails(update(ref(db('alice'), `couples/${C}/presence/a`), { foo: 1 })));
await check('alice messages bob', assertSucceeds(set(ref(db('alice'), `couples/${C}/messages/b/m1`), { text: 'hi', ts: serverTimestamp(), from: 'Alice' })));
await check('alice cannot message herself', assertFails(set(ref(db('alice'), `couples/${C}/messages/a/m2`), { text: 'hi', ts: serverTimestamp() })));
await check('stranger cannot message', assertFails(set(ref(db('mallory'), `couples/${C}/messages/b/m3`), { text: 'hi', ts: serverTimestamp() })));
await check('empty message rejected', assertFails(set(ref(db('alice'), `couples/${C}/messages/b/m4`), { text: '', ts: serverTimestamp() })));
await check('alice cannot delete bob inbox', assertFails(remove(ref(db('alice'), `couples/${C}/messages/b/m1`))));
await check('bob deletes from own inbox', assertSucceeds(remove(ref(db('bobphone2'), `couples/${C}/messages/b/m1`))));
await check('bob buzzes alice', assertSucceeds(set(ref(db('bobphone2'), `couples/${C}/buzz/a`), { bpm: 72, ts: serverTimestamp(), from: 'Bob' })));
await check('bob cannot buzz himself', assertFails(set(ref(db('bobphone2'), `couples/${C}/buzz/b`), { bpm: 72, ts: serverTimestamp() })));
await check('silly bpm buzz rejected', assertFails(set(ref(db('bobphone2'), `couples/${C}/buzz/a`), { bpm: 5000, ts: serverTimestamp() })));
await check('bob marks watching alice', assertSucceeds(set(ref(db('bobphone2'), `couples/${C}/watching/a`), serverTimestamp())));
await check('stranger cannot mark watching', assertFails(set(ref(db('mallory'), `couples/${C}/watching/a`), serverTimestamp())));

await check('alice reports phone battery', assertSucceeds(set(ref(db('alice'), `couples/${C}/presence/a/battery/phone`), { level: 64, charging: false, ts: serverTimestamp() })));
await check('alice watch reports battery', assertSucceeds(set(ref(db('alicewatch'), `couples/${C}/presence/a/battery/watch`), { level: 40, charging: true, ts: serverTimestamp() })));
await check('battery over 100 rejected', assertFails(set(ref(db('alice'), `couples/${C}/presence/a/battery/phone`), { level: 140, charging: false, ts: serverTimestamp() })));
await check('unknown battery device rejected', assertFails(set(ref(db('alice'), `couples/${C}/presence/a/battery/tablet`), { level: 50, charging: false, ts: serverTimestamp() })));
await check('bob cannot report alice battery', assertFails(set(ref(db('bobphone2'), `couples/${C}/presence/a/battery/phone`), { level: 1, charging: false, ts: serverTimestamp() })));

await check('alice checks in home', assertSucceeds(set(ref(db('alice'), `couples/${C}/presence/a/checkIn`), { text: 'Home safe 🏠', ts: serverTimestamp() })));
await check('bob cannot check in as alice', assertFails(set(ref(db('bobphone2'), `couples/${C}/presence/a/checkIn`), { text: 'Home', ts: serverTimestamp() })));
await check('alice sets quiet hours', assertSucceeds(set(ref(db('alice'), `couples/${C}/presence/a/quiet`), { enabled: true, start: 1380, end: 420, allowWhenOnline: true })));
await check('quiet minute out of range rejected', assertFails(set(ref(db('alice'), `couples/${C}/presence/a/quiet`), { enabled: true, start: 2000, end: 420, allowWhenOnline: true })));
await check('member sets countdown', assertSucceeds(set(ref(db('bobphone2'), `couples/${C}/countdown`), { date: '2027-02-14' })));
await check('bad countdown date rejected', assertFails(set(ref(db('alice'), `couples/${C}/countdown`), { date: 'soon' })));
await check('stranger cannot set countdown', assertFails(set(ref(db('mallory'), `couples/${C}/countdown`), { date: '2027-02-14' })));

// ---- invites only for your own seat (or the empty partner seat); unlinking removes your watch ----
await check('bob cannot make an invite for alice\'s seat', assertFails(set(ref(db('bobphone2'), 'invites/HHHHHH'), { couple: C, role: 'a', expires: now + 900000, name: 'Alice' })));
await check('bob can make an invite for his own seat', assertSucceeds(set(ref(db('bobphone2'), 'invites/JJJJJJ'), { couple: C, role: 'b', expires: now + 900000, name: 'Bob' })));
await check('partner invite allowed while the seat is empty', (async () => {
  const C3 = 'coupleZ';
  await assertSucceeds(update(ref(db('dana')), {
    [`couples/${C3}/devices/dana`]: { role: 'a' }, [`couples/${C3}/members/a/name`]: 'Dana',
    [`couples/${C3}/pendingInvite`]: 'KKKKKK', [`invites/KKKKKK`]: { couple: C3, role: 'b', expires: now + DAY, from: 'Dana' },
  }));
})());
await check('bob cannot remove alice\'s watch', assertFails(remove(ref(db('bobphone2'), `couples/${C}/devices/alicewatch`))));
await check('alice unlinks her phone and watch together', assertSucceeds(update(ref(db('alice'), `couples/${C}/devices`), { alice: null, alicewatch: null })));
await check('alice watch no longer reads', assertFails(get(ref(db('alicewatch'), `couples/${C}`))));

console.log(`\n${pass} passed, ${fail} failed`);
await env.cleanup();
process.exit(fail ? 1 : 0);

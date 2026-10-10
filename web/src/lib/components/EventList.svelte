<script lang="ts">
	import type { DashEvent, Verdict } from '../types.js';
	import { ageText, clock, dbm, verdictTone } from '../format.js';

	let { events, serverTime }: { events: DashEvent[]; serverTime: number } = $props();
	let showActivity = $state(false);
	function verdictOf(event: DashEvent): Verdict | null { return event.iffStatus ?? event.verdict ?? null; }
	const visible = $derived(showActivity ? events : events.filter((event) => verdictOf(event)));
	const reasons: Record<string, string> = {
		'replayed nonce': 'Replayed response',
		'late response': 'Response too late',
		'position mismatch': 'Position mismatch',
		'signal mismatch': 'Signal mismatch',
		'no response': 'No response',
		'bad tag': 'Authentication failed'
	};
	function reasonOf(event: DashEvent): string {
		if (event.reason) return reasons[event.reason] ?? event.reason;
		return verdictOf(event) === 'FRIEND' ? 'Identity verified' : 'Identity not verified';
	}
	function targetOf(event: DashEvent): string {
		return event.droneId || (event.trackId != null ? `Drone #${event.trackId}` : 'Unidentified drone');
	}
</script>

<div class="filters" role="group" aria-label="Show results or all activity">
	<button aria-pressed={!showActivity} onclick={() => showActivity = false}>Identifications</button>
	<button aria-pressed={showActivity} onclick={() => showActivity = true}>All activity</button>
</div>
<div class="list" role="region" aria-label="Recent events">
	{#each visible as event (event.id)}
		{@const verdict = verdictOf(event)}
		{#if verdict}
			<details class={['result', verdictTone(verdict)]}>
				<summary aria-label={`${verdict} · ${targetOf(event)} · ${reasonOf(event)}. Show details`}>
					<span class="result-top"><span class="chip">{verdict}</span><strong>{targetOf(event)}</strong><span class="age">{ageText(serverTime, event.receivedAt)}</span></span>
					<span class="result-bottom"><span class="reason">{reasonOf(event)}</span><span class="expand" aria-hidden="true"></span></span>
				</summary>
				<dl>
					<div><dt>Time</dt><dd>{clock(event.t)}</dd></div>
					{#if event.trackId != null}<div><dt>Track</dt><dd>#{event.trackId}</dd></div>{/if}
					{#if event.rssiDbm != null}<div><dt>Signal</dt><dd>{dbm(event.rssiDbm)}</dd></div>{/if}
					{#if event.roundTripMs != null}<div><dt>Response time</dt><dd>{event.roundTripMs} ms</dd></div>{/if}
					{#if event.score != null}<div><dt>Detection confidence</dt><dd>{Math.round(event.score * 100)}%</dd></div>{/if}
				</dl>
			</details>
		{:else}
			<article class="activity">
				<div><span>{event.class ? `${event.class === 'person' ? 'Person' : event.class === 'drone' ? 'Drone' : event.class} detected` : event.text ?? 'Status update'}</span><span class="age">{ageText(serverTime, event.receivedAt)}</span></div>
				{#if event.trackId != null}<small>Track #{event.trackId}{event.score != null ? ` · ${Math.round(event.score * 100)}% confidence` : ''}</small>{/if}
			</article>
		{/if}
	{:else}
		<div class="empty"><strong>{showActivity ? 'Waiting for activity' : 'No identifications yet'}</strong><p>{showActivity ? 'Detections and updates will appear here.' : 'Results appear after an identification check.'}</p></div>
	{/each}
</div>

<style>
	.filters { display: grid; grid-template-columns: repeat(2, 1fr); border-bottom: 1px solid var(--line); }
	button { min-height: 44px; padding: 10px 8px; border: 0; border-right: 1px solid var(--line); background: var(--surface-1); color: var(--muted); font-size: 12px; }
	button:last-child { border-right: 0; }
	button:hover { background: var(--surface-2); color: var(--text); }
	button[aria-pressed='true'] { color: var(--ink); background: var(--accent); font-weight: 700; }
	.list { overflow-y: auto; min-height: 0; flex: 1; scrollbar-color: var(--line) var(--surface-0); }
	.result { border-bottom: 1px solid var(--line); border-left: 3px solid var(--tone); background: var(--surface-1); }
	.result:nth-child(even) { background: var(--surface-0); }
	summary { padding: 14px 12px; list-style: none; cursor: pointer; }
	summary::-webkit-details-marker { display: none; }
	summary:hover { background: var(--surface-2); }
	summary:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; }
	.result-top { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
	.chip { padding: 3px 5px; background: var(--tone); color: var(--ink); font-size: 12px; font-weight: 800; }
	strong { font-size: 14px; font-weight: 600; overflow-wrap: anywhere; }
	.age { margin-left: auto; color: var(--muted); font-size: 11px; white-space: nowrap; }
	.result-bottom { margin-top: 9px; display: flex; align-items: center; gap: 10px; }
	.reason { font-size: 13px; line-height: 1.5; overflow-wrap: anywhere; }
	.expand { margin-left: auto; width: 7px; height: 7px; border-right: 1px solid var(--muted); border-bottom: 1px solid var(--muted); transform: rotate(45deg); flex-shrink: 0; }
	details[open] .expand { transform: rotate(225deg); }
	dl { margin: 0; padding: 10px 12px; border-top: 1px solid var(--line); background: var(--surface-0); }
	dl div { display: flex; justify-content: space-between; gap: 10px; padding: 5px 0; font-size: 12px; }
	dt { color: var(--muted); } dd { margin: 0; text-align: right; }
	.green { --tone: var(--green); } .red { --tone: var(--red); }
	.activity { padding: 12px 15px; border-bottom: 1px solid var(--line); color: var(--muted); }
	.activity > div { display: flex; align-items: baseline; gap: 10px; font-size: 12px; line-height: 1.5; }
	.activity small { display: block; margin-top: 5px; font-size: 11px; }
	.empty { padding: 28px 16px; color: var(--muted); }
	.empty strong { color: var(--text); font-size: 14px; }
	.empty p { font-size: 12px; line-height: 1.6; margin: 8px 0 0; }
	@media (max-width: 850px) { .list { max-height: 65vh; } }
</style>

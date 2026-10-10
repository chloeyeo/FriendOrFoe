<script lang="ts">
	import type { DashEvent } from '../types.js';
	import { ageText, clock, dbm, verdictTone } from '../format.js';

	let { events, serverTime }: { events: DashEvent[]; serverTime: number } = $props();

	function numbers(e: DashEvent): string {
		const parts = [e.rssiDbm != null ? dbm(e.rssiDbm) : null, e.roundTripMs != null ? `${e.roundTripMs} ms` : null];
		return parts.filter(Boolean).join(' · ');
	}

	function verdictOf(e: DashEvent) {
		return e.iffStatus ?? e.verdict ?? null;
	}
</script>

<div class="list">
	{#if events.length === 0}
		<p class="empty">No events yet.</p>
	{/if}
	{#each events as e (e.id)}
		<div class="row">
			<span class="time">{clock(e.t)}</span>
			<span class="what">
				{#if verdictOf(e)}
					{@const v = verdictOf(e)!}
					<span class={['chip', verdictTone(v)]}>{v}</span>
					<span class="reason">{[e.droneId, e.reason].filter(Boolean).join(' · ') || '—'}</span>
				{:else if e.class}
					<span class="class">{e.class}</span>
					{#if e.score != null}<span class="score">{e.score.toFixed(2)}</span>{/if}
				{:else}
					<span class="reason status">{e.text ?? '—'}</span>
				{/if}
			</span>
			<span class="numbers">{numbers(e)}</span>
			<span class="age">{ageText(serverTime, e.receivedAt)}</span>
		</div>
	{/each}
</div>

<style>
	.list {
		display: flex;
		flex-direction: column;
		gap: 6px;
		overflow-y: auto;
		min-height: 0;
	}
	.empty {
		color: var(--muted);
		font-size: 18px;
	}
	.row {
		display: grid;
		grid-template-columns: 5em 1fr auto;
		grid-template-areas:
			'time what age'
			'. numbers numbers';
		align-items: center;
		column-gap: 12px;
		row-gap: 2px;
		padding: 8px 12px;
		background: var(--surface-1);
		border-radius: 10px;
		font-size: 20px;
	}
	.time,
	.numbers,
	.score {
		font-family: ui-monospace, 'SF Mono', Menlo, Consolas, monospace;
		font-variant-numeric: tabular-nums;
	}
	.time {
		grid-area: time;
	}
	.what {
		grid-area: what;
	}
	.numbers {
		grid-area: numbers;
	}
	.numbers:empty {
		display: none;
	}
	.age {
		grid-area: age;
	}
	.time,
	.age {
		color: var(--muted);
	}
	.age {
		text-align: right;
		font-size: 16px;
	}
	.what {
		display: flex;
		align-items: center;
		gap: 10px;
		min-width: 0;
	}
	.reason {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
	.class {
		font-weight: 600;
	}
	.status {
		color: var(--muted);
	}
	.score {
		color: var(--muted);
	}
	.chip {
		padding: 1px 10px;
		border-radius: 6px;
		font-weight: 800;
		color: var(--ink);
	}
	.chip.green {
		background: var(--green);
	}
	.chip.red {
		background: var(--red);
	}
</style>

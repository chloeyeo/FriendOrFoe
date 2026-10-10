<script lang="ts">
	import type { Counts } from '../types.js';

	let { counts }: { counts: Counts } = $props();

	const tiles = $derived([
		{ label: 'drones', n: counts.drones, tone: 'amber' },
		{ label: 'people', n: counts.people, tone: 'neutral' },
		{ label: 'friend', n: counts.friend, tone: 'green' },
		{ label: 'foe', n: counts.foe, tone: 'red' },
		{ label: 'unknown', n: counts.unknown, tone: 'red' }
	]);
</script>

<div class="counts">
	{#each tiles as tile (tile.label)}
		<div class="tile">
			<span class={['n', tile.tone]}>{tile.n}</span>
			<span class="label">{tile.label}</span>
		</div>
	{/each}
</div>

<style>
	.counts {
		display: grid;
		grid-template-columns: repeat(5, 1fr);
		gap: 12px;
	}
	.tile {
		display: flex;
		flex-direction: column;
		padding: 10px 16px;
		background: var(--surface-1);
		border-radius: 12px;
	}
	.n {
		font-size: 44px;
		font-weight: 800;
		line-height: 1.1;
		font-variant-numeric: tabular-nums;
	}
	.label {
		color: var(--muted);
		font-size: 18px;
		font-weight: 600;
	}
	.amber {
		color: var(--amber);
	}
	.green {
		color: var(--green);
	}
	.red {
		color: var(--red);
	}
	.neutral {
		color: var(--neutral);
	}
</style>

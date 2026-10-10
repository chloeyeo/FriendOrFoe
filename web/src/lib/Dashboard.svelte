<script lang="ts">
	import Counts from './components/Counts.svelte';
	import EventList from './components/EventList.svelte';
	import FrameView from './components/FrameView.svelte';
	import { ageText } from './format.js';
	import { DashboardPoller } from './poller.svelte.js';
	import type { DashboardState } from './types.js';

	// Polls while this component reads it; stops when the page goes away.
	const poller = new DashboardPoller('/api/state', 1000);

	function connectionText(s: DashboardState | null, error: string | null): string {
		if (error) return `dashboard API unreachable (${error})`;
		if (!s) return 'connecting…';
		const last = s.updatedAt ? `last event ${ageText(s.serverTime, s.updatedAt * 1000)}` : 'no events yet';
		return `API ok · ${s.received} received · ${last}`;
	}
</script>

<svelte:head>
	<title>FriendOrFoe · observer feed</title>
</svelte:head>

<main>
	<header>
		<h1>FriendOrFoe <span>observer feed · phone A</span></h1>
		<p class={['connection', !poller.error && poller.state ? 'ok' : 'down']}>
			{connectionText(poller.state, poller.error)}
		</p>
	</header>

	{#if poller.state}
		{@const s = poller.state}
		<Counts counts={s.counts} />
		<section class="body">
			<div class="left">
				<FrameView frame={s.frame} thumbnail={s.thumbnail} serverTime={s.serverTime} />
			</div>
			<div class="right">
				<h2>Detections and verdicts</h2>
				<EventList events={s.events} serverTime={s.serverTime} />
			</div>
		</section>
	{:else}
		<p class="hint">
			Start the API with <code>python laptop/dashboard/app.py</code> (port 8081); this page polls it once a
			second.
		</p>
	{/if}
</main>

<style>
	main {
		display: flex;
		flex-direction: column;
		gap: 16px;
		height: 100vh;
		box-sizing: border-box;
		padding: 20px 24px;
	}
	header {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: 24px;
	}
	h1 {
		margin: 0;
		font-size: 32px;
	}
	h1 span {
		color: var(--muted);
		font-size: 20px;
		font-weight: 500;
	}
	h2 {
		margin: 0 0 8px;
		font-size: 20px;
		color: var(--muted);
	}
	.connection {
		margin: 0;
		font-size: 18px;
		font-weight: 600;
	}
	.connection.ok {
		color: var(--muted);
	}
	.connection.down {
		color: var(--red);
	}
	.body {
		display: grid;
		grid-template-columns: 3fr 2fr;
		gap: 20px;
		flex: 1;
		min-height: 0;
	}
	.left {
		display: flex;
		align-items: flex-start;
		min-height: 0;
	}
	.right {
		display: flex;
		flex-direction: column;
		min-height: 0;
	}
	.hint {
		color: var(--muted);
		font-size: 20px;
	}
	@media (max-width: 900px) {
		.body {
			grid-template-columns: 1fr;
		}
	}
</style>

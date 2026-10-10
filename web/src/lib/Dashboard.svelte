<script lang="ts">
	import EventList from './components/EventList.svelte';
	import FrameView from './components/FrameView.svelte';
	import { DashboardPoller } from './poller.svelte.js';

	const poller = new DashboardPoller('/api/state', 1000);
	const connected = $derived(!!poller.state && !poller.error);
	const frame = $derived(poller.state?.frame ?? null);
	const fresh = $derived(connected && !!frame && poller.state!.serverTime - frame.receivedAt < 3000);
	const feedStatus = $derived(poller.error ? 'Disconnected' : !connected ? 'Connecting' : !frame ? 'Waiting for observer' : fresh ? 'Receiving' : 'Feed paused');
	const objects = $derived.by(() => {
		if (!frame) return 'No targets yet';
		const drones = frame.tracks.filter((track) => track.class === 'drone').length;
		const people = frame.tracks.filter((track) => track.class === 'person').length;
		return [drones ? `${drones} drone${drones === 1 ? '' : 's'}` : '', people ? `${people} ${people === 1 ? 'person' : 'people'}` : ''].filter(Boolean).join(' · ') || 'No targets';
	});
</script>

<svelte:head>
	<title>FriendOrFoe / Observer</title>
	<meta name="description" content="Observer view and recent friend-or-foe identification results." />
</svelte:head>

<main>
	<header class="masthead">
		<h1>FRIEND<span>/</span>FOE</h1>
		<div class={['connection', !fresh && 'waiting']} role="status">
			<span class="indicator" aria-hidden="true"></span>{feedStatus}
		</div>
	</header>

	{#if poller.error}
		<div class="notice" role="alert">Connection lost. Last received data is shown while reconnecting.</div>
	{/if}

	<div class="workspace">
		<section class="observer" aria-labelledby="observer-title">
			<div class="panel-heading">
				<h2 id="observer-title">Observation</h2>
				<span>{!fresh && frame ? 'Last view · ' : ''}{objects}</span>
			</div>
			<FrameView {frame} thumbnail={poller.state?.thumbnail ?? null} serverTime={poller.state?.serverTime ?? 0} {connected} />
		</section>
		<section class="events" aria-labelledby="events-title">
			<div class="panel-heading"><h2 id="events-title">Recent results</h2></div>
			<EventList events={poller.state?.events ?? []} serverTime={poller.state?.serverTime ?? 0} />
		</section>
	</div>
</main>

<style>
	main { height: 100dvh; min-height: 360px; display: flex; flex-direction: column; }
	.masthead { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 58px; padding: 0 14px; background: var(--surface-1); border-bottom: 1px solid var(--line); }
	h1 { margin: 0; font-size: 23px; letter-spacing: -1px; font-weight: 800; }
	h1 span { color: var(--muted); padding: 0 2px; }
	.connection { display: flex; gap: 7px; align-items: center; color: var(--text); font-size: 12px; }
	.indicator { width: 6px; height: 6px; background: currentColor; flex-shrink: 0; }
	.waiting { color: var(--amber); }
	.panel-heading { display: flex; justify-content: space-between; align-items: center; gap: 10px; min-height: 42px; padding: 10px 14px; background: var(--surface-2); border-bottom: 1px solid var(--line); }
	h2 { font: inherit; font-size: 13px; font-weight: 600; margin: 0; }
	.panel-heading > span { color: var(--muted); font-size: 12px; text-align: right; }
	.workspace { display: grid; grid-template-columns: minmax(0, 1fr) minmax(320px, 370px); flex: 1; min-height: 0; }
	.observer, .events { display: flex; flex-direction: column; min-width: 0; min-height: 0; }
	.observer { border-right: 1px solid var(--line); }
	.notice { padding: 10px 14px; color: var(--amber); background: #2a2112; border-bottom: 1px solid var(--amber); font-size: 12px; line-height: 1.5; }
	@media (max-width: 850px) {
		main { height: auto; min-height: 100dvh; }
		.workspace { display: flex; flex-direction: column; }
		.observer { border-right: 0; }
		.events { flex: 1; min-height: 220px; border-top: 1px solid var(--line); }
	}
	@media (max-width: 400px) {
		.masthead { padding: 0 10px; gap: 8px; }
		h1 { font-size: 20px; }
		.connection { font-size: 10px; }
		.panel-heading { padding: 10px; }
		.panel-heading > span { font-size: 11px; }
	}
</style>

<script lang="ts">
	import type { Frame } from '../types.js';

	let {
		frame,
		thumbnail,
		serverTime
	}: {
		frame: Frame | null;
		thumbnail: { receivedAt: number; url: string } | null;
		serverTime: number;
	} = $props();

	const STALE_MS = 3000;

	// Only show the picture while it is fresh, so old pixels never sit under live rings.
	const picture = $derived(
		thumbnail && serverTime - thumbnail.receivedAt < STALE_MS ? thumbnail.url : null
	);
	const silentFor = $derived(frame ? Math.floor((serverTime - frame.receivedAt) / 1000) : null);
	// Boxes are preview pixels; the view keeps the phone's preview aspect and works in percentages of it.
	const w = $derived(frame && frame.width > 0 ? frame.width : 1920);
	const h = $derived(frame && frame.height > 0 ? frame.height : 1080);
</script>

<div class="frame" style:aspect-ratio={w / h}>
	{#if picture}
		<img src={picture} alt="Latest preview from phone A" />
	{:else}
		<div class="no-picture">rings only · no picture from the phone</div>
	{/if}

	{#if frame}
		{#each frame.tracks as track (track.id)}
			{@const [l, t, r, b] = track.box}
			<div
				class={['ring', track.tone, track.class === 'drone' && 'thick']}
				style:left="{(l / w) * 100}%"
				style:top="{(t / h) * 100}%"
				style:width="{((r - l) / w) * 100}%"
				style:height="{((b - t) / h) * 100}%"
			>
				<span class={['tag', track.tone, t / h < 0.08 && 'inside']}>{track.tag}</span>
				{#if track.detail}
					<span class="detail">{track.detail}</span>
				{/if}
			</div>
		{/each}
		{#if silentFor !== null && silentFor * 1000 >= STALE_MS}
			<div class="stale">phone silent for {silentFor} s</div>
		{/if}
	{:else}
		<div class="stale">waiting for phone A…</div>
	{/if}
</div>

<style>
	.frame {
		position: relative;
		width: 100%;
		max-height: 100%;
		overflow: hidden;
		border-radius: 14px;
		background: linear-gradient(#2a3a4c, #141b23);
	}
	img {
		position: absolute;
		inset: 0;
		width: 100%;
		height: 100%;
		object-fit: fill; /* boxes are normalised to this exact picture */
	}
	.no-picture {
		position: absolute;
		right: 16px;
		bottom: 12px;
		color: var(--muted);
		font-size: 16px;
	}
	.ring {
		position: absolute;
		box-sizing: border-box;
		border: 3px solid var(--tone);
		border-radius: 12px;
		box-shadow: 0 0 0 2px rgb(0 0 0 / 0.45);
	}
	.ring.thick {
		border-width: 6px;
	}
	.tag {
		position: absolute;
		left: -6px;
		bottom: calc(100% + 8px);
		padding: 2px 10px;
		border-radius: 8px;
		white-space: nowrap;
		font-size: 26px;
		font-weight: 800;
		color: var(--ink);
		background: var(--tone);
	}
	.tag.inside {
		bottom: auto;
		top: 6px;
		left: 6px;
	}
	.tag.neutral {
		color: var(--neutral);
		background: rgb(0 0 0 / 0.7);
	}
	.detail {
		position: absolute;
		left: -6px;
		top: calc(100% + 8px);
		padding: 2px 10px;
		border-radius: 8px;
		white-space: nowrap;
		font-size: 18px;
		font-weight: 700;
		color: var(--tone);
		background: rgb(0 0 0 / 0.7);
	}
	.amber {
		--tone: var(--amber);
	}
	.green {
		--tone: var(--green);
	}
	.red {
		--tone: var(--red);
	}
	.neutral {
		--tone: var(--neutral);
	}
	.stale {
		position: absolute;
		right: 16px;
		top: 12px;
		padding: 4px 12px;
		border-radius: 8px;
		background: rgb(0 0 0 / 0.7);
		color: var(--amber);
		font-size: 18px;
		font-weight: 700;
	}
</style>

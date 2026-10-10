<script lang="ts">
	import type { Frame } from '../types.js';
	let { frame, thumbnail, serverTime, connected = true }: {
		frame: Frame | null;
		thumbnail: { receivedAt: number; url: string } | null;
		serverTime: number;
		connected?: boolean;
	} = $props();
	const STALE_MS = 3000;
	const picture = $derived(connected && thumbnail && serverTime - thumbnail.receivedAt < STALE_MS ? thumbnail.url : null);
	const silentFor = $derived(frame ? Math.max(0, Math.floor((serverTime - frame.receivedAt) / 1000)) : null);
	const stale = $derived(!connected || (silentFor !== null && silentFor * 1000 >= STALE_MS));
	const w = $derived(frame && frame.width > 0 ? frame.width : 1920);
	const h = $derived(frame && frame.height > 0 ? frame.height : 1080);
	// Keep the verdict, reason and identity on the object; measurements live in result details.
	function compactLabel(text: string): string {
		return text.split(' · ').filter((part) => !/\b(?:dBm|ms)$/.test(part.trim())).join(' · ');
	}
</script>

<div class="viewport">
	<div class="frame" style:--frame-ratio={w / h}>
		{#if picture}<img src={picture} alt="Latest camera preview from the observer phone" />{/if}
		{#if !frame}
			<div class="standby"><span class="reticle" aria-hidden="true"></span><strong>Waiting for observer</strong><p>Object positions will appear when the phone connects.</p></div>
		{:else if frame.tracks.length === 0 && !picture}
			<div class="standby"><strong>No objects detected</strong></div>
		{:else}
			{#each frame.tracks as track (track.id)}
				{@const [l, t, r, b] = track.box}
				{@const detail = track.detail ? compactLabel(track.detail) : ''}
				<div class={['ring', track.tone, stale && 'stale-ring', track.class === 'drone' && 'drone']}
					style:left="{(l / w) * 100}%" style:top="{(t / h) * 100}%"
					style:width="{((r - l) / w) * 100}%" style:height="{((b - t) / h) * 100}%">
					<span class={['tag', t / h < 0.12 && 'inside', l / w > 0.65 && 'right']}>{compactLabel(track.tag)}</span>
					{#if detail}<span class={['detail', b / h > 0.85 && 'above', l / w > 0.65 && 'right']}>{detail}</span>{/if}
				</div>
			{/each}
		{/if}
	</div>
	{#if frame && stale}<div class="stale">Last received positions{connected ? ` · ${silentFor} s ago` : ''}</div>{/if}
	{#if frame && !picture}<div class="no-picture">Positions only · camera image unavailable</div>{/if}
</div>

<style>
	.viewport { position: relative; display: grid; place-items: center; flex: 1; min-height: 0; overflow: hidden; container-type: size; background-color: #111316; background-image: linear-gradient(#30363c45 1px, transparent 1px), linear-gradient(90deg, #30363c45 1px, transparent 1px); background-size: 48px 48px; }
	.frame { position: relative; width: min(100cqw, calc(100cqh * var(--frame-ratio))); aspect-ratio: var(--frame-ratio); }
	img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: fill; }
	.no-picture, .stale { position: absolute; left: 0; right: 0; padding: 9px 12px; font-size: 11px; line-height: 1.4; pointer-events: none; background: #101113eb; }
	.no-picture { bottom: 0; color: var(--muted); }
	.stale { top: 0; color: var(--amber); border-bottom: 1px solid var(--line); }
	.ring { position: absolute; border: 1px solid var(--tone); box-shadow: 0 0 0 1px #0009; }
	.ring.drone { border-width: 2px; }
	.stale-ring { border-style: dashed; opacity: .55; }
	.tag, .detail { position: absolute; left: -2px; width: max-content; max-width: min(300px, 65cqw); padding: 4px 6px; font-size: clamp(11px, 1.6cqw, 18px); font-weight: 700; line-height: 1.2; overflow-wrap: anywhere; }
	.tag { bottom: calc(100% + 3px); color: var(--ink); background: var(--tone); }
	.tag.inside { bottom: auto; top: 2px; left: 2px; }
	.tag.right, .detail.right { left: auto; right: -2px; }
	.detail { top: calc(100% + 3px); font-size: clamp(10px, 1.2cqw, 13px); color: var(--tone); background: #101113e8; font-weight: 500; }
	.detail.above { top: auto; bottom: 2px; }
	.amber { --tone: var(--amber); } .green { --tone: var(--green); } .red { --tone: var(--red); } .neutral { --tone: var(--neutral); }
	.standby { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; color: var(--muted); padding: 14px; text-align: center; }
	.standby strong { font-size: 16px; font-weight: 500; color: var(--text); }
	.standby p { margin: 8px 0 0; font-size: 12px; line-height: 1.6; }
	.reticle { width: 38px; height: 38px; border: 1px solid var(--line); margin-bottom: 18px; position: relative; }
	.reticle::before, .reticle::after { content: ''; position: absolute; background: var(--accent); }
	.reticle::before { width: 18px; height: 1px; top: 18px; left: 9px; }
	.reticle::after { height: 18px; width: 1px; top: 9px; left: 18px; }
	@media (max-width: 850px) { .viewport { flex: none; height: clamp(240px, 58vw, 480px); } }
</style>

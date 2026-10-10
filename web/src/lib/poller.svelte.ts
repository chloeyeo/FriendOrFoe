import { createSubscriber } from 'svelte/reactivity';
import type { DashboardState } from './types.js';

/**
 * Polls the dashboard API once per interval while something on the page reads `state` or `error`,
 * and stops when nothing does. A request never overlaps the next one and gives up before the next
 * tick, so a dead laptop API shows up as `error` instead of a growing backlog.
 */
export class DashboardPoller {
	#state: DashboardState | null = null;
	#error: string | null = null;
	#subscribe: () => void;

	constructor(url: string, intervalMs = 1000) {
		this.#subscribe = createSubscriber((update) => {
			let stopped = false;
			let timer: ReturnType<typeof setTimeout> | undefined;

			const tick = async () => {
				const controller = new AbortController();
				const timeout = setTimeout(() => controller.abort(), intervalMs * 0.9);
				try {
					const res = await fetch(url, { cache: 'no-store', signal: controller.signal });
					if (!res.ok) throw new Error(`HTTP ${res.status}`);
					this.#state = (await res.json()) as DashboardState;
					this.#error = null;
				} catch (e) {
					this.#error = e instanceof Error && e.name !== 'AbortError' ? e.message : 'no answer';
				} finally {
					clearTimeout(timeout);
					if (!stopped) {
						update();
						timer = setTimeout(tick, intervalMs);
					}
				}
			};
			tick();

			return () => {
				stopped = true;
				clearTimeout(timer);
			};
		});
	}

	get state(): DashboardState | null {
		this.#subscribe();
		return this.#state;
	}

	get error(): string | null {
		this.#subscribe();
		return this.#error;
	}
}

import { useWindowDimensions } from 'react-native';

/**
 * - `phone`: a phone held landscape (phone A, Galaxy A06: 20:9, about 850 x 384 dp). Vertical space is the
 *   scarce resource, so the camera takes the full height with the status floating on it, and the results column
 *   shrinks with the screen.
 * - `portrait`: anything taller than wide (the web preview on a phone, a tablet held upright): stacked, like the
 *   web page's narrow layout.
 * - `wide`: tablets and desktops in landscape: the web page's two-column layout with its masthead.
 */
export type LayoutMode = 'phone' | 'portrait' | 'wide';

export interface Layout {
  mode: LayoutMode;
  width: number;
  height: number;
  /** Width of the results column in the side-by-side modes. */
  resultsWidth: number;
  /** Phone-sized: shorter labels, larger tags relative to the view, bigger tap slop. */
  compact: boolean;
}

export function layoutFor(width: number, height: number): Layout {
  const mode: LayoutMode = height > width ? 'portrait' : height < 520 ? 'phone' : 'wide';
  const resultsWidth = mode === 'wide' ? Math.round(Math.min(370, Math.max(320, width * 0.3))) : Math.round(Math.min(340, Math.max(250, width * 0.32)));
  return { mode, width, height, resultsWidth, compact: mode !== 'wide' };
}

export function useLayout(): Layout {
  const { width, height } = useWindowDimensions();
  return layoutFor(width, height);
}

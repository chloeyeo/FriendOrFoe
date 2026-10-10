import { layoutFor } from '../ui/layout';

describe('layoutFor', () => {
  it('treats phone A held landscape (Galaxy A06, about 853 x 384 dp) as the immersive phone layout', () => {
    expect(layoutFor(853, 384)).toMatchObject({ mode: 'phone', compact: true, resultsWidth: 273 });
  });

  it('stacks anything taller than wide', () => {
    expect(layoutFor(390, 844)).toMatchObject({ mode: 'portrait', compact: true });
  });

  it('keeps the web two-column layout on tablets and desktops', () => {
    expect(layoutFor(1280, 800)).toMatchObject({ mode: 'wide', compact: false, resultsWidth: 370 });
  });

  it('never squeezes the results column below 250 dp on a small phone', () => {
    expect(layoutFor(640, 360).resultsWidth).toBe(250);
  });
});

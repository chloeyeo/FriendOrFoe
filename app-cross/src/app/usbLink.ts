import { FofUsb } from '../../modules/fof-native';
import type { LineLink, LinkHandlers } from '../signal/lineLink';

/** The reader on this phone's USB port; null in a build without the USB module (iPhone, web). */
export function usbLink(h: LinkHandlers): LineLink | null {
  const usb = FofUsb;
  if (!usb) return null;
  let subscriptions: { remove(): void }[] = [];
  return {
    name: 'USB',
    async open() {
      subscriptions.forEach((s) => s.remove());
      subscriptions = [
        usb.addListener('onLine', ({ line }) => h.onLine(line)),
        usb.addListener('onState', ({ connected, error }) => h.onState(connected, error)),
      ];
      return usb.open(null);
    },
    async write(line) {
      if (!usb.write(line)) throw new Error('write to the reader failed');
    },
    isOpen: () => usb.isOpen(),
    close() {
      subscriptions.forEach((s) => s.remove());
      subscriptions = [];
      usb.close();
    },
  };
}

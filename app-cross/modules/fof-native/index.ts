// FriendOrFoe native side (Android only): ClikaRT runtime + camera pane, and the USB gadget link.
// Everything here is optional at runtime: on web, iOS or a build without the module the app falls back to the
// simulated detector and the simulated gadget, so the UI and the session logic still run.
export { FofRuntime, FofUsb, FofCameraView, hasNativeCamera } from './src/native';
export type { CameraFrameEvent, CameraErrorEvent, FofCameraViewProps, OpenDetectorResult, UsbDevice } from './src/native';

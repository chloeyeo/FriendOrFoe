// FriendOrFoe native side: the camera pane with on-device detection (Android: ClikaRT; iPhone: Core ML) and,
// on Android, the USB gadget link.
// Everything here is optional at runtime: on web, iOS or a build without the module the app falls back to the
// simulated detector and the simulated gadget, so the UI and the session logic still run.
export { FofRuntime, FofUsb, FofCameraView, hasNativeCamera, onDeviceDetector } from './src/native';
export type { CameraFrameEvent, CameraErrorEvent, FofCameraViewProps, OpenDetectorResult, UsbDevice } from './src/native';

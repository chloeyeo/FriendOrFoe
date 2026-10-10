import { NativeModule, requireNativeView, requireOptionalNativeModule } from 'expo';
import * as React from 'react';
import type { StyleProp, ViewStyle } from 'react-native';

export interface OpenDetectorResult {
  loadMs: number;
  warmupMs: number;
  threads: number;
}

export interface UsbDevice {
  deviceId: number;
  vendorId: number;
  productId: number;
  name: string;
}

/** One raw detector row from ClikaRT, frame pixels. */
export interface NativeRow {
  labelId: number;
  score: number;
  left: number;
  top: number;
  right: number;
  bottom: number;
  label: string;
}

export interface CameraFrameEvent {
  width: number;
  height: number;
  /** False when the detector did not run on this frame (model not open yet, no serve URL, or `none`). */
  detected: boolean;
  ms: number;
  /** ClikaRT rows (detector `clikart`). */
  rows: NativeRow[] | null;
  /** The raw `/v1/detections` body (detector `http`); parsed in TypeScript. */
  body: string | null;
  error: string | null;
  t: number;
}

export interface CameraErrorEvent {
  code: string;
  message: string;
}

export interface FofCameraViewProps {
  detector: 'clikart' | 'http' | 'none';
  serveUrl: string;
  intervalMs: number;
  /** Dashboard base URL for the preview thumbnail; empty disables it. */
  thumbnailUrl: string;
  active: boolean;
  onFrame?: (event: { nativeEvent: CameraFrameEvent }) => void;
  onCameraError?: (event: { nativeEvent: CameraErrorEvent }) => void;
  style?: StyleProp<ViewStyle>;
}

declare class FofRuntimeModule extends NativeModule<Record<string, never>> {
  defaultModelDir(): string;
  requestCameraPermission(): Promise<{ granted: boolean; status: string }>;
  openDetector(modelDir: string, threads: number): Promise<OpenDetectorResult>;
  closeDetector(): Promise<void>;
}

type UsbEvents = {
  onLine: (event: { line: string }) => void;
  onState: (event: { connected: boolean; error: string | null }) => void;
};

declare class FofUsbModule extends NativeModule<UsbEvents> {
  listDevices(): Promise<UsbDevice[]>;
  open(deviceId: number | null): Promise<string>;
  write(line: string): boolean;
  isOpen(): boolean;
  close(): void;
}

export const FofRuntime = requireOptionalNativeModule<FofRuntimeModule>('FofRuntime');
export const FofUsb = requireOptionalNativeModule<FofUsbModule>('FofUsb');

/** True on an Android build that carries the camera pane. */
export const hasNativeCamera = FofRuntime !== null;

const NativeCameraView: React.ComponentType<FofCameraViewProps> | null = hasNativeCamera ? requireNativeView('FofRuntime') : null;

export function FofCameraView(props: FofCameraViewProps) {
  if (!NativeCameraView) return null;
  return <NativeCameraView {...props} />;
}

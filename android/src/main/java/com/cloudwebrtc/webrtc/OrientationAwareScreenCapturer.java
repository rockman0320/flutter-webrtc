package com.cloudwebrtc.webrtc;

import org.webrtc.SurfaceTextureHelper;
import org.webrtc.CapturerObserver;
import org.webrtc.ThreadUtils;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.view.Surface;
import android.view.WindowManager;
import android.app.Activity;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjectionManager;
import android.os.Looper;
import android.os.Handler;
import android.os.Build;
import android.view.Display;

/**
 * An copy of ScreenCapturerAndroid to capture the screen content while being aware of device orientation
 */
@TargetApi(21)
public class OrientationAwareScreenCapturer implements VideoCapturer, VideoSink {
    private static final int DISPLAY_FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
    // DPI for VirtualDisplay, does not seem to matter for us.
    private static final int VIRTUAL_DISPLAY_DPI = 400;
    private final Intent mediaProjectionPermissionResultData;
    private final MediaProjection.Callback mediaProjectionCallback;
    private int width;
    private int height;
    private int oldWidth;
    private int oldHeight;
    private VirtualDisplay virtualDisplay;
    private Surface virtualDisplaySurface;
    private SurfaceTextureHelper surfaceTextureHelper;
    private CapturerObserver capturerObserver;
    private long numCapturedFrames = 0;
    private MediaProjection mediaProjection;
    private boolean isDisposed = false;
    private MediaProjectionManager mediaProjectionManager;
    private WindowManager windowManager;
    private boolean isPortrait;

    // 1 FPS heartbeat: 정적 화면에서 VirtualDisplay 프레임 유지
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private VideoFrame.I420Buffer heartbeatI420 = null;
    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (isDisposed || capturerObserver == null) return;
            final VideoFrame.I420Buffer i420;
            synchronized (OrientationAwareScreenCapturer.this) {
                i420 = heartbeatI420;
                if (i420 != null) i420.retain();
            }
            if (i420 != null) {
                final VideoFrame heartbeatFrame = new VideoFrame(i420, 0, System.nanoTime());
                capturerObserver.onFrameCaptured(heartbeatFrame);
                // heartbeatFrame.release()이 내부에서 i420.release()를 호출하므로
                // 별도로 i420.release()를 호출하면 double-release → refcount < 1 → CRASH
                heartbeatFrame.release();
            }
            heartbeatHandler.postDelayed(this, 1000L);
        }
    };

    /**
     * Constructs a new Screen Capturer.
     *
     * @param mediaProjectionPermissionResultData the result data of MediaProjection permission
     *                                            activity; the calling app must validate that result code is Activity.RESULT_OK before
     *                                            calling this method.
     * @param mediaProjectionCallback             MediaProjection callback to implement application specific
     *                                            logic in events such as when the user revokes a previously granted capture permission.
     **/
    public OrientationAwareScreenCapturer(Intent mediaProjectionPermissionResultData,
                                          MediaProjection.Callback mediaProjectionCallback) {
        this.mediaProjectionPermissionResultData = mediaProjectionPermissionResultData;
        this.mediaProjectionCallback = mediaProjectionCallback;
    }

    public void onFrame(VideoFrame frame) {
        checkNotDisposed();
        this.isPortrait = isDeviceOrientationPortrait();
        final int max = Math.max(this.height, this.width);
        final int min = Math.min(this.height, this.width);
        if (this.isPortrait) {
            changeCaptureFormat(min, max, 15);
        } else {
            changeCaptureFormat(max, min, 15);
        }
        // heartbeat용 I420 캐시 갱신 (실제 프레임 도착 시마다)
        synchronized (this) {
            if (heartbeatI420 != null) heartbeatI420.release();
            heartbeatI420 = frame.getBuffer().toI420();
        }
        capturerObserver.onFrameCaptured(frame);
    }

    private boolean isDeviceOrientationPortrait() {
        final Display display = windowManager.getDefaultDisplay();
        final DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        
        return metrics.heightPixels > metrics.widthPixels;
    }


    private void checkNotDisposed() {
        if (isDisposed) {
            throw new RuntimeException("capturer is disposed.");
        }
    }

    public synchronized void initialize(final SurfaceTextureHelper surfaceTextureHelper,
                                        final Context applicationContext, final CapturerObserver capturerObserver) {
        checkNotDisposed();
        if (capturerObserver == null) {
            throw new RuntimeException("capturerObserver not set.");
        }
        this.capturerObserver = capturerObserver;
        if (surfaceTextureHelper == null) {
            throw new RuntimeException("surfaceTextureHelper not set.");
        }
        this.surfaceTextureHelper = surfaceTextureHelper;

        this.windowManager = (WindowManager) applicationContext.getSystemService(
                Context.WINDOW_SERVICE);
        this.mediaProjectionManager = (MediaProjectionManager) applicationContext.getSystemService(
                Context.MEDIA_PROJECTION_SERVICE);
    }

    @Override
    public synchronized void startCapture(
            final int width, final int height, final int ignoredFramerate) {
        //checkNotDisposed();

        this.isPortrait = isDeviceOrientationPortrait();
        if (this.isPortrait) {
            this.width = width;
            this.height = height;
        } else {
            this.height = width;
            this.width = height;
        }

        this.oldWidth = this.width;
        this.oldHeight = this.height;

        mediaProjection = mediaProjectionManager.getMediaProjection(
                Activity.RESULT_OK, mediaProjectionPermissionResultData);

        // Let MediaProjection callback use the SurfaceTextureHelper thread.
        mediaProjection.registerCallback(mediaProjectionCallback, surfaceTextureHelper.getHandler());

        createVirtualDisplay();
        capturerObserver.onCapturerStarted(true);
        surfaceTextureHelper.startListening(this);
        heartbeatHandler.postDelayed(heartbeatRunnable, 1000L);
    }

    @Override
    public synchronized void stopCapture() {
        checkNotDisposed();
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        synchronized (this) {
            if (heartbeatI420 != null) { heartbeatI420.release(); heartbeatI420 = null; }
        }
        // Run release operations on the SurfaceTextureHelper thread without blocking
        // the caller thread to avoid potential deadlocks / ANRs. If posting fails,
        // fall back to the original synchronous invoke to remain safe.
        final Runnable releaseRunnable = new Runnable() {
            @Override
            public void run() {
                if (surfaceTextureHelper != null) {
                    surfaceTextureHelper.stopListening();
                }
                if (capturerObserver != null) {
                    capturerObserver.onCapturerStopped();
                }
                if (virtualDisplay != null) {
                    virtualDisplay.release();
                    virtualDisplay = null;
                }
                releaseVirtualDisplaySurface();
                if (mediaProjection != null) {
                    // Unregister the callback before stopping, otherwise the callback recursively
                    // calls this method.
                    try {
                        mediaProjection.unregisterCallback(mediaProjectionCallback);
                    } catch (Exception ignored) {
                    }
                    try {
                        mediaProjection.stop();
                    } catch (Exception ignored) {
                    }
                    mediaProjection = null;
                }
            }
        };

        android.os.Handler handler = null;
        try {
            if (surfaceTextureHelper != null) handler = surfaceTextureHelper.getHandler();
        } catch (Exception ignored) {
        }

        boolean posted = false;
        if (handler != null) {
            try {
                posted = handler.post(releaseRunnable);
            } catch (Exception ignored) {
                posted = false;
            }
        }

        if (!posted) {
            // If posting to the handler failed, fall back to synchronous invoke
            if (handler != null) {
                ThreadUtils.invokeAtFrontUninterruptibly(handler, releaseRunnable);
            } else {
                // No handler available — run inline as last resort
                releaseRunnable.run();
            }
        }
    }

    @Override
    public synchronized void dispose() {
        isDisposed = true;
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        synchronized (this) {
            if (heartbeatI420 != null) { heartbeatI420.release(); heartbeatI420 = null; }
        }
    }

    /**
     * Changes output video format. This method can be used to scale the output
     * video, or to change orientation when the captured screen is rotated for example.
     *
     * @param width            new output video width
     * @param height           new output video height
     * @param ignoredFramerate ignored
     */
    @Override
    public synchronized void changeCaptureFormat(
            final int width, final int height, final int ignoredFramerate) {
        checkNotDisposed();
        if (this.oldWidth != width || this.oldHeight != height) {
            this.width = width;
            this.height = height;
            this.oldWidth = width;
            this.oldHeight = height;

            if (height > width) {
                // Portrait: STH 스레드에서 즉시 resize
                ThreadUtils.invokeAtFrontUninterruptibly(surfaceTextureHelper.getHandler(), new Runnable() {
                    @Override
                    public void run() {
                        if (virtualDisplay != null && surfaceTextureHelper != null) {
                            surfaceTextureHelper.setTextureSize(width, height);
                            releaseVirtualDisplaySurface();
                            virtualDisplaySurface = new Surface(surfaceTextureHelper.getSurfaceTexture());
                            virtualDisplay.setSurface(virtualDisplaySurface);
                            virtualDisplay.resize(width, height, VIRTUAL_DISPLAY_DPI);
                        }
                    }
                });
            } else {
                // Landscape: setTextureSize 먼저, resize는 700ms 후
                ThreadUtils.invokeAtFrontUninterruptibly(surfaceTextureHelper.getHandler(), new Runnable() {
                    @Override
                    public void run() {
                        if (virtualDisplay != null && surfaceTextureHelper != null) {
                            surfaceTextureHelper.setTextureSize(width, height);
                            releaseVirtualDisplaySurface();
                            virtualDisplaySurface = new Surface(surfaceTextureHelper.getSurfaceTexture());
                            virtualDisplay.setSurface(virtualDisplaySurface);
                        }
                    }
                });
                heartbeatHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        ThreadUtils.invokeAtFrontUninterruptibly(surfaceTextureHelper.getHandler(), new Runnable() {
                            @Override
                            public void run() {
                                if (virtualDisplay != null) {
                                    virtualDisplay.resize(width, height, VIRTUAL_DISPLAY_DPI);
                                }
                            }
                        });
                    }
                }, 700);
            }
        }
    }

    private void createVirtualDisplay() {
        surfaceTextureHelper.setTextureSize(width, height);
        surfaceTextureHelper.getSurfaceTexture().setDefaultBufferSize(width, height);
        releaseVirtualDisplaySurface();
        virtualDisplaySurface = new Surface(surfaceTextureHelper.getSurfaceTexture());
        virtualDisplay = mediaProjection.createVirtualDisplay("WebRTC_ScreenCapture", width, height,
                VIRTUAL_DISPLAY_DPI, DISPLAY_FLAGS,
                virtualDisplaySurface, null /* callback */, null /* callback handler */);
    }

    private void releaseVirtualDisplaySurface() {
        if (virtualDisplaySurface != null) {
            virtualDisplaySurface.release();
            virtualDisplaySurface = null;
        }
    }

    @Override
    public boolean isScreencast() {
        return true;
    }

    public long getNumCapturedFrames() {
        return numCapturedFrames;
    }
}
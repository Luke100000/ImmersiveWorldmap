package net.conczin.immersive_worldmap.screen;


public class Camera3D {
    private static final float SMOOTH_HALF_LIFE = 0.07f;
    private static final float DECAY_HALF_LIFE = 0.12f;
    private static final float VELOCITY_EPSILON = 0.001f;

    private static final float ZOOM_MIN = 1f;
    private static final float ZOOM_MAX = 4000f;
    private static final float PITCH_MIN = -89f;
    private static final float PITCH_MAX = -5f;

    // desired state
    private float targetX = 0f, targetZ = 0f;
    private float targetYaw = 0f, targetPitch = -45f, targetZoom = 100f;

    // rendered (lerped) state
    private float smoothX, smoothZ, smoothYaw, smoothPitch, smoothZoom;

    // carry-on velocities (target-units per frame, decayed each tick)
    private float velX, velZ, velYaw, velPitch;

    private boolean isDraggingRotation = false;
    private boolean isDraggingPan = false;
    private int lastMouseX, lastMouseY;

    private static final float ROT_SENSITIVITY = 0.4f;   // px -> degrees
    private static final float WASD_SPEED = 0.015f; // fraction of zoom per tick

    private boolean keyW, keyA, keyS, keyD;
    private long lastTickNanos = 0;

    public Camera3D() {
        smoothX = targetX;
        smoothZ = targetZ;
        smoothYaw = targetYaw;
        smoothPitch = targetPitch;
        smoothZoom = targetZoom;
    }

    public void tick() {
        long now = System.nanoTime();
        float dt = lastTickNanos == 0 ? 0f : (now - lastTickNanos) / 1_000_000_000f;
        dt = Math.min(dt, 0.1f);
        lastTickNanos = now;

        float decay = (dt == 0f) ? 0f : (float) Math.pow(0.5, dt / DECAY_HALF_LIFE);
        float lerp = (dt == 0f) ? 0f : 1f - (float) Math.pow(0.5, dt / SMOOTH_HALF_LIFE);

        // WASD pan aligned to yaw. Convention: yaw=0 -> fwd = -Z, right = +X.
        if (keyW || keyA || keyS || keyD) {
            float yr = (float) Math.toRadians(smoothYaw);
            // forward vector in XZ (toward screen top)
            float fwdX = -(float) Math.sin(yr);
            float fwdZ = (float) Math.cos(yr);
            // right vector = rotate fwd 90 CW
            float rigZ = -fwdX;
            float speed = WASD_SPEED * smoothZoom;

            if (keyW) {
                targetX += fwdX * speed;
                targetZ += fwdZ * speed;
            }
            if (keyS) {
                targetX -= fwdX * speed;
                targetZ -= fwdZ * speed;
            }
            if (keyA) {
                targetX -= fwdZ * speed;
                targetZ -= rigZ * speed;
            }
            if (keyD) {
                targetX += fwdZ * speed;
                targetZ += rigZ * speed;
            }
        }

        targetX += velX;
        targetZ += velZ;
        targetYaw += velYaw;
        targetPitch += velPitch;

        targetPitch = Math.max(PITCH_MIN, Math.min(PITCH_MAX, targetPitch));
        targetZoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, targetZoom));

        velX *= decay;
        velZ *= decay;
        velYaw *= decay;
        velPitch *= decay;

        if (Math.abs(velX) < VELOCITY_EPSILON) velX = 0;
        if (Math.abs(velZ) < VELOCITY_EPSILON) velZ = 0;
        if (Math.abs(velYaw) < VELOCITY_EPSILON) velYaw = 0;
        if (Math.abs(velPitch) < VELOCITY_EPSILON) velPitch = 0;

        smoothX += (targetX - smoothX) * lerp;
        smoothZ += (targetZ - smoothZ) * lerp;
        smoothYaw += (targetYaw - smoothYaw) * lerp;
        smoothPitch += (targetPitch - smoothPitch) * lerp;
        smoothZoom += (targetZoom - smoothZoom) * lerp;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            isDraggingRotation = true;
            isDraggingPan = false;
            lastMouseX = (int) mouseX;
            lastMouseY = (int) mouseY;
            return true;
        } else if (button == 1) {
            isDraggingPan = true;
            isDraggingRotation = false;
            lastMouseX = (int) mouseX;
            lastMouseY = (int) mouseY;
            return true;
        }
        return false;
    }

    public boolean mouseReleased(int button) {
        if (button == 0 && isDraggingRotation) {
            isDraggingRotation = false;
            return true;
        }
        if (button == 1 && isDraggingPan) {
            isDraggingPan = false;
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, int screenW, int screenH) {
        int mx = (int) mouseX, my = (int) mouseY;
        int dx = mx - lastMouseX, dy = my - lastMouseY;
        lastMouseX = mx;
        lastMouseY = my;

        if (isDraggingRotation && button == 0) {
            float dYaw = dx * ROT_SENSITIVITY;
            float dPitch = dy * ROT_SENSITIVITY;
            targetYaw += dYaw;
            targetPitch = Math.max(PITCH_MIN, Math.min(PITCH_MAX, targetPitch + dPitch));
            velYaw = dYaw;
            velPitch = dPitch;
            return true;
        }
        if (isDraggingPan && button == 1) {
            applyPan(dx, dy, screenW, screenH);
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double scrollY) {
        float factor = (scrollY > 0) ? (1f / 1.12f) : 1.12f;
        targetZoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, targetZoom * factor));
        return true;
    }

    public boolean keyPressed(int keyCode) {
        switch (keyCode) {
            case 87 -> {
                keyW = true;
                return true;
            }
            case 83 -> {
                keyS = true;
                return true;
            }
            case 65 -> {
                keyA = true;
                return true;
            }
            case 68 -> {
                keyD = true;
                return true;
            }
        }
        return false;
    }

    public boolean keyReleased(int keyCode) {
        switch (keyCode) {
            case 87 -> {
                keyW = false;
                return true;
            }
            case 83 -> {
                keyS = false;
                return true;
            }
            case 65 -> {
                keyA = false;
                return true;
            }
            case 68 -> {
                keyD = false;
                return true;
            }
        }
        return false;
    }

    public float getSmoothX() {
        return smoothX;
    }

    public float getSmoothZ() {
        return smoothZ;
    }

    public float getSmoothYaw() {
        return smoothYaw;
    }

    public float getSmoothPitch() {
        return smoothPitch;
    }

    public float getSmoothZoom() {
        return smoothZoom;
    }

    public void setPan(float x, float z) {
        targetX = smoothX = x;
        targetZ = smoothZ = z;
    }

    public void setZoom(float zoom) {
        targetZoom = smoothZoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom));
    }

    public void applyPan(float dx, float dy, int screenW, int screenH) {
        float yr = (float) Math.toRadians(smoothYaw);
        float cosY = (float) Math.cos(yr);
        float sinY = (float) Math.sin(yr);
        float scale = smoothZoom / Math.min(screenW, screenH);

        float worldDX = (dx * cosY + dy * sinY) * scale;
        float worldDZ = (-dx * sinY + dy * cosY) * scale;

        targetX += worldDX;
        targetZ += worldDZ;
        velX = worldDX;
        velZ = worldDZ;
    }
}

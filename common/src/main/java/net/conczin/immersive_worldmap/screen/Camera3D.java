package net.conczin.immersive_worldmap.screen;


public class Camera3D {
    private static final float SMOOTH_HALF_LIFE = 0.025f;
    private static final float DECAY_HALF_LIFE = 0.05f;
    private static final float VELOCITY_EPSILON = 0.001f;

    private static final float ZOOM_MIN = 96f;
    private static final float ZOOM_MAX = 10000f;
    private static final float PITCH_MIN = -89.9f;
    private static final float PITCH_MAX = -25f;

    // desired state
    private float targetX, targetY, targetZ;
    private float targetYaw = 0f;
    private float targetPitch = -45f;
    private float targetZoom = 100f;

    // rendered (lerped) state
    private float smoothX, smoothY, smoothZ, smoothYaw, smoothPitch, smoothZoom;

    // carry-on velocities (target-units per frame, decayed each tick)
    private float velX, velZ, velYaw, velPitch;

    private boolean isDraggingRotation = false;
    private boolean isDraggingPan = false;
    private int lastMouseX, lastMouseY;

    private static final float ROT_SENSITIVITY = 0.4f;   // px -> degrees
    private static final float WASD_SPEED = 0.01f; // fraction of zoom per tick
    private static final float KEY_ROTATION_SPEED = 150f; // degrees per second

    private static final int MOUSE_PAN = 0;
    private static final int MOUSE_ROTATE = 1;

    private static final int KEY_W = 87;
    private static final int KEY_A = 65;
    private static final int KEY_S = 83;
    private static final int KEY_D = 68;
    private static final int KEY_Q = 81;
    private static final int KEY_E = 69;
    private static final int KEY_RIGHT = 262;
    private static final int KEY_LEFT = 263;
    private static final int KEY_DOWN = 264;
    private static final int KEY_UP = 265;

    private boolean keyW, keyA, keyS, keyD, keyQ, keyE;
    private long lastTickNanos = 0;

    public Camera3D() {
        smoothX = targetX;
        smoothY = targetY;
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

        // WASD pan relative to camera yaw. Matches the view basis:
        // screen-forward = (sin yaw, cos yaw), screen-right = f x up = (-cos yaw, sin yaw).
        if (keyW || keyA || keyS || keyD) {
            float yr = (float) Math.toRadians(smoothYaw);
            float fwdX = (float) Math.sin(yr);
            float fwdZ = (float) Math.cos(yr);
            float rigX = -(float) Math.cos(yr);
            float rigZ = (float) Math.sin(yr);
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
                targetX -= rigX * speed;
                targetZ -= rigZ * speed;
            }
            if (keyD) {
                targetX += rigX * speed;
                targetZ += rigZ * speed;
            }
        }

        targetX += velX;
        targetZ += velZ;
        targetYaw += ((keyQ ? 1 : 0) - (keyE ? 1 : 0)) * KEY_ROTATION_SPEED * dt;
        targetYaw += velYaw;
        targetPitch += velPitch;

        targetPitch = Math.clamp(targetPitch, PITCH_MIN, PITCH_MAX);
        targetZoom = Math.clamp(targetZoom, ZOOM_MIN, ZOOM_MAX);

        velX *= decay;
        velZ *= decay;
        velYaw *= decay;
        velPitch *= decay;

        if (Math.abs(velX) < VELOCITY_EPSILON) velX = 0;
        if (Math.abs(velZ) < VELOCITY_EPSILON) velZ = 0;
        if (Math.abs(velYaw) < VELOCITY_EPSILON) velYaw = 0;
        if (Math.abs(velPitch) < VELOCITY_EPSILON) velPitch = 0;

        smoothX += (targetX - smoothX) * lerp;
        smoothY += (targetY - smoothY) * lerp;
        smoothZ += (targetZ - smoothZ) * lerp;
        smoothYaw += (targetYaw - smoothYaw) * lerp;
        smoothPitch += (targetPitch - smoothPitch) * lerp;
        smoothZoom += (targetZoom - smoothZoom) * lerp;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == MOUSE_ROTATE) {
            isDraggingRotation = true;
            isDraggingPan = false;
            lastMouseX = (int) mouseX;
            lastMouseY = (int) mouseY;
            return true;
        } else if (button == MOUSE_PAN) {
            isDraggingPan = true;
            isDraggingRotation = false;
            lastMouseX = (int) mouseX;
            lastMouseY = (int) mouseY;
            return true;
        }
        return false;
    }

    public boolean mouseReleased(int button) {
        if (button == MOUSE_ROTATE && isDraggingRotation) {
            isDraggingRotation = false;
            return true;
        }
        if (button == MOUSE_PAN && isDraggingPan) {
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

        if (isDraggingRotation && button == MOUSE_ROTATE) {
            float dYaw = dx * ROT_SENSITIVITY;
            float dPitch = dy * ROT_SENSITIVITY;
            targetYaw -= dYaw;
            targetPitch = Math.clamp(targetPitch - dPitch, PITCH_MIN, PITCH_MAX);
            velYaw = -dYaw;
            velPitch = -dPitch;
            return true;
        }
        if (isDraggingPan && button == MOUSE_PAN) {
            applyPan(dx, dy, screenW, screenH);
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double scrollY) {
        float factor = (scrollY > 0) ? (1f / 1.12f) : 1.12f;
        targetZoom = Math.clamp(targetZoom * factor, ZOOM_MIN, ZOOM_MAX);
        return true;
    }

    public boolean keyPressed(int keyCode) {
        switch (keyCode) {
            case KEY_W, KEY_UP -> {
                keyW = true;
                return true;
            }
            case KEY_S, KEY_DOWN -> {
                keyS = true;
                return true;
            }
            case KEY_A, KEY_LEFT -> {
                keyA = true;
                return true;
            }
            case KEY_D, KEY_RIGHT -> {
                keyD = true;
                return true;
            }
            case KEY_Q -> {
                keyQ = true;
                return true;
            }
            case KEY_E -> {
                keyE = true;
                return true;
            }
        }
        return false;
    }

    public boolean keyReleased(int keyCode) {
        switch (keyCode) {
            case KEY_W, KEY_UP -> {
                keyW = false;
                return true;
            }
            case KEY_S, KEY_DOWN -> {
                keyS = false;
                return true;
            }
            case KEY_A, KEY_LEFT -> {
                keyA = false;
                return true;
            }
            case KEY_D, KEY_RIGHT -> {
                keyD = false;
                return true;
            }
            case KEY_Q -> {
                keyQ = false;
                return true;
            }
            case KEY_E -> {
                keyE = false;
                return true;
            }
        }
        return false;
    }

    public float getSmoothTargetX() {
        return smoothX;
    }

    public float getSmoothTargetY() {
        return smoothY;
    }

    public float getSmoothTargetZ() {
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

    public void setTarget(float x, float y, float z) {
        targetX = smoothX = x;
        targetY = smoothY = y;
        targetZ = smoothZ = z;
    }

    public void setZoom(float zoom) {
        targetZoom = smoothZoom = Math.clamp(zoom, ZOOM_MIN, ZOOM_MAX);
    }

    public void applyPan(float dx, float dy, int screenW, int screenH) {
        float yr = (float) Math.toRadians(smoothYaw);
        float cosY = (float) Math.cos(yr);
        float sinY = (float) Math.sin(yr);
        float scale = smoothZoom / (Math.min(screenW, screenH));

        float worldDX = (dx * cosY + dy * sinY) * scale;
        float worldDZ = (-dx * sinY + dy * cosY) * scale;

        targetX += worldDX;
        targetZ += worldDZ;
        velX = worldDX;
        velZ = worldDZ;
    }
}

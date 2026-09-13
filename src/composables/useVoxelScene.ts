import { onBeforeUnmount, onMounted, ref, shallowRef, watch, type Ref } from "vue";
import * as THREE from "three";
import type { MeshBounds, MeshData } from "../protocol/mesh-v1";

const DEFAULT_WIDTH = 560;
const DEFAULT_HEIGHT = 360;
const MAX_PIXEL_RATIO = 2;
const MIN_ZOOM = 0.25;
const MAX_ZOOM = 4;
const DEFAULT_YAW = Math.PI / 4;
const DEFAULT_PITCH = Math.PI * 0.22;

export interface CameraFit {
  readonly target: { x: number; y: number; z: number };
  readonly radius: number;
  readonly distance: number;
  readonly near: number;
  readonly far: number;
}

export interface SceneBundle {
  readonly scene: THREE.Scene;
  readonly geometry: THREE.BufferGeometry;
  readonly materials: THREE.MeshLambertMaterial[];
  readonly mesh: THREE.Mesh<THREE.BufferGeometry, THREE.MeshLambertMaterial[]>;
}

interface PendingScene {
  readonly bundle: SceneBundle;
  readonly fit: CameraFit;
  readonly bounds: MeshBounds | null;
  readonly data: MeshData;
}

function alphaFromBits(bits: number): number {
  const bytes = new ArrayBuffer(4);
  const view = new DataView(bytes);
  view.setUint32(0, bits, true);
  return view.getFloat32(0, true);
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.max(minimum, Math.min(maximum, value));
}

export function calculateCameraFit(bounds: MeshBounds | null, aspect: number, fovDegrees = 45): CameraFit {
  if (!bounds) {
    return { target: { x: 0, y: 0, z: 0 }, radius: 1, distance: 4, near: 0.1, far: 16 };
  }
  const target = {
    x: (bounds.minX + bounds.maxX) / 2,
    y: (bounds.minY + bounds.maxY) / 2,
    z: (bounds.minZ + bounds.maxZ) / 2,
  };
  const width = bounds.maxX - bounds.minX;
  const height = bounds.maxY - bounds.minY;
  const depth = bounds.maxZ - bounds.minZ;
  const radius = Math.max(0.5, Math.hypot(width, height, depth) / 2);
  const verticalFov = THREE.MathUtils.degToRad(fovDegrees);
  const safeAspect = Number.isFinite(aspect) && aspect > 0 ? aspect : 1;
  const horizontalFov = 2 * Math.atan(Math.tan(verticalFov / 2) * safeAspect);
  const limitingFov = Math.min(verticalFov, horizontalFov);
  const distance = Math.max(2, (radius / Math.sin(limitingFov / 2)) * 1.15);
  return {
    target,
    radius,
    distance,
    near: Math.max(0.05, distance - radius * 2),
    far: Math.max(distance + radius * 3, 32),
  };
}

export function createSceneBundle(data: MeshData): SceneBundle {
  const geometry = new THREE.BufferGeometry();
  const materials: THREE.MeshLambertMaterial[] = [];
  try {
    geometry.setAttribute("position", new THREE.BufferAttribute(data.positions, 3));
    geometry.setAttribute("normal", new THREE.BufferAttribute(data.normals, 3));
    geometry.setIndex(new THREE.BufferAttribute(data.indices, 1));
    for (let offset = 0; offset < data.batches.length; offset += 8) {
      const layer = data.batches[offset];
      const alpha = alphaFromBits(data.batches[offset + 2]);
      const material = new THREE.MeshLambertMaterial({
        color: data.batches[offset + 1],
        opacity: alpha,
        transparent: alpha < 1,
        depthWrite: alpha >= 1,
        side: THREE.FrontSide,
      });
      material.name = `mcwe-layer-${layer}`;
      materials.push(material);
      geometry.addGroup(data.batches[offset + 3], data.batches[offset + 4], materials.length - 1);
    }
    const mesh = new THREE.Mesh(geometry, materials);
    const scene = new THREE.Scene();
    scene.background = new THREE.Color(0x0d141a);
    scene.add(new THREE.HemisphereLight(0xffffff, 0x243443, 1.6));
    const sunlight = new THREE.DirectionalLight(0xffffff, 2.2);
    sunlight.position.set(1, 2, 1);
    scene.add(sunlight, mesh);
    return { scene, geometry, materials, mesh };
  } catch (reason) {
    geometry.dispose();
    for (const material of materials) material.dispose();
    throw reason;
  }
}

export function disposeSceneBundle(bundle: SceneBundle): void {
  bundle.scene.remove(bundle.mesh);
  bundle.geometry.dispose();
  for (const material of bundle.materials) material.dispose();
}

export function useVoxelScene(data: Readonly<Ref<MeshData>>) {
  const host = ref<HTMLDivElement>();
  const state = ref<"ready" | "context-lost" | "error">("ready");
  const presentedData = shallowRef(data.value);
  let renderer: THREE.WebGLRenderer | null = null;
  let camera: THREE.PerspectiveCamera | null = null;
  let current: PendingScene | null = null;
  let pending: PendingScene | null = null;
  let resizeObserver: ResizeObserver | null = null;
  let frame: number | null = null;
  let contextLost = false;
  let yaw = DEFAULT_YAW;
  let pitch = DEFAULT_PITCH;
  let zoom = 1;
  let pointerId: number | null = null;
  let pointerX = 0;
  let pointerY = 0;
  let pointerButton = 0;
  let rotating = false;
  const targetOffset = new THREE.Vector3();
  let renderWidth = 0;
  let renderHeight = 0;
  let pixelRatio = 0;

  function activeScene(): PendingScene | null {
    return pending ?? current;
  }

  function applyCamera(fit: CameraFit): void {
    if (!camera) return;
    const distance = fit.distance * zoom;
    const horizontal = Math.cos(pitch) * distance;
    camera.position.set(
      fit.target.x + targetOffset.x + Math.sin(yaw) * horizontal,
      fit.target.y + targetOffset.y + Math.sin(pitch) * distance,
      fit.target.z + targetOffset.z + Math.cos(yaw) * horizontal,
    );
    camera.near = Math.max(0.05, fit.near * zoom);
    camera.far = Math.max(camera.near + 1, fit.far * Math.max(1, zoom));
    camera.lookAt(fit.target.x + targetOffset.x, fit.target.y + targetOffset.y, fit.target.z + targetOffset.z);
    camera.updateProjectionMatrix();
  }

  function renderFrame(): void {
    frame = null;
    if (!renderer || !camera || contextLost) return;
    const next = activeScene();
    if (!next) return;
    applyCamera(next.fit);
    try {
      renderer.render(next.bundle.scene, camera);
      if (pending === next) {
        const previous = current;
        current = next;
        pending = null;
        presentedData.value = next.data;
        if (previous) disposeSceneBundle(previous.bundle);
      }
      state.value = "ready";
    } catch {
      if (pending === next) {
        disposeSceneBundle(next.bundle);
        pending = null;
      }
      if (current) applyCamera(current.fit);
      state.value = "error";
    }
  }

  function requestFrame(): void {
    if (frame === null && renderer && !contextLost) {
      frame = globalThis.requestAnimationFrame(renderFrame);
    }
  }

  function prepareScene(nextData: MeshData): void {
    try {
      const next = {
        bundle: createSceneBundle(nextData),
        fit: calculateCameraFit(nextData.metadata.bounds, camera?.aspect ?? DEFAULT_WIDTH / DEFAULT_HEIGHT),
        bounds: nextData.metadata.bounds,
        data: nextData,
      };
      if (pending) disposeSceneBundle(pending.bundle);
      pending = next;
      yaw = DEFAULT_YAW;
      pitch = DEFAULT_PITCH;
      zoom = 1;
      targetOffset.set(0, 0, 0);
      cancelPointer();
      requestFrame();
    } catch {
      if (pending) disposeSceneBundle(pending.bundle);
      pending = null;
      if (frame !== null) globalThis.cancelAnimationFrame(frame);
      frame = null;
      state.value = "error";
    }
  }

  function resize(): boolean {
    if (!renderer || !camera || !host.value) return true;
    const rect = host.value.getBoundingClientRect();
    const width = Math.max(1, Math.round(rect.width || DEFAULT_WIDTH));
    const height = Math.max(1, Math.round(rect.height || DEFAULT_HEIGHT));
    const nextPixelRatio = Math.min(
      MAX_PIXEL_RATIO,
      Number.isFinite(globalThis.devicePixelRatio) && globalThis.devicePixelRatio > 0
        ? globalThis.devicePixelRatio
        : 1,
    );
    if (width === renderWidth && height === renderHeight && nextPixelRatio === pixelRatio) return true;
    try {
      renderer.setPixelRatio(nextPixelRatio);
      renderer.setSize(width, height, false);
      camera.aspect = width / height;
      camera.updateProjectionMatrix();
      if (current) current = { ...current, fit: calculateCameraFit(current.bounds, camera.aspect) };
      if (pending) pending = { ...pending, fit: calculateCameraFit(pending.bounds, camera.aspect) };
      renderWidth = width;
      renderHeight = height;
      pixelRatio = nextPixelRatio;
      requestFrame();
      return true;
    } catch {
      state.value = "error";
      return false;
    }
  }

  function changeView(nextYaw: number, nextPitch: number, nextZoom: number): void {
    yaw = nextYaw;
    pitch = clamp(nextPitch, -Math.PI * 0.47, Math.PI * 0.47);
    zoom = clamp(nextZoom, MIN_ZOOM, MAX_ZOOM);
    requestFrame();
  }

  function resetView(): void {
    cancelPointer();
    targetOffset.set(0, 0, 0);
    changeView(DEFAULT_YAW, DEFAULT_PITCH, 1);
  }

  function pointerDown(event: PointerEvent): void {
    if ((event.button !== 0 && event.button !== 2) || pointerId !== null || contextLost) return;
    pointerId = event.pointerId;
    pointerButton = event.button;
    rotating = false;
    pointerX = event.clientX;
    pointerY = event.clientY;
    host.value?.setPointerCapture?.(event.pointerId);
    host.value?.focus({ preventScroll: true });
    event.preventDefault();
  }

  function pointerMove(event: PointerEvent): void {
    if (pointerId !== event.pointerId) return;
    const deltaX = event.clientX - pointerX;
    const deltaY = event.clientY - pointerY;
    if (pointerButton === 0 && !rotating) {
      if (deltaX * deltaX + deltaY * deltaY <= 16) return;
      rotating = true;
    }
    pointerX = event.clientX;
    pointerY = event.clientY;
    if (pointerButton === 0) {
      changeView(yaw - deltaX * 0.01, pitch + deltaY * 0.01, zoom);
    } else {
      const fit = activeScene()?.fit;
      if (!fit || !camera) return;
      // Java ViewerOrbitCameraState.panPixels: logical pixels, not device pixels.
      const scale = 2 * fit.distance * zoom * Math.tan(THREE.MathUtils.degToRad(camera.fov) / 2)
        / Math.max(1, renderHeight);
      targetOffset.x += (-Math.cos(yaw) * deltaX - Math.sin(yaw) * Math.sin(pitch) * deltaY) * scale;
      targetOffset.y += Math.cos(pitch) * deltaY * scale;
      targetOffset.z += (Math.sin(yaw) * deltaX - Math.cos(yaw) * Math.sin(pitch) * deltaY) * scale;
      targetOffset.clampLength(0, fit.radius * 2);
      requestFrame();
    }
  }

  function pointerUp(event: PointerEvent): void {
    if (pointerId !== event.pointerId) return;
    if (event.type === "pointerup" && event.button !== pointerButton) return;
    cancelPointer();
  }

  function cancelPointer(): void {
    const previous = pointerId;
    pointerId = null;
    rotating = false;
    if (previous !== null && host.value?.hasPointerCapture?.(previous)) {
      host.value.releasePointerCapture(previous);
    }
  }

  function wheel(event: WheelEvent): void {
    changeView(yaw, pitch, zoom * Math.exp(event.deltaY * 0.001));
    event.preventDefault();
  }

  function keyDown(event: KeyboardEvent): void {
    const step = Math.PI / 36;
    if (event.key === "ArrowLeft") changeView(yaw + step, pitch, zoom);
    else if (event.key === "ArrowRight") changeView(yaw - step, pitch, zoom);
    else if (event.key === "ArrowUp") changeView(yaw, pitch + step, zoom);
    else if (event.key === "ArrowDown") changeView(yaw, pitch - step, zoom);
    else if (event.key === "+" || event.key === "=") changeView(yaw, pitch, zoom / 1.15);
    else if (event.key === "-" || event.key === "_") changeView(yaw, pitch, zoom * 1.15);
    else if (event.key === "Home" || event.key.toLowerCase() === "r") resetView();
    else return;
    event.preventDefault();
  }

  function contextLostHandler(event: Event): void {
    event.preventDefault();
    contextLost = true;
    cancelPointer();
    if (frame !== null) globalThis.cancelAnimationFrame(frame);
    frame = null;
    state.value = "context-lost";
  }

  function contextRestoredHandler(): void {
    contextLost = false;
    renderer?.resetState();
    requestFrame();
  }

  function releaseRuntime(): void {
    cancelPointer();
    resizeObserver?.disconnect();
    resizeObserver = null;
    globalThis.removeEventListener("resize", resize);
    if (frame !== null) globalThis.cancelAnimationFrame(frame);
    frame = null;
    if (pending) disposeSceneBundle(pending.bundle);
    if (current) disposeSceneBundle(current.bundle);
    pending = null;
    current = null;
    if (renderer) {
      renderer.domElement.removeEventListener("webglcontextlost", contextLostHandler);
      renderer.domElement.removeEventListener("webglcontextrestored", contextRestoredHandler);
      renderer.setAnimationLoop(null);
      renderer.dispose();
      renderer.forceContextLoss();
    }
    renderer = null;
    camera = null;
    host.value?.replaceChildren();
  }

  onMounted(() => {
    if (!host.value) return;
    try {
      renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false, powerPreference: "high-performance" });
      renderer.outputColorSpace = THREE.SRGBColorSpace;
      camera = new THREE.PerspectiveCamera(45, DEFAULT_WIDTH / DEFAULT_HEIGHT, 0.1, 4096);
      renderer.domElement.addEventListener("webglcontextlost", contextLostHandler);
      renderer.domElement.addEventListener("webglcontextrestored", contextRestoredHandler);
      host.value.append(renderer.domElement);
      if (typeof ResizeObserver !== "undefined") {
        resizeObserver = new ResizeObserver(resize);
        resizeObserver.observe(host.value);
      }
      globalThis.addEventListener("resize", resize);
      if (!resize()) throw new Error("renderer resize failed");
      prepareScene(data.value);
    } catch {
      releaseRuntime();
      state.value = "error";
    }
  });

  watch(data, prepareScene);

  onBeforeUnmount(releaseRuntime);

  return {
    host,
    state,
    presentedData,
    resetView,
    pointerDown,
    pointerMove,
    pointerUp,
    cancelPointer,
    wheel,
    keyDown,
  };
}

import { useEffect, useRef } from 'react'
import {
  BoxGeometry,
  Clock,
  Color,
  DoubleSide,
  EdgesGeometry,
  Group,
  LineBasicMaterial,
  LineSegments,
  Mesh,
  PerspectiveCamera,
  Scene,
  ShaderMaterial,
  Vector3,
  WebGLRenderer,
} from 'three'
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js'

export interface GlassCubeProps {
  className?: string
  /** Called once, after the first frame is on screen. */
  onReady?: () => void
  /** Called when WebGL cannot start, so the caller can show the flat mark instead. */
  onFail?: () => void
  /** Turns per second about the cube's vertical diagonal. */
  spin?: number
  /** Tilt toward the pointer. */
  interactive?: boolean
}

const VERTEX = /* glsl */ `
  varying vec3 vNormal;
  varying vec3 vView;
  varying vec3 vPos;
  void main() {
    vec4 mv = modelViewMatrix * vec4(position, 1.0);
    vNormal = normalize(normalMatrix * normal);
    vView = normalize(-mv.xyz);
    vPos = position;
    gl_Position = projectionMatrix * mv;
  }
`

/**
 * Thin glass, not a solid: the middle of every face is almost clear and the page behind shows through; light gathers where the surface turns away from the viewer
 * (the Fresnel edge), picks up a colour fringe that shifts with the angle of view, and three soft studio lights draw bright streaks. It is drawn in display colours,
 * so there is no tone mapping and no environment map, and it costs one cheap pass instead of the refraction pass that real transmission needs.
 */
const FRAGMENT = /* glsl */ `
  uniform vec3 uTintA;
  uniform vec3 uTintB;
  uniform float uTime;
  uniform float uClear;
  uniform float uStrength;
  varying vec3 vNormal;
  varying vec3 vView;
  varying vec3 vPos;
  void main() {
    vec3 n = normalize(vNormal);
    if (!gl_FrontFacing) n = -n;
    vec3 v = normalize(vView);
    float ndv = clamp(abs(dot(n, v)), 0.0, 1.0);
    float fres = pow(1.0 - ndv, 2.2);

    // the colour fringe only ever uses the brand's three colours (cyan, violet, rose), travelling with the angle of view
    float t = 0.5 + 0.5 * sin(6.28318 * (fres * 0.9 + vPos.y * 0.11 + uTime * 0.02));
    vec3 fringe = mix(mix(vec3(0.40, 0.91, 0.98), vec3(0.65, 0.55, 0.98), smoothstep(0.0, 0.55, t)), vec3(0.94, 0.67, 0.99), smoothstep(0.45, 1.0, t));
    vec3 body = mix(uTintA, uTintB, 0.5 + 0.5 * n.y);

    vec3 r = reflect(-v, n);
    float s1 = pow(max(dot(r, normalize(vec3(-0.55, 0.85, 0.55))), 0.0), 56.0);
    float s2 = pow(max(dot(r, normalize(vec3(0.75, -0.25, 0.65))), 0.0), 36.0);
    float s3 = pow(max(dot(r, normalize(vec3(0.05, 0.2, 1.0))), 0.0), 90.0);

    vec3 colour = body * 0.2 + fringe * fres * 0.95 + vec3(1.0) * (s1 * 0.95 + s2 * 0.6 + s3 * 0.8);
    float alpha = uClear + fres * 0.5 + s1 * 0.7 + s2 * 0.45 + s3 * 0.6;
    gl_FragColor = vec4(colour, clamp(alpha * uStrength, 0.0, 1.0));
  }
`

function glassMaterial(tintA: number, tintB: number, clear: number, strength: number): ShaderMaterial {
  return new ShaderMaterial({
    vertexShader: VERTEX,
    fragmentShader: FRAGMENT,
    uniforms: {
      uTintA: { value: new Color(tintA) },
      uTintB: { value: new Color(tintB) },
      uTime: { value: 0 },
      uClear: { value: clear },
      uStrength: { value: strength },
    },
    transparent: true,
    depthWrite: false,
    side: DoubleSide,
  })
}

/**
 * The Glassbox cube as a real object: transparent glass balanced on one corner (its body diagonal stands vertical) and turning slowly, with a smaller cube turning the
 * other way inside it and every edge drawn as a thin bright line. The canvas is transparent, so the page's own colour shows through the glass. Plain three.js,
 * loaded only on the splash and sign-in screens.
 *
 * It looks after itself: it stops drawing when the tab is hidden or the cube is off screen, drops its resolution if frames run long, and frees every GPU resource
 * when it unmounts. If WebGL cannot start it calls `onFail` and draws nothing.
 */
export default function GlassCube({ className, onReady, onFail, spin = 0.085, interactive = true }: GlassCubeProps) {
  const host = useRef<HTMLDivElement>(null)
  const callbacks = useRef({ onReady, onFail })
  callbacks.current = { onReady, onFail }

  useEffect(() => {
    const container = host.current
    if (!container) return

    let renderer: WebGLRenderer
    try {
      renderer = new WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'high-performance' })
    } catch {
      callbacks.current.onFail?.()
      return
    }
    renderer.setClearColor(0x000000, 0)
    let pixelRatio = Math.min(window.devicePixelRatio || 1, 2)
    renderer.setPixelRatio(pixelRatio)
    renderer.domElement.style.cssText = 'display:block;width:100%;height:100%'
    renderer.domElement.setAttribute('aria-hidden', 'true')
    container.appendChild(renderer.domElement)

    const scene = new Scene()
    const camera = new PerspectiveCamera(32, 1, 0.1, 60)
    camera.position.set(0, 1.6, 8.6)
    camera.lookAt(0, 0, 0)

    const outerMaterial = glassMaterial(0x9bdcff, 0xd0a8ff, 0.05, 1)
    const innerMaterial = glassMaterial(0x67e8f9, 0xf0abfc, 0.07, 0.95)
    const outer = new Mesh(new RoundedBoxGeometry(2, 2, 2, 5, 0.09), outerMaterial)
    const inner = new Mesh(new RoundedBoxGeometry(1, 1, 1, 4, 0.06), innerMaterial)
    const outerLines = new EdgesGeometry(new BoxGeometry(2, 2, 2))
    const innerLines = new EdgesGeometry(new BoxGeometry(1, 1, 1))
    const outerEdge = new LineBasicMaterial({ color: 0xe9eeff, transparent: true, opacity: 0.8 })
    const innerEdge = new LineBasicMaterial({ color: 0x9be7f5, transparent: true, opacity: 0.55 })
    const outerWire = new LineSegments(outerLines, outerEdge)
    const innerWire = new LineSegments(innerLines, innerEdge)
    const core = new Group()
    core.add(inner, innerWire)

    // the cube's body diagonal (1,1,1) is turned to point straight up, then the whole thing turns about that axis
    const body = new Group()
    body.add(outer, outerWire, core)
    body.quaternion.setFromUnitVectors(new Vector3(1, 1, 1).normalize(), new Vector3(0, 1, 0))
    const spinner = new Group()
    spinner.add(body)
    const root = new Group()
    const BASE_TILT = 0.3
    root.rotation.x = BASE_TILT
    root.add(spinner)
    scene.add(root)

    function resize() {
      const w = Math.max(1, container!.clientWidth)
      const h = Math.max(1, container!.clientHeight)
      renderer.setPixelRatio(pixelRatio)
      renderer.setSize(w, h, false)
      camera.aspect = w / h
      // keep the whole cube in view in a narrow, tall box too
      camera.fov = camera.aspect < 0.8 ? 32 / Math.max(0.55, camera.aspect) : 32
      camera.updateProjectionMatrix()
    }
    resize()
    const resizeObserver = new ResizeObserver(resize)
    resizeObserver.observe(container)

    const pointer = { x: 0, y: 0 }
    const onPointerMove = (e: PointerEvent) => {
      const box = container.getBoundingClientRect()
      pointer.x = Math.max(-1, Math.min(1, ((e.clientX - box.left) / box.width) * 2 - 1))
      pointer.y = Math.max(-1, Math.min(1, ((e.clientY - box.top) / box.height) * 2 - 1))
    }
    if (interactive) window.addEventListener('pointermove', onPointerMove, { passive: true })

    let onScreen = true
    const visibility = new IntersectionObserver((entries) => {
      onScreen = entries[entries.length - 1]?.isIntersecting ?? true
    })
    visibility.observe(container)

    const clock = new Clock()
    let elapsed = 0
    let frameId = 0
    let frames = 0
    let slowTime = 0
    let announced = false

    const tick = () => {
      frameId = requestAnimationFrame(tick)
      const dt = Math.min(clock.getDelta(), 0.05)
      if (document.hidden || !onScreen) return
      elapsed += dt
      spinner.rotation.y += dt * spin * Math.PI * 2
      core.rotation.y -= dt * 0.5
      core.rotation.x += dt * 0.2
      root.position.y = Math.sin(elapsed * 0.9) * 0.07
      root.rotation.x += (BASE_TILT + pointer.y * 0.2 - root.rotation.x) * Math.min(1, dt * 3)
      root.rotation.z += (-pointer.x * 0.2 - root.rotation.z) * Math.min(1, dt * 3)
      outerMaterial.uniforms.uTime.value = elapsed
      innerMaterial.uniforms.uTime.value = elapsed
      renderer.render(scene, camera)

      if (!announced) {
        announced = true
        callbacks.current.onReady?.()
      }
      // quality follows speed: after a settling period, a slow average lowers the pixel ratio once
      frames++
      if (frames > 30) {
        slowTime += dt
        if (frames % 90 === 0) {
          const average = slowTime / 60
          slowTime = 0
          if (average > 0.028 && pixelRatio > 1) {
            pixelRatio = 1
            resize()
          }
        }
      }
    }
    frameId = requestAnimationFrame(tick)

    return () => {
      cancelAnimationFrame(frameId)
      resizeObserver.disconnect()
      visibility.disconnect()
      window.removeEventListener('pointermove', onPointerMove)
      outer.geometry.dispose()
      inner.geometry.dispose()
      outerLines.dispose()
      innerLines.dispose()
      outerMaterial.dispose()
      innerMaterial.dispose()
      outerEdge.dispose()
      innerEdge.dispose()
      renderer.dispose()
      renderer.forceContextLoss()
      renderer.domElement.remove()
    }
  }, [spin, interactive])

  return <div ref={host} className={className} />
}

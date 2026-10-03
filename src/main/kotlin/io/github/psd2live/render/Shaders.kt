package io.github.psd2live.render

/**
 * The canvas renderer's GLSL, 330 core.
 *
 * World to clip space is one affine map per axis, `clip = u_axis.xy * world + ...`, carried as
 * `u_world = (ax, cx, ay, cy)`: `clip.x = ax * world.x + cx`, `clip.y = ay * world.y + cy`. It maps the screen's
 * top row to the framebuffer's bottom row, so a plain glReadPixels comes back top row first.
 *
 * Everything is premultiplied: the atlas is uploaded premultiplied, the shaders write premultiplied colour
 * and blending is ONE, ONE_MINUS_SRC_ALPHA, as Skia composites.
 */
internal object Shaders {
	const val ARTWORK_VERTEX = """#version 330 core
layout(location = 0) in vec2 a_position;
layout(location = 1) in vec2 a_uv;
uniform vec4 u_world;
out vec2 v_uv;
void main() {
    v_uv = a_uv;
    gl_Position = vec4(u_world.x * a_position.x + u_world.y, u_world.z * a_position.y + u_world.w, 0.0, 1.0);
}
"""

	/** Textured when u_solid.a is negative; a flat premultiplied colour otherwise (the hover wash, mask writes). */
	const val ARTWORK_FRAGMENT = """#version 330 core
in vec2 v_uv;
uniform sampler2D u_texture;
uniform float u_opacity;
uniform vec4 u_solid;
out vec4 o_color;
void main() {
    o_color = u_solid.a < 0.0 ? texture(u_texture, v_uv) * u_opacity : u_solid;
}
"""

	/**
	 * One quad per line segment, instanced. The quad is widened by a pixel past the stroke so the fragment
	 * shader can fade the edge by its distance from the centre line.
	 */
	const val LINE_VERTEX = """#version 330 core
layout(location = 0) in vec2 a_corner;
layout(location = 1) in vec4 a_segment;
uniform vec4 u_world;
uniform vec2 u_viewport;
uniform float u_width;
out float v_across;
void main() {
    vec2 a = vec2(u_world.x * a_segment.x + u_world.y, u_world.z * a_segment.y + u_world.w);
    vec2 b = vec2(u_world.x * a_segment.z + u_world.y, u_world.z * a_segment.w + u_world.w);
    vec2 pa = (a * 0.5 + 0.5) * u_viewport;
    vec2 pb = (b * 0.5 + 0.5) * u_viewport;
    vec2 along = pb - pa;
    float span = max(length(along), 1e-4);
    vec2 dir = along / span;
    vec2 normal = vec2(-dir.y, dir.x);
    float reach = u_width * 0.5 + 1.0;
    vec2 p = mix(pa, pb, a_corner.x) + normal * (a_corner.y * reach) + dir * ((a_corner.x * 2.0 - 1.0) * 0.5);
    v_across = a_corner.y * reach;
    gl_Position = vec4(p / u_viewport * 2.0 - 1.0, 0.0, 1.0);
}
"""

	const val LINE_FRAGMENT = """#version 330 core
in float v_across;
uniform float u_width;
uniform vec4 u_color;
out vec4 o_color;
void main() {
    float coverage = clamp(u_width * 0.5 + 0.5 - abs(v_across), 0.0, 1.0);
    o_color = u_color * coverage;
}
"""

	/** One quad per point, instanced: a filled disc with an optional ring, antialiased by distance. */
	const val POINT_VERTEX = """#version 330 core
layout(location = 0) in vec2 a_corner;
layout(location = 1) in vec2 a_center;
uniform vec4 u_world;
uniform vec2 u_viewport;
uniform float u_radius;
out vec2 v_offset;
void main() {
    vec2 c = vec2(u_world.x * a_center.x + u_world.y, u_world.z * a_center.y + u_world.w);
    vec2 pc = (c * 0.5 + 0.5) * u_viewport;
    float extent = u_radius + 1.0;
    v_offset = (a_corner * 2.0 - 1.0) * extent;
    gl_Position = vec4((pc + v_offset) / u_viewport * 2.0 - 1.0, 0.0, 1.0);
}
"""

	const val POINT_FRAGMENT = """#version 330 core
in vec2 v_offset;
uniform float u_radius;
uniform float u_ring;
uniform vec4 u_fill;
uniform vec4 u_stroke;
out vec4 o_color;
void main() {
    float d = length(v_offset);
    float outside = clamp(d - u_radius + 0.5, 0.0, 1.0);
    float inside = u_ring > 0.0 ? clamp(d - (u_radius - u_ring) + 0.5, 0.0, 1.0) : 0.0;
    vec4 color = mix(u_fill, u_stroke, inside);
    o_color = color * (1.0 - outside);
}
"""
}

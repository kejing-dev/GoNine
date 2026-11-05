package io.embodit.gonine.ui.board.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import io.embodit.gonine.ui.theme.GoColors

internal fun Color.toColor4(): Color4 = Color4(red, green, blue, alpha)

/**
 * Meshes, materials, textures and collision shapes for the whole board, created once off the main thread
 * (each one waits for the renderer) and kept with toGlobal; [close] releases them.
 *
 * Every visible thing is procedural on purpose (design referenceRepo.assets / deviations B12): the board,
 * the lens stones, bowls and lids are exact-geometry game pieces, so no GLB / Tripo.
 */
internal class SceneResources {
    private val all = ArrayList<Resource>()

    private fun pbr(color: Color, roughness: Float, glow: Float = 0.06f, metallic: Float = 0f): PhysicallyBasedMaterial =
        PhysicallyBasedMaterial.create().apply {
            setBaseColor(color.toColor4())
            // A little light of its own keeps the pieces readable from the sides of the volume (no baked lighting).
            setEmissiveColor(Color4(color.red * glow, color.green * glow, color.blue * glow, 1f))
            setRoughness(roughness)
            setMetallic(metallic)
            toGlobal()
            all += this
        }

    private fun unlit(color: Color, opacity: Float = 1f): UnlitMaterial =
        UnlitMaterial.create(if (opacity < 1f) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
            setBaseColor(color.toColor4())
            if (opacity < 1f) setOpacity(opacity)
            setCullingMode(MaterialCullingMode.NONE)
            setApplyToneMapping(false)
            toGlobal()
            all += this
        }

    // ---- materials (≤ 6 lit ones, design budget) ----
    val kaya: Material = pbr(GoColors.KayaEdge, roughness = 0.7f, glow = 0.08f)
    val black: Material = pbr(GoColors.BlackStone, roughness = 0.32f, glow = 0.05f)
    val white: Material = pbr(GoColors.WhiteStone, roughness = 0.5f, glow = 0.10f)
    val bowlWood: Material = pbr(GoColors.BowlWood, roughness = 0.6f, glow = 0.08f)
    val lidWood: Material = pbr(GoColors.LidWood, roughness = 0.65f, glow = 0.08f)
    /** The winner's stones glow a little on the result (S09). */
    val blackGlow: Material = pbr(GoColors.BlackStone, roughness = 0.32f, glow = 0.9f)
    val whiteGlow: Material = pbr(GoColors.WhiteStone, roughness = 0.5f, glow = 0.35f)

    // ---- markers: unlit, some deliberately see-through (D24 exception: markers, not models) ----
    val ghostBlack: Material = unlit(GoColors.BlackStone, opacity = 0.5f)
    val ghostWhite: Material = unlit(GoColors.WhiteStone, opacity = 0.6f)
    val cross: Material = unlit(GoColors.Error)
    val lastDot: Material = unlit(GoColors.LastMove)
    val hintRing: Material = unlit(GoColors.HintGreen)
    val light: Material = unlit(GoColors.LibertyGlow)
    val cubeBlack: Material = unlit(GoColors.BlackStone, opacity = 0.7f)
    val cubeWhite: Material = unlit(GoColors.WhiteStone, opacity = 0.7f)

    // ---- board face texture ----
    private val faceTexture: TextureResource = TextureResource.create(BoardTexture.draw(1024)).keep()
    val face: Material = PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
        setBaseColor(Color4(1f, 1f, 1f, 1f))
        setBaseColorTexture(faceTexture)
        setRoughness(0.6f)
        setMetallic(0f)
        setEmissiveColor(Color4(0.06f, 0.05f, 0.03f, 1f))
        toGlobal()
        all += this
    }

    // ---- meshes ----
    // gate:allow geometry board slab: exact-geometry game piece (design B12 deviation, no Tripo for board / stones / bowls)
    val slab: MeshResource = MeshResource.createBox(Vector3(BoardLayout.BOARD, BoardLayout.BOARD_THICK, BoardLayout.BOARD), cornerRadius = 0.004f).keep()
    val facePlane: MeshResource = MeshResource.createPlane(BoardLayout.BOARD - 0.008f, BoardLayout.BOARD - 0.008f, 0f).keep()
    // gate:allow geometry lens stone: a sphere flattened to Ø 3.8 x 1.0 cm (design pieces), exact-geometry game piece
    val stone: MeshResource = MeshResource.createSphere(radius = BoardLayout.STONE_D / 2f).keep()
    // gate:allow geometry bowl body: turned-wood bowl, exact-geometry game piece
    val bowl: MeshResource = MeshResource.createCylinder(radius = BoardLayout.BOWL_R, height = BoardLayout.BOWL_H).keep()
    // gate:allow geometry stones heaped in a bowl: a flattened dome of stone colour
    val heap: MeshResource = MeshResource.createSphere(radius = BoardLayout.BOWL_R * 0.86f).keep()
    // gate:allow geometry lid turned over as the capture tray: exact-geometry game piece
    val lid: MeshResource = MeshResource.createCylinder(radius = BoardLayout.LID_R, height = BoardLayout.LID_H).keep()
    // gate:allow geometry lid's inner hollow: a darker disc on the lid
    val lidHollow: MeshResource = MeshResource.createCylinder(radius = BoardLayout.LID_R * 0.86f, height = 0.002f).keep()
    // gate:allow geometry illegal-point cross: abstract marker bar
    val crossBar: MeshResource = MeshResource.createBox(Vector3(0.034f, 0.003f, 0.006f), cornerRadius = 0.001f).keep()
    // gate:allow geometry last-move dot: abstract marker
    val dot: MeshResource = MeshResource.createCylinder(radius = 0.0045f, height = 0.0016f).keep()
    val ring: MeshResource = MeshResource.createTorus(outerRingRadius = 0.022f, innerRingRadius = 0.0185f).keep()
    // gate:allow geometry liberty light: abstract glowing marker (design spatialOnly 气光点)
    val lightBall: MeshResource = MeshResource.createSphere(radius = BoardLayout.LIGHT_D / 2f).keep()
    // gate:allow geometry scoring cube: abstract territory marker (design 数目方块)
    val cube: MeshResource = MeshResource.createBox(Vector3(BoardLayout.CUBE, BoardLayout.CUBE_T, BoardLayout.CUBE), cornerRadius = 0.0015f).keep()

    // ---- collision ----
    val physics: PhysicsMaterialResource = PhysicsMaterialResource().keep()
    val padShape: ShapeResource = ShapeResource.createBox(Vector3(BoardLayout.POINT_PAD, BoardLayout.POINT_PAD_T, BoardLayout.POINT_PAD)).keep()
    val stoneShape: ShapeResource = ShapeResource.createBox(Vector3(BoardLayout.STONE_D, BoardLayout.STONE_T, BoardLayout.STONE_D)).keep()
    val bowlShape: ShapeResource = ShapeResource.createBox(Vector3(BoardLayout.BOWL_R * 2, BoardLayout.BOWL_H, BoardLayout.BOWL_R * 2)).keep()
    val lidShape: ShapeResource = ShapeResource.createBox(Vector3(BoardLayout.LID_R * 2, 0.03f, BoardLayout.LID_R * 2)).keep()

    private fun <T : Resource> T.keep(): T {
        toGlobal()
        all += this
        return this
    }

    fun close() {
        all.forEach { runCatching { it.close() } }
        all.clear()
    }
}

/** The kaya board face: warm wood with a faint straight grain, 9 lines each way and 5 star points. No text. */
internal object BoardTexture {
    fun draw(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(GoColors.Kaya.toArgb())
        // Faint straight grain running left to right (kaya: fine, straight).
        val grain = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GoColors.KayaEdge.toArgb(); alpha = 70; style = Paint.Style.STROKE; strokeWidth = size * 0.0025f }
        val rnd = java.util.Random(9)
        for (i in 0 until 46) {
            val y = (i + rnd.nextFloat()) * size / 46f
            val path = Path().apply { moveTo(0f, y); cubicTo(size * 0.3f, y - size * 0.004f, size * 0.7f, y + size * 0.004f, size.toFloat(), y) }
            c.drawPath(path, grain)
        }
        // The face mesh is 0.452 m; lines run ±0.20 m from the centre.
        val metres = BoardLayout.BOARD - 0.008f
        fun px(m: Float) = (m / metres + 0.5f) * size
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GoColors.GridInk.toArgb(); strokeWidth = size * 0.0032f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }
        val edge = Paint(line).apply { strokeWidth = size * 0.0052f }
        for (i in 0..8) {
            val m = -0.20f + i * BoardLayout.PITCH
            val pen = if (i == 0 || i == 8) edge else line
            c.drawLine(px(-0.20f), px(m), px(0.20f), px(m), pen)
            c.drawLine(px(m), px(-0.20f), px(m), px(0.20f), pen)
        }
        val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GoColors.GridInk.toArgb(); style = Paint.Style.FILL }
        for ((r, col) in listOf(2 to 2, 2 to 6, 6 to 2, 6 to 6, 4 to 4)) {
            c.drawCircle(px(-0.20f + col * BoardLayout.PITCH), px(-0.20f + r * BoardLayout.PITCH), size * 0.0085f, star)
        }
        return bmp
    }
}

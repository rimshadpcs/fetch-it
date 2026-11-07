package com.rimapps.fetchit.ui.icons

import kotlin.math.sqrt

/**
 * SVG's feTurbulence, ported from the reference implementation in the SVG 1.1 spec (§15.24, "feTurbulence"), without
 * stitching. Same seed → the same noise a browser draws, so a displacement built on it matches the SVG's wobble.
 */
class SvgTurbulence(seed: Long) {
    private val lattice = IntArray(B + B + 2)
    private val gradient = Array(4) { Array(B + B + 2) { DoubleArray(2) } }

    init {
        var s = setupSeed(seed)
        for (k in 0 until 4) {
            for (i in 0 until B) {
                lattice[i] = i
                for (j in 0 until 2) {
                    s = random(s)
                    gradient[k][i][j] = ((s % (B + B)) - B).toDouble() / B
                }
                val len = sqrt(gradient[k][i][0] * gradient[k][i][0] + gradient[k][i][1] * gradient[k][i][1])
                gradient[k][i][0] /= len
                gradient[k][i][1] /= len
            }
        }
        var i = B
        while (--i > 0) {
            val k = lattice[i]
            s = random(s)
            val j = (s % B).toInt()
            lattice[i] = lattice[j]
            lattice[j] = k
        }
        for (n in 0 until B + 2) {
            lattice[B + n] = lattice[n]
            for (k in 0 until 4) for (j in 0 until 2) gradient[k][B + n][j] = gradient[k][n][j]
        }
    }

    private fun noise2(channel: Int, vx: Double, vy: Double): Double {
        var t = vx + PERLIN_N
        val bx0 = t.toInt() and BM; val bx1 = (bx0 + 1) and BM
        val rx0 = t - t.toInt(); val rx1 = rx0 - 1.0
        t = vy + PERLIN_N
        val by0 = t.toInt() and BM; val by1 = (by0 + 1) and BM
        val ry0 = t - t.toInt(); val ry1 = ry0 - 1.0
        val i = lattice[bx0]; val j = lattice[bx1]
        val b00 = lattice[i + by0]; val b10 = lattice[j + by0]
        val b01 = lattice[i + by1]; val b11 = lattice[j + by1]
        val sx = sCurve(rx0); val sy = sCurve(ry0)
        val g = gradient[channel]
        var u = rx0 * g[b00][0] + ry0 * g[b00][1]
        var v = rx1 * g[b10][0] + ry0 * g[b10][1]
        val a = lerp(sx, u, v)
        u = rx0 * g[b01][0] + ry1 * g[b01][1]
        v = rx1 * g[b11][0] + ry1 * g[b11][1]
        val b = lerp(sx, u, v)
        return lerp(sy, a, b)
    }

    /** type="fractalNoise": the channel's value 0…1 at (x, y) in user units. */
    fun fractalNoise(channel: Int, x: Double, y: Double, baseFrequency: Double, octaves: Int): Double {
        var sum = 0.0
        var vx = x * baseFrequency; var vy = y * baseFrequency
        var ratio = 1.0
        repeat(octaves) {
            sum += noise2(channel, vx, vy) / ratio
            vx *= 2; vy *= 2; ratio *= 2
        }
        return ((sum + 1) / 2).coerceIn(0.0, 1.0)
    }

    private companion object {
        const val B = 0x100
        const val BM = 0xff
        const val PERLIN_N = 0x1000
        const val RAND_M = 2147483647L
        const val RAND_A = 16807L
        const val RAND_Q = 127773L
        const val RAND_R = 2836L

        fun setupSeed(seed: Long): Long {
            var s = seed
            if (s <= 0) s = -(s % (RAND_M - 1)) + 1
            if (s > RAND_M - 1) s = RAND_M - 1
            return s
        }

        fun random(seed: Long): Long {
            var r = RAND_A * (seed % RAND_Q) - RAND_R * (seed / RAND_Q)
            if (r <= 0) r += RAND_M
            return r
        }

        fun sCurve(t: Double) = t * t * (3.0 - 2.0 * t)
        fun lerp(t: Double, a: Double, b: Double) = a + t * (b - a)
    }
}

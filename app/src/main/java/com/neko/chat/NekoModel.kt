package com.neko.chat

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Pure-Kotlin inference for the MiniGPT defined in llm.py (no PyTorch needed).
 * Reads the custom .cde format:
 *   [4 bytes "CDE1"][uint32 LE header size][JSON header][float32 LE tensors]
 */
class NekoModel private constructor(
    val blockSize: Int,
    private val nEmbd: Int,
    private val nHead: Int,
    private val nLayer: Int,
    private val chars: List<String>,
    private val w: Map<String, FloatArray>
) {
    private val vocab = chars.size
    private val stoi = HashMap<String, Int>().also { m -> chars.forEachIndexed { i, c -> m[c] = i } }
    private val rng = Random()

    // KV cache for one generation
    private val kCache = Array(nLayer) { FloatArray(blockSize * nEmbd) }
    private val vCache = Array(nLayer) { FloatArray(blockSize * nEmbd) }
    private var pos = 0

    private fun p(name: String): FloatArray = w[name] ?: error("Missing tensor: $name")

    /** Generates Neko's reply for the given context (same post-processing as llm.py). */
    @Synchronized
    fun reply(context: String, maxNew: Int = 120, temperature: Float = 0.4f): String {
        var ids = ArrayList<Int>()
        var i = 0
        while (i < context.length) {
            val cp = context.codePointAt(i)
            stoi[String(Character.toChars(cp))]?.let { ids.add(it) }
            i += Character.charCount(cp)
        }
        if (ids.isEmpty()) return ""
        if (ids.size > blockSize - 1) ids = ArrayList(ids.takeLast(blockSize - 1))

        pos = 0
        var logits = FloatArray(0)
        for (t in ids) logits = step(t)

        val limit = minOf(maxNew, blockSize - ids.size)
        val sb = StringBuilder()
        for (n in 0 until limit) {
            val tok = sample(logits, temperature)
            sb.append(chars[tok])
            val s = sb.toString()
            if (s.contains("\nUser:") || s.contains("\n\n")) break
            if (n < limit - 1) logits = step(tok)
        }
        return sb.toString().split("\nUser:")[0].split("\n\n")[0].trim()
    }

    /** One transformer step for a single new token using the KV cache. Returns logits. */
    private fun step(token: Int): FloatArray {
        val c = nEmbd
        val hd = c / nHead
        val tok = p("tok.weight")
        val pe = p("pos.weight")
        val x = FloatArray(c) { tok[token * c + it] + pe[pos * c + it] }
        val scale = 1f / sqrt(hd.toFloat())
        val scores = FloatArray(pos + 1)

        for (l in 0 until nLayer) {
            val b = "blocks.$l."
            val h = layerNorm(x, p(b + "ln1.weight"), p(b + "ln1.bias"))
            val qkv = linear(h, p(b + "qkv.weight"), p(b + "qkv.bias"), 3 * c)
            System.arraycopy(qkv, c, kCache[l], pos * c, c)
            System.arraycopy(qkv, 2 * c, vCache[l], pos * c, c)

            val att = FloatArray(c)
            val kc = kCache[l]
            val vc = vCache[l]
            for (head in 0 until nHead) {
                val off = head * hd
                var mx = Float.NEGATIVE_INFINITY
                for (t in 0..pos) {
                    var s = 0f
                    val base = t * c + off
                    for (d in 0 until hd) s += qkv[off + d] * kc[base + d]
                    s *= scale
                    scores[t] = s
                    if (s > mx) mx = s
                }
                var sum = 0f
                for (t in 0..pos) {
                    scores[t] = exp(scores[t] - mx)
                    sum += scores[t]
                }
                for (t in 0..pos) {
                    val a = scores[t] / sum
                    val base = t * c + off
                    for (d in 0 until hd) att[off + d] += a * vc[base + d]
                }
            }
            val proj = linear(att, p(b + "proj.weight"), p(b + "proj.bias"), c)
            for (k in 0 until c) x[k] += proj[k]

            val h2 = layerNorm(x, p(b + "ln2.weight"), p(b + "ln2.bias"))
            val f = linear(h2, p(b + "ff.0.weight"), p(b + "ff.0.bias"), 4 * c)
            for (k in f.indices) f[k] = gelu(f[k])
            val f2 = linear(f, p(b + "ff.2.weight"), p(b + "ff.2.bias"), c)
            for (k in 0 until c) x[k] += f2[k]
        }
        pos++
        val xn = layerNorm(x, p("ln.weight"), p("ln.bias"))
        return linear(xn, p("head.weight"), p("head.bias"), vocab)
    }

    private fun sample(logits: FloatArray, temp: Float): Int {
        var mx = Float.NEGATIVE_INFINITY
        for (v in logits) if (v > mx) mx = v
        val pr = DoubleArray(logits.size)
        var sum = 0.0
        for (i in logits.indices) {
            pr[i] = exp(((logits[i] - mx) / temp).toDouble())
            sum += pr[i]
        }
        var r = rng.nextDouble() * sum
        for (i in pr.indices) {
            r -= pr[i]
            if (r <= 0) return i
        }
        return pr.size - 1
    }

    private fun layerNorm(x: FloatArray, g: FloatArray, b: FloatArray): FloatArray {
        val n = x.size
        var mean = 0f
        for (v in x) mean += v
        mean /= n
        var variance = 0f
        for (v in x) variance += (v - mean) * (v - mean)
        variance /= n
        val inv = 1f / sqrt(variance + 1e-5f)
        return FloatArray(n) { (x[it] - mean) * inv * g[it] + b[it] }
    }

    /** y = x · Wᵀ + b, with W shaped [out, in] (PyTorch nn.Linear layout). */
    private fun linear(x: FloatArray, wt: FloatArray, b: FloatArray, out: Int): FloatArray {
        val inn = x.size
        val y = FloatArray(out)
        for (o in 0 until out) {
            var s = b[o]
            val base = o * inn
            for (i in 0 until inn) s += wt[base + i] * x[i]
            y[o] = s
        }
        return y
    }

    private fun gelu(x: Float): Float = 0.5f * x * (1f + erf(x * 0.70710678f))

    private fun erf(x: Float): Float {
        val t = 1f / (1f + 0.3275911f * abs(x))
        val poly = ((((1.061405429f * t - 1.453152027f) * t) + 1.421413741f) * t - 0.284496736f) * t + 0.254829592f
        val y = 1f - poly * t * exp(-x * x)
        return if (x >= 0) y else -y
    }

    companion object {
        fun load(file: File): NekoModel {
            val bytes = file.readBytes()
            require(bytes.size > 8 && String(bytes, 0, 4, Charsets.US_ASCII) == "CDE1") { "Not a valid .cde file" }
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val hsize = bb.getInt(4)
            require(hsize > 0 && 8 + hsize <= bytes.size) { "Corrupt .cde header" }
            val header = JSONObject(String(bytes, 8, hsize, Charsets.UTF_8))

            val cfg = header.getJSONObject("config")
            val arr = header.getJSONArray("chars")
            val chars = List(arr.length()) { arr.getString(it) }

            val tensors = header.getJSONArray("tensors")
            val map = HashMap<String, FloatArray>()
            var pos = 8 + hsize
            for (i in 0 until tensors.length()) {
                val t = tensors.getJSONArray(i)
                val shape = t.getJSONArray(1)
                var n = 1
                for (j in 0 until shape.length()) n *= shape.getInt(j)
                require(pos + n * 4 <= bytes.size) { "Model file is truncated" }
                val data = FloatArray(n)
                bb.position(pos)
                bb.asFloatBuffer().get(data)
                pos += n * 4
                map[t.getString(0)] = data
            }

            val model = NekoModel(
                cfg.getInt("block_size"), cfg.getInt("n_embd"),
                cfg.getInt("n_head"), cfg.getInt("n_layer"), chars, map
            )
            val required = mutableListOf("tok.weight", "pos.weight", "ln.weight", "ln.bias", "head.weight", "head.bias")
            for (l in 0 until model.nLayer) {
                for (n in listOf("ln1.weight", "ln1.bias", "ln2.weight", "ln2.bias", "qkv.weight", "qkv.bias",
                    "proj.weight", "proj.bias", "ff.0.weight", "ff.0.bias", "ff.2.weight", "ff.2.bias")) {
                    required.add("blocks.$l.$n")
                }
            }
            for (name in required) require(map.containsKey(name)) { "Model is missing tensor: $name" }
            return model
        }
    }
}

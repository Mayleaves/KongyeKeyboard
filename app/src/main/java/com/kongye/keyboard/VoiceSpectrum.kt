package com.kongye.keyboard

import androidx.annotation.Keep
import kotlin.math.*

/** 固定频段的实际录音频谱；每个柱子表示自己的频段，不搬动音量历史。 */
@Keep
object VoiceSpectrum {
    /** 低频居中、高频向两侧展开，左右对应柱子使用同一份能量。 */
    fun mirrored(values: FloatArray): FloatArray = FloatArray(17) { index ->
        val band = abs(index - 8) * 2
        max(values.getOrElse(band) { 0f }, values.getOrElse(band + 1) { 0f }).coerceIn(0f, 1f)
    }

    fun analyze(samples: ShortArray, sampleRate: Int): FloatArray {
        val n = 1024
        val real = DoubleArray(n)
        val imaginary = DoubleArray(n)
        val size = min(samples.size, n)
        var power = 0.0
        for (i in 0 until size) {
            val value = samples[samples.size-size+i] / 32768.0
            power += value*value
            real[i] = value * (.5 - .5*cos(2*PI*i/(n-1)))
        }
        val result = FloatArray(17)
        if (size == 0 || sqrt(power/size) < .006) return result
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val v=real[i]; real[i]=real[j]; real[j]=v }
        }
        var length = 2
        while (length <= n) {
            val angle = -2*PI/length
            for (start in 0 until n step length) {
                for (k in 0 until length/2) {
                    val a=start+k; val b=a+length/2
                    val wr=cos(angle*k); val wi=sin(angle*k)
                    val tr=real[b]*wr-imaginary[b]*wi
                    val ti=real[b]*wi+imaginary[b]*wr
                    real[b]=real[a]-tr; imaginary[b]=imaginary[a]-ti
                    real[a]+=tr; imaginary[a]+=ti
                }
            }
            length = length shl 1
        }
        for (band in result.indices) {
            val low=(100.0*2.0.pow(band*6.0/result.size)*n/sampleRate).toInt().coerceIn(1,n/2-1)
            val high=(100.0*2.0.pow((band+1)*6.0/result.size)*n/sampleRate).toInt().coerceIn(low+1,n/2)
            var sum=0.0
            for (bin in low until high) sum+=real[bin]*real[bin]+imaginary[bin]*imaginary[bin]
            val amplitude=sqrt(sum/(high-low))/(n/2)
            result[band]=((20*log10(amplitude.coerceAtLeast(1e-7))+65)/42).toFloat().coerceIn(0f,1f)
        }
        return result
    }
}

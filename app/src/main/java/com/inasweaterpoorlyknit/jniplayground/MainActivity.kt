package com.inasweaterpoorlyknit.jniplayground

import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.lifecycleScope
import dalvik.annotation.optimization.FastNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.min
import kotlin.random.Random

val DEBUG_LOG_TAG = "jni_playground"
fun logd(msg: String) = Log.d(DEBUG_LOG_TAG, msg)
fun loge(msg: String) = Log.e(DEBUG_LOG_TAG, msg)
fun logi(msg: String) = Log.i(DEBUG_LOG_TAG, msg)


class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        performanceTests()
    }

    fun logTimeHeader() {
        val toPrint = CharArray(MAX_PRINT_SIZE){' '}
        "=== Title ===".forEachIndexed { i, c ->
            toPrint[i] = c
        }
        "=== Seconds ===".forEachIndexed { i, c ->
            toPrint[40 + i] = c
        }
        Log.d(DEBUG_LOG_TAG, toPrint.joinToString(""))
    }

    fun logTime(title: String, clocks: Int) {
        val toPrint = CharArray(MAX_PRINT_SIZE){' '}
        for(i in title.indices){
            if(i == 40) break
            toPrint[i] = title[i]
        }
        clocksToSeconds(clocks).toString().let{
            it.substring(0, min(40, it.length)).forEachIndexed { i, c ->
                toPrint[i + 40] = c
            }
        }
        Log.d(DEBUG_LOG_TAG, toPrint.joinToString(""))
    }

    fun TimedWork.log(title: String) {
        logTime("$title (min)", minSecs)
        logTime("$title (max)", maxSecs)
        logd("$title (iterations): $totalIterations")
    }

    data class CPUInfo (
        val processors: List<CPUInfoProcessor>,
        val name: String
    ) {
        val features = processors.flatMap{ it.features.toSet() }.fold(setOf<String>()){ acc, it -> acc + it }
        fun log() {
            logi("=== CPU Information ===")
            logi("Name: $name")
            logi("Processor count: ${processors.size}")
            logi("Supported features:${features.fold(StringBuilder()){ acc, it -> acc.append(" $it") }}").toString()
        }
    }

    data class CPUInfoProcessor(
        val index: Int,
        val model: String,
        val bogoMips: Float,
        val features: List<String>
    )

    fun String.hasPrefix(prefix: String): Boolean {
        if(length < prefix.length) return false
        for(i in 0..prefix.lastIndex) {
            if(this[i] != prefix[i]) return false
        }
        return true
    }

    fun fetchCpuInfo(): CPUInfo {
        val cpuInfoFile = File("/proc/cpuinfo")
        if(!cpuInfoFile.isFile) {
            loge( "Could not open /proc/cpuinfo")
            return CPUInfo(emptyList(), "")
        }
        val cpuProcessors = ArrayList<CPUInfoProcessor>()
        var name = ""
        val fileLines = cpuInfoFile.readLines()
        var lineIter = 0
        while(lineIter < fileLines.size) {
            var line = fileLines[lineIter++]
            when {
                line.hasPrefix("processor") -> {
                    val index = Regex("(0-9)+").find(line)?.value?.toInt() ?: -1
                    var model = ""; var bogoMips = -1.0f; var features = ArrayList<String>();
                    line = fileLines[lineIter++]
                    while(line.isNotBlank()) {
                        when {
                            line.hasPrefix("model name") -> model = Regex("(?<=:\\s).+").find(line)?.value ?: ""
                            line.hasPrefix("BogoMIPS") -> bogoMips = Regex("(?<=:\\s).+").find(line)?.value?.toFloat() ?: -1.0f
                            line.hasPrefix("Features") -> features.addAll(
                                Regex("(?<=:\\s).+").find(line)?.value?.split(regex = Regex("\\s+"))?.filter{ it.isNotEmpty() } ?: emptyList()
                            )
                        }
                        line = fileLines[lineIter++]
                    }
                    cpuProcessors.add(CPUInfoProcessor(index, model, bogoMips, features))
                }
                line.hasPrefix("Hardware") -> {
                    name = Regex("(?<=:\\s).+").find(line)?.value ?: ""
                }
            }
        }
        return CPUInfo(cpuProcessors, name)
    }

    fun fetchRamInfo() {
        val memInfoFile = File("/proc/meminfo")
        if(!memInfoFile.isFile) {
            loge( "Could not open /proc/memInfo")
            return
        }
        memInfoFile.readLines().forEach { logi(it) }
    }

    private fun performanceTests() {
        lifecycleScope.launch(Dispatchers.Default) {
            initialize()
            val rand = Random(123)
            Log.i(DEBUG_LOG_TAG, "Supported ABIs:" + Build.SUPPORTED_ABIS.fold(StringBuilder()){ acc, str -> acc.append(" $str") }).toString()
            Log.i(DEBUG_LOG_TAG,"Model: " + Build.MODEL)
            Log.i(DEBUG_LOG_TAG,"Manufacturer: " + Build.MANUFACTURER)
            Log.i(DEBUG_LOG_TAG,"Brand: " + Build.BRAND)
            Log.i(DEBUG_LOG_TAG,"SDK: " + Build.VERSION.SDK_INT.toString())
            Log.i(DEBUG_LOG_TAG,"Board: " + Build.BOARD)
            Log.i(DEBUG_LOG_TAG,"Product: " + Build.PRODUCT)
            Log.i(DEBUG_LOG_TAG,"Device: " + Build.DEVICE)
            if(Build.VERSION.SDK_INT >= 31){
                Log.i(DEBUG_LOG_TAG,"SoC Manufacturer: " + Build.SOC_MANUFACTURER)
                Log.i(DEBUG_LOG_TAG,"SoC Manufacturer: " + Build.SOC_MODEL)
            }
            val cpuInfo = fetchCpuInfo()
            fetchRamInfo()
            cpuInfo.log()

            logTimeHeader()

            // timer overhead
            val timerOverheadDuration = iterationTiming{}
            timerOverheadDuration.log("C Timer Overhead")

            val randomNumbers = IntArray(1_000_000) { rand.nextInt() }
            val numbersCopy: () -> IntArray = { randomNumbers.copyOf() }

            // plus one C
            val numbersCPlusOne = numbersCopy()
            val plusOneCDuration = iterationTiming{ plusOneC(numbersCPlusOne) }
            plusOneCDuration.log("+1 C")

            // plus one in-place Kotlin
            val numbersKotlinInPlacePlusOne = numbersCopy()
            val plusOneInPlaceKotlinDuration = iterationTiming{ for(i in numbersKotlinInPlacePlusOne.indices){ numbersKotlinInPlacePlusOne[i] += 1 } }
            plusOneInPlaceKotlinDuration.log("+1 in-place Kotlin")

            // plus one map Kotlin
            var numbersKotlinMapPlusOne: List<Int>
            val plusOneCopyMapKotlinDuration = iterationTiming{
                numbersKotlinMapPlusOne = randomNumbers.map { it + 1 }
            }
            plusOneCopyMapKotlinDuration.log("+1 copy (map) Kotlin")

            // default sort in Kotlin
            val sortInKotlinDuration = iterationTiming(setup = numbersCopy){ it.sort() }
            sortInKotlinDuration.log("Sorting in Kotlin")

            // default sort in C
            val sortInCDuration = iterationTiming(setup = numbersCopy){ sortC(it) }
            sortInCDuration.log("Sorting in C")

            // default reverse in Kotlin
            val defaultReverseKotlinDuration = iterationTiming{
                randomNumbers.reverse()
            }
            defaultReverseKotlinDuration.log("Default reverse in Kotlin")

            // C-equivalent reverse in Kotlin
            val reverseKotlinDuration = iterationTiming{
                var left = 0; var right = randomNumbers.lastIndex; var tmp: Int;
                while(left < right) {
                    tmp = randomNumbers[left];
                    randomNumbers[left++] = randomNumbers[right];
                    randomNumbers[right--] = tmp;
                }
            }
            reverseKotlinDuration.log("C-equivalent reverse in Kotlin")

            // reverse array in C
            val reverseCDuration = iterationTiming{ reverseC(randomNumbers) }
            reverseCDuration.log("Reverse in C")
        }
    }

    private fun time(work: () -> Unit): Int { startTime(); work(); return endTime() }

    data class TimedWork(
        val minSecs: Int,
        val maxSecs: Int,
        val totalIterations: Int,
    )

    private fun iterationTiming(maxIterationsNoChange: Int = 10, work: () -> Unit): TimedWork {
        var iterationsSinceChange = 0
        var totalIterations = 0
        var minSecs = Int.MAX_VALUE
        var maxSecs = Int.MIN_VALUE
        while(iterationsSinceChange < maxIterationsNoChange) {
            val secs = time(work)
            iterationsSinceChange += 1; totalIterations += 1;
            if(secs < minSecs) { minSecs = secs; iterationsSinceChange = 0; }
            if(secs > maxSecs) { maxSecs = secs; iterationsSinceChange = 0; }
        }
        return TimedWork(minSecs, maxSecs, totalIterations)
    }

    private fun <T> iterationTiming(maxIterationsNoChange: Int = 10, setup: () -> T, work: (T) -> Unit): TimedWork {
        var iterationsSinceChange = 0
        var totalIterations = 0
        var minSecs = Int.MAX_VALUE
        var maxSecs = Int.MIN_VALUE
        while(iterationsSinceChange < maxIterationsNoChange) {
            val input = setup()
            val secs = time { work(input) }
            iterationsSinceChange += 1; totalIterations += 1;
            if(secs < minSecs) { minSecs = secs; iterationsSinceChange = 0; }
            if(secs > maxSecs) { maxSecs = secs; iterationsSinceChange = 0; }
        }
        return TimedWork(minSecs, maxSecs, totalIterations)
    }

    private fun List<Int>.printable(): String {
        val s = StringBuilder()
        s.append("[")
        for(i in 0..<size-1){
            s.append("${this[i]}, ")
        }
        s.append("${this[size-1]}]")
        return s.toString()
    }

    companion object {
        private const val MAX_PRINT_SIZE = 90

        // Used to load the 'jniplayground' library on application startup.
        init {
            System.loadLibrary("jniplayground")
        }

        @JvmStatic
        external fun stringFromJNI(): String
        @JvmStatic
        external fun sumC(nums: IntArray): Int
        @JvmStatic
        external fun sortC(nums: IntArray)
        @FastNative
        @JvmStatic
        external fun plusOneC(nums: IntArray)
        @JvmStatic
        external fun reverseC(nums: IntArray)

        @FastNative
        @JvmStatic
        external fun startTime()
        @FastNative
        @JvmStatic
        external fun initialize()
        @FastNative
        @JvmStatic
        external fun endTime(): Int
        @FastNative
        @JvmStatic
        external fun clocksToSeconds(clocks: Int): Double
    }
}
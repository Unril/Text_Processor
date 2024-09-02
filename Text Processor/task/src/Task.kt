import com.squareup.moshi.FromJson
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.ToJson
import com.squareup.moshi.adapter
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

class FormattedText(input: String, inputType: InputType) {
    private val text = inputType.readInput(input)

    private val formatted = text
        .replace("""([({\[]) +""".toRegex()) { it.groupValues[1] }
        .replace(""" +([)}\]])""".toRegex()) { it.groupValues[1] }
        .replace("""([^ ]+)([({\[])""".toRegex()) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        .replace("""([)}\]])([^ ]+)""".toRegex()) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        .replace(""" *([.,!?;:]) *""".toRegex()) { "${it.groupValues[1]} " }
        .replace(""" +""".toRegex(), " ")
        .split("\n")
        .joinToString("\n") { it.trim() }
        .replace("""(([.?!]\s+)|^)[{\[(]*[a-z]""".toRegex()) { it.value.uppercase() }

    override fun toString(): String = formatted
}

abstract class MostUsed(var elements: List<String>, var count: Int) {
    abstract fun name(): String

    override fun toString(): String {
        if (elements.isEmpty()) {
            return "Most used ${name()}: -"
        }
        val suffix = if (elements.size > 1) "s" else ""
        val chars = elements.joinToString(", ")
        return "Most used ${name()}$suffix: $chars ($count times)"
    }
}

class MostUsedChars(elements: List<String> = emptyList(), count: Int = 0) :
    MostUsed(elements, count) {
    override fun name(): String = "character"
}

class MostUsedWords(elements: List<String> = emptyList(), count: Int = 0) :
    MostUsed(elements, count) {
    override fun name(): String = "word"
}

enum class InputType {
    TEXT,
    FILE;

    fun readInput(input: String): String = when (this) {
        TEXT -> input
        FILE -> File(input).readText()
    }
}

class InputTypeAdapter {
    @ToJson
    fun toJson(inputType: InputType): String = inputType.name.lowercase()

    @FromJson
    fun fromJson(inputType: String): InputType = InputType.valueOf(inputType.uppercase())
}

class Statistics(val input: String, val inputType: InputType) {
    private object Regexes {
        val SPLIT = """\w+""".toRegex()
    }

    private val text = inputType.readInput(input)
    private val chars = text
        .toCharArray()
        .filter { !it.isWhitespace() }
    private val charFreq = frequency(chars.map { it.toString() })

    val inputCharsLength get() = text.length
    val inputCharsLengthWithoutSpaces get() = chars.size
    fun getMostUsedChars() =
        if (noMostUsed(charFreq)) MostUsedChars() else MostUsedChars(
            mostUsed(charFreq), mostUsedCount(charFreq)
        )

    private val words = Regexes.SPLIT.findAll(text).map { it.value.trim().lowercase() }.toList()
    private val wordFreq = frequency(words)

    val inputWordsLength get() = words.size
    fun getMostUsedWords() =
        if (noMostUsed(wordFreq)) MostUsedWords() else MostUsedWords(
            mostUsed(wordFreq), mostUsedCount(wordFreq)
        )

    private fun frequency(items: List<String>): List<Pair<String, Int>> = items
        .groupingBy { it }
        .eachCount()
        .toList()
        .sortedByDescending { it.second }

    private fun mostUsed(freq: List<Pair<String, Int>>): List<String> = freq
        .takeWhile { it.second == mostUsedCount(freq) }
        .map { it.first }

    private fun noMostUsed(freq: List<Pair<String, Int>>) = freq.none { it.second > 1 }

    private fun mostUsedCount(freq: List<Pair<String, Int>>) = freq.first().second
}

class History {
    companion object {
        private const val FILE_NAME = "history.json"
        private val BUILDER: Moshi = Moshi.Builder()
            .add(InputTypeAdapter())
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    class Characters(
        var count: Int,
        @Json(name = "exclude-spaces") var excludeSpaces: Int,
        @Json(name = "most-used") var mostUsed: MostUsedChars
    ) {
        override fun toString(): String = buildString {
            appendLine("Characters: $count")
            appendLine("Characters excluding spaces: $excludeSpaces")
            appendLine(mostUsed)
        }
    }

    class Words(
        var count: Int,
        @Json(name = "most-used") var mostUsed: MostUsedWords
    ) {
        override fun toString(): String = buildString {
            appendLine("Words: $count")
            appendLine(mostUsed)
        }
    }

    class Entry(
        var type: InputType,
        var content: String,
        var characters: Characters,
        var words: Words
    ) {
        override fun toString(): String = buildString {
            when (type) {
                InputType.TEXT -> appendLine("Text:")
                InputType.FILE -> appendLine("File:")
            }
            appendLine(content)
            appendLine()
            appendLine(characters)
            appendLine(words)
        }
    }

    private val history = mutableListOf<Entry>()

    init {
        loadJson()
    }

    fun add(stats: Statistics) {
        history.add(
            Entry(
                stats.inputType,
                stats.input,
                Characters(
                    stats.inputCharsLength,
                    stats.inputCharsLengthWithoutSpaces,
                    stats.getMostUsedChars()
                ),
                Words(
                    stats.inputWordsLength,
                    stats.getMostUsedWords()
                )
            )
        )
        saveJson()
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun loadJson() {
        if (!File(FILE_NAME).exists()) {
            return
        }
        val jsonAdapter = BUILDER.adapter<List<Entry>>()
        val json = File(FILE_NAME).readText()
        history.addAll(jsonAdapter.fromJson(json) ?: emptyList())
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun saveJson() {
        val jsonAdapter = BUILDER.adapter<List<Entry>>().indent("    ")
        val json = jsonAdapter.toJson(history)
        File(FILE_NAME).writeText(json)
    }

    fun lastItem() = history.last()

    override fun toString(): String = history.joinToString("\n")
}

class Menu(private val argsList: List<String>) {
    class Args {
        var help: Boolean = false
        var history: Boolean = false
        var statistics: Boolean = false
        var format: Boolean = false
        var input: String? = null
        var output: String? = null
    }

    private val history = History()

    private fun inputLines(): String? {
        println("Enter text (enter a blank line to end):")
        val builder = StringBuilder()
        while (true) {
            val input = readlnOrNull()
            if (input.isNullOrBlank()) break
            builder.appendLine(input)
        }
        if (builder.isEmpty()) {
            println("No text provided!")
            return null
        }
        return builder.trim().toString()
    }

    private fun input(args: Args): Pair<String, InputType>? {
        val argsInput = args.input
        if (argsInput.isNullOrBlank()) {
            val input = inputLines() ?: return null
            return input to InputType.TEXT
        }
        if (!File(argsInput).exists()) {
            println("File not found!")
            return null
        }
        return argsInput to InputType.FILE
    }

    private fun parseArgs(): Args? {
        if (argsList.isEmpty()) return Args().apply { help = true }
        // Parse arguments
        val firstArg = argsList.first()
        val args = Args()
        when (firstArg) {
            "-h", "--help" -> args.help = true
            "--history" -> args.history = true
            "--statistics" -> args.statistics = true
            "--format" -> args.format = true
            else -> {
                println("Invalid argument!")
                return null
            }
        }
        if (args.help) return args
        if (argsList.size < 2) {
            return args
        }
        if (argsList.size < 3) {
            println("Invalid argument!")
            return null
        }
        val secondArg = argsList[1]
        val secondData = argsList[2]
        when (secondArg) {
            "--in", "--input" -> args.input = secondData
            "--out", "--output" -> args.output = secondData
            else -> {
                println("Invalid argument!")
                return null
            }
        }
        if (argsList.size < 4) {
            return args
        }
        if (argsList.size < 5) {
            println("Invalid argument!")
            return null
        }
        val thirdArg = argsList[3]
        val thirdData = argsList[4]
        when (thirdArg) {
            "--in", "--input" -> args.input = thirdData
            "--out", "--output" -> args.output = thirdData
            else -> {
                println("Invalid argument!")
                return null
            }
        }
        if (argsList.size > 5) {
            println("Invalid argument!")
            return null
        }
        return args
    }

    fun run() {
        val args = parseArgs() ?: return
        if (args.help) {
            println("Allowed commands: -h, --help, --history, --statistics, --format, --in, --input, --out, --output")
            return
        }
        fun output(message: String) {
            val argsOutput = args.output
            if (argsOutput.isNullOrBlank()) println(message)
            else File(argsOutput).writeText(message)
        }
        if (args.statistics) {
            val (input, type) = input(args) ?: return
            val stats = Statistics(input, type)
            history.add(stats)
            output("${history.lastItem().characters} ${history.lastItem().words}")
        }
        if (args.history) {
            output("== History ==\n$history")
        }
        if (args.format) {
            val (input, type) = input(args) ?: return
            val formatted = FormattedText(input, type)
            output(formatted.toString())
        }
    }
}


fun main(args: Array<String>) {
    Menu(args.toList()).run()
}
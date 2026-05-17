package com.example.eldaniel

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

class DictionaryManager(private val context: Context) {
    private val englishWords = mutableSetOf<String>()
    private val localWords = mutableSetOf<String>()

    init {
        // Load both dictionaries on startup
        loadWordsFromAssets("english_10k.txt", englishWords)
        loadWordsFromAssets("local_nigerian.txt", localWords)
    }

    private fun loadWordsFromAssets(fileName: String, targetSet: MutableSet<String>) {
        try {
            val inputStream = context.assets.open(fileName)
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String? = reader.readLine()
            while (line != null) {
                val word = line.trim().lowercase()
                if (word.isNotEmpty()) targetSet.add(word)
                line = reader.readLine()
            }
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getSuggestions(currentWord: String): List<String> {
        if (currentWord.isEmpty()) return emptyList()
        val query = currentWord.lowercase()

        // Search both sets
        val localMatch = localWords.filter { it.startsWith(query) }
        val englishMatch = englishWords.filter { it.startsWith(query) }

        // Combine: Local words get priority (Sovereign First)
        return (localMatch + englishMatch).distinct().take(4)
    }

    fun learnLocalWord(word: String) {
        val cleanWord = word.trim().lowercase()
        if (cleanWord.length > 2 && !englishWords.contains(cleanWord)) {
            localWords.add(cleanWord)
        }
    }
}
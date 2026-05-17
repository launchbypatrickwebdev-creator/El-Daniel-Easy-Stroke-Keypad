package com.example.eldaniel

object LanguageMapper {
    // English Sets
    val vowels = listOf("A", "E", "I", "O", "U", "Y")
    val powerCons = listOf("T", "N", "S", "R", "H", "D")
    val flowCons = listOf("L", "C", "M", "F", "P", "B")
    val complexCons = listOf("W", "G", "V", "K", "X", "Q", "J", "Z")

    // Numeric & Symbol Sets
    val numLow = listOf("1", "2", "3", "4", "5")
    val numHigh = listOf("6", "7", "8", "9", "0")
    val mathSymbols = listOf("+", "-", "×", "÷", "=", "/", "_", "<", ">")
    val extraSymbols = listOf("%", "^", "*", "(", ")", "[", "]", "{", "}")
    val punctuation = listOf(".", ",", "?", "!", "'")
    val symbols = listOf("@", "#", "&", "$", "|")
}
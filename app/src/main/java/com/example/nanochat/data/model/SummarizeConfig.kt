package com.example.nanochat.data.model

enum class BulletCount(val label: String, val apiValue: Int) {
    ONE("1 bullet", 1),
    TWO("2 bullets", 2),
    THREE("3 bullets", 3)
}

data class SummarizeConfig(
    val bulletCount: BulletCount = BulletCount.TWO,
    val originalTokens: Int? = null,
    val summaryTokens: Int? = null
)

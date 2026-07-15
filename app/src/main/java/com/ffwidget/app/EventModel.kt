package com.ffwidget.app

data class CalEvent(
    val title: String,
    val country: String,
    val dateIso: String,
    val impact: String,      // "High" 或 "Holiday"
    val forecast: String,
    val previous: String
)

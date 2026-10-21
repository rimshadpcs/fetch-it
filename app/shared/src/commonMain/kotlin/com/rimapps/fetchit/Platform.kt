package com.rimapps.fetchit

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
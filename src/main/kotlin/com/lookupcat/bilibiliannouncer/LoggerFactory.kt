package com.lookupcat.bilibiliannouncer


import org.slf4j.Logger
import org.slf4j.LoggerFactory

fun Any.logger(): Logger = logger(this.javaClass.name)
fun logger(name: String): Logger = LoggerFactory.getLogger(name)
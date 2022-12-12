package com.lookupcat.bilibiliannouncer

import java.util.logging.Logger

//import org.slf4j.Logger
//import org.slf4j.LoggerFactory

fun Any.logger(): Logger = logger(this.javaClass.name)
fun logger(name: String): Logger = Logger.getLogger(name)

fun Logger.warning(msg: String, ex: Throwable) {
    this.warning(msg)
    this.warning(ex.stackTraceToString())
}
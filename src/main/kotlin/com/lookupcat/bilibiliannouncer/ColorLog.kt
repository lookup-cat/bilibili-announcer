package com.lookupcat.bilibiliannouncer

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.pattern.color.ANSIConstants
import ch.qos.logback.core.pattern.color.ForegroundCompositeConverterBase

class ColorLog : ForegroundCompositeConverterBase<ILoggingEvent>() {
  override fun getForegroundColorCode(event: ILoggingEvent): String {
    return when (event.level.toInt()) {
      Level.TRACE_INT -> ANSIConstants.CYAN_FG
      Level.INFO_INT -> ANSIConstants.BLUE_FG
      Level.WARN_INT -> ANSIConstants.YELLOW_FG
      Level.ERROR_INT -> ANSIConstants.RED_FG
      Level.DEBUG_INT -> ANSIConstants.MAGENTA_FG
      else -> ANSIConstants.WHITE_FG
    }
  }
}
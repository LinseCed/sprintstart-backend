package com.sprintstart.sprintstartbackend.shared.annotations

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.time.measureTimedValue

@Aspect
@Component
class TimedAspect {
    private val logger = LoggerFactory.getLogger(TimedAspect::class.java)

    @Around("@annotation(timed)")
    fun logExecutionTime(joinPoint: ProceedingJoinPoint, timed: Timed): Any? {
        val label = timed.value.ifBlank { joinPoint.signature.name }
        val (result, duration) = measureTimedValue { joinPoint.proceed() }
        logger.info("$label: $duration")
        return result
    }
}

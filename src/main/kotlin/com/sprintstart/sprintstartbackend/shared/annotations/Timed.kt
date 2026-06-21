package com.sprintstart.sprintstartbackend.shared.annotations

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Timed(
    val value: String = "",
)

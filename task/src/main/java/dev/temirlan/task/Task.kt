package dev.temirlan.task

/**
 * Created by Temirlan Kuntubayev <t.me/tkuntubaev> on 10/14/19.
 */
interface Task {

    sealed class Status {
        object InProgress : Status()
        object Completed : Status()
        object Cancelled : Status()
    }

    sealed class Strategy {
        object KeepFirst : Strategy()
        object KillFirst : Strategy()
    }

    fun getId(): String

    fun execute(onFinish: () -> Unit)

    fun cancel()

    fun getStatus(): Status

    fun getStrategy(): Strategy
}
# Android asynchronous tasks management
Sometimes there is need to cancel some asynchronous execution while launching a new one with the same id, sometimes vice versa. This library provides an easy API to control every asynchronous task (no matter Coroutines, Rx, or something else). More details of the usage can be found in Medium post https://medium.com/@tkuntubayev/how-to-control-android-long-running-tasks-in-easy-way-91bbd5af69e1

## Getting Started with TaskHandler

Follow these instructions to implement this library in your project

1. add depencencies

* add to **project** `build.gradle`
``` gradle
allprojects {
    repositories {
        google()
        jcenter()
        //
        maven { url "https://dl.bintray.com/temirlan/common/" }
        //
    }
}
```
* add to **module** `build.gradle`
``` gradle
implementation 'dev.temirlan.common:task:1.0.1'
```

2. implement your own `Task` or find some that matches your preferences (Coroutines or Rx) from samples below
3. put `TaskHandler` in abstract Presenter or ViewModel to have an easy access in every Presenter
``` kotlin
abstract class AbstractPresenter<T : AbstractContract.View> : MvpPresenter<T>(), AbstractContract.Presenter {
    //
    protected val taskHandler = TaskHandler()
    //    
}
```
4. don't forget to cancel all tasks on presenter or viewmodel destroy
``` kotlin
abstract class AbstractPresenter<T : AbstractContract.View> : MvpPresenter<T>(), AbstractContract.Presenter {
    //
    override fun onDestroy() {
        taskHandler.cancelAll()
        super.onDestroy()
    }
    //
}
```
5. define and launch a task with TaskHandler's `handle` method in your presenter(viewmodel) as shown in example
``` kotlin
override fun onSetCardAsDefaultClicked(cardModel: CardModel) {
        viewState.showLoading()
        val setCardAsDefaultTask = CoroutineTask(
                "setCardAsDefault",                                   // set an id that will correspond to current task 
                Task.Strategy.KillFirst,                              // set the necessary strategy (KillFirst will destroy previous task with the same id)
                { billingRepository.setCardAsDefault(cardModel.id) }, // provide suspend function (cause we use CoroutineTask)
                { // use the result of the execution
                    viewState.hideLoading()
                    refresh()
                },
                { // handle exeption if something goes wrong
                    viewState.hideLoading()
                    throwableHandler.handleThrowable(it)
                }
        )
        taskHandler.handle(setCardAsDefaultTask)
    }
```

## Task usage
Task is an interface that contains the following methods. Implement and use it to execute your domain logic
``` kotlin
interface Task {
    fun getId(): String               // id that will be used by taskhandler to identify the same tasks

    fun execute(onFinish: () -> Unit) // method that executes the task. onFinish is callback function to inform TaskHandler about completing

    fun cancel()                      // method that cancels the task

    fun getStatus(): Status           // get the status of the task

    fun getStrategy(): Strategy       // identifying a Strategy to inform TaskHandler about what to do with the previous task with the same id (KillFirst or KeepFirst). See description below
}
```

#### Available task statuses
Status - class that informs about the current task state
``` kotlin
sealed class Status {
        object InProgress : Status()
        object Completed : Status()
        object Cancelled : Status()
}
```

#### Available task strategies
* KeepFirst - TaskHandler keeps the previous task with the same id and doesn't start current
* KillFirst - TaskHandler cancels the previous task with the same id and starts the current task
``` kotlin
sealed class Strategy {
        object KeepFirst : Strategy()
        object KillFirst : Strategy()
}
```

## Task implementation samples (not included to the library). Copy or implement your own Task
### Coroutines
``` kotlin
class CoroutineTask<T>(
    private val id: String,
    private val taskStrategy: Task.Strategy,
    private val function: suspend () -> T,
    private val onSuccess: (T) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val scope: CoroutineScope = GlobalScope
) : Task {

    private var job: Job? = null

    override fun getId(): String {
        return id
    }

    override fun execute(onFinish: () -> Unit) {
        if (job == null) {
            job = scope.launch {
                try {
                    val result = function()
                    withContext(Dispatchers.Main) { onSuccess(result) }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { onError(e) }
                } finally {
                    withContext(Dispatchers.Main) { onFinish() }
                }
            }
        }
    }

    override fun cancel() {
        job?.cancel(null)
    }

    override fun getStatus(): Task.Status {
        return when {
            job?.isCompleted == true -> Task.Status.Completed
            job?.isActive == true -> Task.Status.InProgress
            job?.isCancelled == true -> Task.Status.Cancelled
            else -> Task.Status.InProgress
        }
    }

    override fun getStrategy(): Task.Strategy {
        return taskStrategy
    }
}
```

``` kotlin
@FlowPreview
class FlowTask<T>(
    private val id: String,
    private val taskStrategy: Task.Strategy,
    private val function: suspend () -> Flow<T>,
    private val onNext: (T) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val scope: CoroutineScope = GlobalScope
) : Task {

    private var job: Job? = null

    override fun getId(): String {
        return id
    }

    override fun execute(onFinish: () -> Unit) {
        if (job == null) {
            job = scope.launch {
                try {
                    function().collect(object : FlowCollector<T> {
                        override suspend fun emit(value: T) {
                            withContext(Dispatchers.Main) { onNext(value) }
                        }
                    })
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { onError(e) }
                }
            }
        }
    }

    override fun cancel() {
        job?.cancel()
    }

    override fun getStatus(): Task.Status {
        return when {
            job?.isCompleted == true -> Task.Status.Completed
            job?.isActive == true -> Task.Status.InProgress
            job?.isCancelled == true -> Task.Status.Cancelled
            else -> Task.Status.InProgress
        }
    }

    override fun getStrategy(): Task.Strategy {
        return taskStrategy
    }
}
```

### Rx
``` kotlin
class SingleTask<T>(
    private val id: String,
    private val taskStrategy: Task.Strategy,
    private val single: Single<T>,
    private val onSuccess: (T) -> Unit,
    private val onError: (Throwable) -> Unit,
    private var scheduler: Scheduler = Schedulers.io()
) : Task {

    private var disposable: Disposable? = null

    override fun getId(): String {
        return id
    }

    override fun execute(onFinish: () -> Unit) {
        if (disposable == null) {
            disposable = single
                .subscribeOn(scheduler)
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(onFinish)
                .subscribe(onSuccess, onError)
        }
    }

    override fun cancel() {
        disposable?.dispose()
    }

    override fun getStatus(): Task.Status {
        if (disposable != null && disposable?.isDisposed == false) {
            return Task.Status.InProgress
        }
        return Task.Status.Completed
    }

    override fun getStrategy(): Task.Strategy {
        return taskStrategy
    }
}
```

``` kotlin
class CompletableTask(
    private val id: String,
    private val taskStrategy: Task.Strategy,
    private val completable: Completable,
    private val onComplete: () -> Unit,
    private val onError: (Throwable) -> Unit,
    private var scheduler: Scheduler = Schedulers.io()
) : Task {

    private var disposable: Disposable? = null

    override fun getId(): String {
        return id
    }

    override fun execute(onFinish: () -> Unit) {
        if (disposable == null) {
            disposable = completable
                .subscribeOn(scheduler)
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(onFinish)
                .subscribe(onComplete, onError)
        }
    }

    override fun cancel() {
        disposable?.dispose()
    }

    override fun getStatus(): Task.Status {
        if (disposable != null && disposable?.isDisposed == false) {
            return Task.Status.InProgress
        }
        return Task.Status.Completed
    }

    override fun getStrategy(): Task.Strategy {
        return taskStrategy
    }

}
```

``` kotlin
class FlowableTask<T>(
    private val id: String,
    private val taskStrategy: Task.Strategy,
    private val flowable: Flowable<T>,
    private val onNext: (T) -> Unit,
    private val onComplete: () -> Unit,
    private val onError: (Throwable) -> Unit,
    private var scheduler: Scheduler = Schedulers.io()
) : Task {

    private var disposable: Disposable? = null

    override fun getId(): String {
        return id
    }

    override fun execute(onFinish: () -> Unit) {
        if (disposable == null) {
            disposable = flowable
                .subscribeOn(scheduler)
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally(onFinish)
                .subscribe(onNext, onError, onComplete)
        }
    }

    override fun cancel() {
        disposable?.dispose()
    }

    override fun getStatus(): Task.Status {
        if (disposable != null && disposable?.isDisposed == false) {
            return Task.Status.InProgress
        }
        return Task.Status.Completed
    }

    override fun getStrategy(): Task.Strategy {
        return taskStrategy
    }
}
```

## Contributing

This repo is open for contributing

## Authors

* **Temirlan Kuntubayev** - *Initial work* - [github](https://github.com/tkuntubayev)

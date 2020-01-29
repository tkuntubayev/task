# Asynchronous tasks management
Sometimes there is need to cancel some asynchronous execution while launching a new one with the same id, sometimes vice versa. This library provides an easy API to control every asynchronous task (no matter Coroutines, Rx, or something else). More details of the usage can be found in Medium post (I'll post it in a few days).

## Getting Started with TaskHandler

Follow this instructions to implement this library in your project

1. add to **module** `build.gradle`
```
implementation "dev.temirlan.common:task:1.0.0"
```
2. implement your own `Task` or find some that matching your preferences (Coroutines or Rx) from samples below
3. put `TaskHandler` in abstract Presenter or ViewModel to have an easy access in every Presenter
```
abstract class AbstractPresenter<T : AbstractContract.View> : MvpPresenter<T>(), AbstractContract.Presenter {
    //
    protected val taskHandler = TaskHandler()
    //    
}
```
4. don't forget to cancel all tasks on presenter or viewmodel destroy
```
abstract class AbstractPresenter<T : AbstractContract.View> : MvpPresenter<T>(), AbstractContract.Presenter {
    //
    override fun onDestroy() {
        taskHandler.cancelAll()
        super.onDestroy()
    }
    //
}
```
5. define and launch a task with TaskHandler's `handle` method in your presenter(viewmodel) as shown in example. One task cannot be launched twice.
```
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
Task is an interface that contains the following methods
```
interface Task {
    fun getId(): String               // id that will be used by taskhandler to identify the same tasks

    fun execute(onFinish: () -> Unit) // method that executes the task

    fun cancel()                      // method that cancels the task

    fun getStatus(): Status           // get the status of the task

    fun getStrategy(): Strategy       // identifying a Strategy to inform TaskHandler about what to do with the previous task with the same id (KillFirst or KeepFirst). See description below
}
```

#### Available task statuses
Stasus - class that informs about the current task state
```
sealed class Status {
        object InProgress : Status()
        object Completed : Status()
        object Cancelled : Status()
}
```

##### Available task strategies
KeepFirst - TaskHandler keeps the previous task with the same id and doesn't start current
KillFirst - TaskHandler cancels the previous task with the same id and starts the current task
```
sealed class Strategy {
        object KeepFirst : Strategy()
        object KillFirst : Strategy()
}
```

## Task implementation samples (not included to the library). Copy or implement your own Task
### Coroutines
```
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
```
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
```
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

```
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

```
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

## Some advices

Put your domain logic to the task

## Contributing

This repo is open for contributing

## Authors

* **Temirlan Kuntubayev** - *Initial work* - [github](https://github.com/tkuntubayev)

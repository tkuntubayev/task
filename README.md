# Asynchronous tasks managing
Sometimes there is need to cancel some asynchronous execution while launching a new one with the same id, sometimes vice versa. It's not comfortable to control it in each place that contains asynchronous code. This library provides an easy API to control every asynchronous task (Coroutines, Rx, or something else). More details of the usage can be found in Medium post (I'll post it in a few days).

## Getting Started with TaskHandler

Follow this instructions to implement this library in your project

1. add to **module** `build.gradle`
```
implementation "dev.temirlan.common:task:$task_version"
```
2. implement your own `Task` or find some that matching your preferences(Coroutines or Rx) from samples below
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
5. launch task with `handle` method as shown in example
```
override fun onSetCardAsDefaultClicked(cardModel: CardModel) {
        viewState.showLoading()
        val setCardAsDefaultTask = CoroutineTask(
                "setCardAsDefault",                                   // set an id that will correspond to task 
                Task.Strategy.KillFirst,                              // cancel previous task with the same id if contains
                { billingRepository.setCardAsDefault(cardModel.id) }, // provide suspend function as we use Coroutines
                { // onSuccess
                    viewState.hideLoading()
                    refresh()
                },
                { // onError
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

    fun getStrategy(): Strategy       // identifying a Strategy to inform TaskHandler about destroying or keeping previous task with the same id
}
```

Stasus - is a class to inform about the progress of executing
```
sealed class Status {
        object InProgress : Status()
        object Completed : Status()
        object Cancelled : Status()
}
```

##### Available strategies
KeepFirst - keeps the previous task with the same id and don't start current
KillFirst - cancels the previous task with the same id and start the current task
```
sealed class Strategy {
        object KeepFirst : Strategy()
        object KillFirst : Strategy()
}
```

## Task implementation samples (not included to the library)
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

### Installing

A step by step series of examples that tell you how to get a development env running

Say what the step will be

```
Give the example
```

And repeat

```
until finished
```

End with an example of getting some data out of the system or using it for a little demo

## Running the tests

Explain how to run the automated tests for this system

### Break down into end to end tests

Explain what these tests test and why

```
Give an example
```

### And coding style tests

Explain what these tests test and why

```
Give an example
```

## Deployment

Add additional notes about how to deploy this on a live system

## Built With

* [Dropwizard](http://www.dropwizard.io/1.0.2/docs/) - The web framework used
* [Maven](https://maven.apache.org/) - Dependency Management
* [ROME](https://rometools.github.io/rome/) - Used to generate RSS Feeds

## Contributing

Please read [CONTRIBUTING.md](https://gist.github.com/PurpleBooth/b24679402957c63ec426) for details on our code of conduct, and the process for submitting pull requests to us.

## Versioning

We use [SemVer](http://semver.org/) for versioning. For the versions available, see the [tags on this repository](https://github.com/your/project/tags). 

## Authors

* **Billie Thompson** - *Initial work* - [PurpleBooth](https://github.com/PurpleBooth)

See also the list of [contributors](https://github.com/your/project/contributors) who participated in this project.

## License

This project is licensed under the MIT License - see the [LICENSE.md](LICENSE.md) file for details

## Acknowledgments

* Hat tip to anyone whose code was used
* Inspiration
* etc

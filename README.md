Example project to reproduce leaks caused by idle HandlerThread instance retaining their last recycled Message.

Bug filed at: https://issuetracker.google.com/issues/146144484


Pasting the description of the issue in case it is eventually deleted:

____

Android Versions: All (confirmed for API 29)

I wrote about this bug in 2015 (https://developer.squareup.com/blog/a-small-leak-will-sink-a-great-ship/) but wasn't able to provide a repro case then, but I've now managed to repro so here we go!

Summary of the bug:

1) All HandlerThread instances retain a java local reference to their last recycled Message while waiting for the next message with nativePollOnce().
2) Dialogs obtain Message instances for their listeners without ever recycling them.
3) As a result, any idle HandlerThread will eventually be preventing a dialog listener from being garbage collected.

I reproduced the issue in a sample project. The exact code highlighting the issue is here: https://github.com/pyricau/handler-thread-leaks/blob/master/app/src/main/java/com/example/leak/MainActivity.kt

Steps to repro:

```
* Check out https://github.com/pyricau/handler-thread-leaks
* Build and start the app (any device should do, I used a Pixel 2 emulator on API 29)
* Rotate the screen and wait 5 seconds. You should see a notification that says "1 retained object, tap to dump heap". If not, try rotating a few more times (the probabilities are high with this repro case but not 100%)
* Tap the notification. LeakCanary will dump the heap and analyze it, with the following output in Logcat:

    ├─ android.os.HandlerThread
    │    Leaking: UNKNOWN
    │    Thread name: 'Idle HandlerThread'
    │    GC Root: Java local variable
    │    ↓ thread HandlerThread.<Java Local>
    │                           ~~~~~~~~~~~~
    ├─ android.os.Message
    │    Leaking: UNKNOWN
    │    ↓ Message.obj
    │              ~~~
    ├─ com.example.leak.MainActivity$sam$android_content_DialogInterface_OnCancelListener$0
    │    Leaking: UNKNOWN
    │    Anonymous class implementing android.content.DialogInterface$OnCancelListener
    │    ↓ MainActivity$sam$android_content_DialogInterface_OnCancelListener$0.function
    │                                                                          ~~~~~~~~
    ├─ com.example.leak.MainActivity$onCreate$dialogCancelListener$1
    │    Leaking: UNKNOWN
    │    Anonymous subclass of kotlin.jvm.internal.Lambda
    │    ↓ MainActivity$onCreate$dialogCancelListener$1.this$0
    │                                                   ~~~~~~
    ╰→ com.example.leak.MainActivity
    ​     Leaking: YES (Activity#mDestroyed is true and ObjectWatcher was watching this)
```

This shows that the Handler Thread named "Idle HandlerThread" holds a java local reference to a Message which has an its obj field set to a dialog cancel listener. That HandlerThread is idle, the held Message was obtained then recycled then obtained again by the Dialog.

The Dialog never puts the Message it obtained back in the pool, so that message will always have its Message.obj field set to the Dialog OnCancelListener. Normally that would be totally fine, because both the Dialog and the Message would be garbage collected once we're done with the dialog. Unfortunately, the idle HandlerThread is still holding on to that Message, preventing it from being garbage collected. Which in turns prevents the cancel listener from being garbage collected. And that cancel listener is holding on to the activity. When the activity is destroyed, it won't be garbage collected, and its entire view hierarchy will be kept in memory forever, or until the idle HandlerThread gets new work.

How often does this happen in practice? Any HandlerThread that performs work and then becomes idle will push a "leaky" Message into the pool. That Message will be obtained and recycled continuously (no issue there), until a dialog comes in and obtains that message without recycling it, which is bound to happen unless no dialogs are used.
When a HandlerThread becomes idle, each dialog created after that has a chance of obtaining the bad message for each of the listeners it sets. There's at least one very common HandlerThread that becomes idle: GoogleApiHandler (used by Google Play Services).

How can this be fixed? I can think of a few options:

* a) [AOSP] Figuring out why HandlerThread is keeping java local reference to the last recycled Message instance while waiting for nativePollOnce(), and fixing
that.
* b) [AOSP] Change the HandlerThread implementation to never recycle messages. Unlikely.
* c) [AOSP] Change the HandlerThread implementation to never stay idle for too long.
* d) [AOSP] Change dialog to recycle messages when dismissed. Unfortunately that wouldn't fix it since a dialog can be created without being shown (happens a lot)
* e) [Devs] Have developers clear out listeners when they're done with a dialog. Developers can already do that today. It's easy to forget to do it though (and sometimes impossible, e.g. when using libraries).
* f) [Devs] Implement a hack that sends an empty message to all idle handler threads. Here's how one can do that: https://github.com/pyricau/handler-thread-leaks/blob/master/app/src/main/java/com/example/leak/MainActivity.kt#L112
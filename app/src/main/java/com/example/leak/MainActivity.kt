package com.example.leak

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.View
import java.util.concurrent.CountDownLatch


class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // This created dialog is never shown because we don't need to. We could show it as well,
        // that wouldn't change the problem.
        val dialog = AlertDialog.Builder(this).create()

        // This dialog cancel listener has a reference to the activity. Most dialog listeners
        // are expected to reference a context in some way to update the UI. Nothing wrong about
        // this part of the code.
        val dialogCancelListener: (dialog: DialogInterface) -> Unit = {
            this@MainActivity.onBackPressed()
        }

        val waitUntilLeakingRecycledMessage = leakRecycledMessage()

        // Waiting for an HandlerThread to obtain & recycle a Message. That message will then be
        // at first in the Message pool and ready to be used, but also unexpectedly held in memory
        // by the HandlerThread.
        waitUntilLeakingRecycledMessage.await()

        // When the latch is released, the HandlerThread is still processing the Message that
        // released that latch.
        // We wait a bit so that the message is done executing and gets recycled.
        Thread.sleep(100)

        // At this point the Message recycled by the HandlerThread is first in the Message pool.
        // In this demo app the current thread (main)is the only active HandlerThread at this point
        // so we're fairly confident nothing else will consume a Message from the pool.

        // Calling Dialog.setOnCancelListener() will obtain the first Message from the Message pool
        // and set its Message.obj field to the dialog cancel listener. It's the equivalent of:
        // Message.obtain(handler, CANCEL, dialogCancelListener)
        dialog.setOnCancelListener(dialogCancelListener)

        // The Dialog never puts the Message it obtained back in the pool, so that message will
        // always have its Message.obj field set to the dialogCancelListener. Normally that would
        // be totally fine, because both the Dialog and the Message would be garbage collected
        // once we're done with the dialog. Unfortunately, the idle HandlerThread is still holding
        // on to that Message, preventing it from being garbage collected. Which in turns prevents
        // the cancel listener from being garbage collected. And that cancel listener is holding
        // on to the activity. When the activity is destroyed, it won't be garbage collected, and
        // its entire view hierarchy will be kept in memory forever, or until the idle
        // HandlerThread gets new work.

        // How often does this happen in practice? Any HandlerThread that performs work and then
        // becomes idle will push a "leaky" Message into the pool. That Message will be obtained
        // and recycled continuously (no issue there), until a dialog comes in and obtains that
        // message without recycling it, which is bound to happen unless no dialogs are used.
        // When a HandlerThread becomes idle, each dialog created after that has a chance of
        // obtaining the bad message for each of the listeners it sets. There's at least one very
        // common HandlerThread that becomes idle: GoogleApiHandler (used by Google Play Services).

        // How can this be fixed? There are a few options:
        // a) [AOSP] Figuring out why HandlerThread is keeping java local reference
        // to the last recycled Message instance while waiting for nativePollOnce(), and fixing
        // that.
        // b) [AOSP] Change the HandlerThread implementation to never recycle messages. Unlikely.
        // c) [AOSP] Change the HandlerThread implementation to never stay idle for too long.
        // d) [AOSP] Change dialog to recycle messages when dismissed. Unfortunately that wouldn't fix it
        // since a dialog can be created without being shown (happens a lot)
        // e) [Devs] Have developers clear out listeners when they're done with a dialog. Developers can
        // already do that today. It's easy to forget.
        // f) [Devs] Implement a hack that sends an empty message to all idle handler threads.
        // Below if a hacky implementation that flushes all handler threads when the button is
        // clicked, effectively making the existing leaks go away.

        findViewById<View>(R.id.flush_handler_threads).setOnClickListener {
            flushHandlerThreads()
        }
    }


    companion object {
        private fun leakRecycledMessage(): CountDownLatch {
            val thread = HandlerThread("Idle HandlerThread")
            thread.start()
            val idleThreadHandler = Handler(thread.looper)

            val waitUntilLeakingRecycledMessage = CountDownLatch(1)

            idleThreadHandler.postDelayed({
                waitUntilLeakingRecycledMessage.countDown()
                // Right after returning the Message that wrapped this callback will be recycled,
                // at which point the MessageQueue will call nativePollOnce() and the HandlerThread
                // should not have any reference to the recycled Message.
                // Unfortunately the HandlerThread will keep a Java local variable reference to the
                // recycled Message until it receives a new Message to process. In this specific
                // case, there will be no other Message, so the recycled message is held by
                // the HandlerThread until process death.
            }, 100)

            return waitUntilLeakingRecycledMessage
        }


        private fun flushHandlerThreads() {
            for (thread in listAllCurrentThreads()) {
                if (thread is HandlerThread) {
                    val handler = Handler(thread.looper)
                    handler.sendMessage(handler.obtainMessage())
                }
            }
        }

        private fun listAllCurrentThreads(): Array<Thread?> {
            // https://stackoverflow.com/a/1323480
            var rootGroup = Thread.currentThread()
                .threadGroup!!
            var lookForRoot = true
            while (lookForRoot) {
                val parentGroup = rootGroup.parent
                if (parentGroup != null) {
                    rootGroup = parentGroup
                } else {
                    lookForRoot = false
                }
            }
            var threads = arrayOfNulls<Thread>(rootGroup.activeCount())
            while (rootGroup.enumerate(threads, true) == threads.size) {
                threads = arrayOfNulls(threads.size * 2)
            }
            return threads
        }
    }

}

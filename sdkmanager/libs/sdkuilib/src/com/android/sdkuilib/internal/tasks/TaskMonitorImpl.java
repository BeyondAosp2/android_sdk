/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.sdkuilib.internal.tasks;

import com.android.sdklib.internal.repository.ITaskMonitor;

import org.eclipse.swt.widgets.ProgressBar;

/**
 * Internal class that implements the logic of an {@link ITaskMonitor}.
 * It doesn't deal with any UI directly. Instead it delegates the UI to
 * the provided {@link IProgressUiProvider}.
 */
class TaskMonitorImpl implements ITaskMonitor {

    private static final double MAX_COUNT = 10000.0;

    private interface ISubTaskMonitor extends ITaskMonitor {
        public void subIncProgress(double realDelta);
    }

    private double mIncCoef = 0;
    private double mValue = 0;
    private final IProgressUiProvider mUi;

    /**
     * Constructs a new {@link TaskMonitorImpl} that relies on the given
     * {@link IProgressUiProvider} to change the user interface.
     * @param ui The {@link IProgressUiProvider}. Cannot be null.
     */
    public TaskMonitorImpl(IProgressUiProvider ui) {
        mUi = ui;
    }

    /** Returns the {@link IProgressUiProvider} passed to the constructor. */
    public IProgressUiProvider getUiProvider() {
        return mUi;
    }

    /**
     * Sets the description in the current task dialog.
     * This method can be invoked from a non-UI thread.
     */
    public void setDescription(String descriptionFormat, Object... args) {
        final String text = String.format(descriptionFormat, args);
        mUi.setDescription(text);
    }

    /**
     * Sets the result in the current task.
     * This method can be invoked from a non-UI thread.
     */
    public void setResult(String resultFormat, Object... args) {
        String text = String.format(resultFormat, args);
        mUi.setResult(text);
    }

    /**
     * Sets the max value of the progress bar.
     * This method can be invoked from a non-UI thread.
     *
     * Weird things will happen if setProgressMax is called multiple times
     * *after* {@link #incProgress(int)}: we don't try to adjust it on the
     * fly.
     *
     * @see ProgressBar#setMaximum(int)
     */
    public void setProgressMax(int max) {
        assert max > 0;
        // Always set the dialog's progress max to 10k since it only handles
        // integers and we want to have a better inner granularity. Instead
        // we use the max to compute a coefficient for inc deltas.
        mUi.setProgressMax((int) MAX_COUNT);
        mIncCoef = max > 0 ? MAX_COUNT / max : 0;
        assert mIncCoef > 0;
    }

    /**
     * Increments the current value of the progress bar.
     *
     * This method can be invoked from a non-UI thread.
     */
    public void incProgress(int delta) {
        if (delta > 0 && mIncCoef > 0) {
            internalIncProgress(delta * mIncCoef);
        }
    }

    private void internalIncProgress(double realDelta) {
        mValue += realDelta;
        mUi.setProgress((int)mValue);
    }

    /**
     * Returns the current value of the progress bar,
     * between 0 and up to {@link #setProgressMax(int)} - 1.
     *
     * This method can be invoked from a non-UI thread.
     */
    public int getProgress() {
        assert mIncCoef > 0;
        return mIncCoef > 0 ? (int)(mUi.getProgress() / mIncCoef) : 0;
    }

    /**
     * Returns true if the "Cancel" button was selected.
     * It is up to the task thread to pool this and exit.
     */
    public boolean isCancelRequested() {
        return mUi.isCancelRequested();
    }

    /**
     * Display a yes/no question dialog box.
     *
     * This implementation allow this to be called from any thread, it
     * makes sure the dialog is opened synchronously in the ui thread.
     *
     * @param title The title of the dialog box
     * @param message The error message
     * @return true if YES was clicked.
     */
    public boolean displayPrompt(final String title, final String message) {
        return mUi.displayPrompt(title, message);
    }

    /**
     * Creates a sub-monitor that will use up to tickCount on the progress bar.
     * tickCount must be 1 or more.
     */
    public ITaskMonitor createSubMonitor(int tickCount) {
        assert mIncCoef > 0;
        assert tickCount > 0;
        return new SubTaskMonitor(this, null, mValue, tickCount * mIncCoef);
    }

    private static class SubTaskMonitor implements ISubTaskMonitor {

        private final TaskMonitorImpl mRoot;
        private final ISubTaskMonitor mParent;
        private final double mStart;
        private final double mSpan;
        private double mSubValue;
        private double mSubCoef;

        /**
         * Creates a new sub task monitor which will work for the given range [start, start+span]
         * in its parent.
         *
         * @param taskMonitor The ProgressTask root
         * @param parent The immediate parent. Can be the null or another sub task monitor.
         * @param start The start value in the root's coordinates
         * @param span The span value in the root's coordinates
         */
        public SubTaskMonitor(TaskMonitorImpl taskMonitor,
                ISubTaskMonitor parent,
                double start,
                double span) {
            mRoot = taskMonitor;
            mParent = parent;
            mStart = start;
            mSpan = span;
            mSubValue = start;
        }

        public boolean isCancelRequested() {
            return mRoot.isCancelRequested();
        }

        public void setDescription(String descriptionFormat, Object... args) {
            mRoot.setDescription(descriptionFormat, args);
        }

        public void setResult(String resultFormat, Object... args) {
            mRoot.setResult(resultFormat, args);
        }

        public void setProgressMax(int max) {
            assert max > 0;
            mSubCoef = max > 0 ? mSpan / max : 0;
            assert mSubCoef > 0;
        }

        public int getProgress() {
            assert mSubCoef > 0;
            return mSubCoef > 0 ? (int)((mSubValue - mStart) / mSubCoef) : 0;
        }

        public void incProgress(int delta) {
            if (delta > 0 && mSubCoef > 0) {
                subIncProgress(delta * mSubCoef);
            }
        }

        public void subIncProgress(double realDelta) {
            mSubValue += realDelta;
            if (mParent != null) {
                mParent.subIncProgress(realDelta);
            } else {
                mRoot.internalIncProgress(realDelta);
            }
        }

        public boolean displayPrompt(String title, String message) {
            return mRoot.displayPrompt(title, message);
        }

        public ITaskMonitor createSubMonitor(int tickCount) {
            assert mSubCoef > 0;
            assert tickCount > 0;
            return new SubTaskMonitor(mRoot,
                    this,
                    mSubValue,
                    tickCount * mSubCoef);
        }
    }

}

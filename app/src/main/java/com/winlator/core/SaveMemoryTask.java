package com.winlator.core;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class SaveMemoryTask {
    private Timer timer;
    private TimerTask task;
    private volatile String cachedProcessName = null;

    public synchronized void start() {
        if (timer != null) return;
        timer = new Timer("SaveMemoryTask", true);
        task = new TimerTask() {
            @Override
            public void run() {
                runTask();
            }
        };
        timer.schedule(task, 0, 500);
    }

    public synchronized void stop() {
        cachedProcessName = null;
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    private void runTask() {
        List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
        int steamPID = 0;
        boolean saveMemory = false;
        for (ProcessHelper.PStat process : processes) {
            if (process.name.equals("steam.exe")) {
                steamPID = process.pid;
            }
            else if (steamPID > 0 && process.name.equals(cachedProcessName)) {
                saveMemory = true;
            }
            else if (steamPID > 0 && process.pid > steamPID && ProcessHelper.getMemoryUsage(process.pid) > 500000000L) {
                List<String> cmdLine = ProcessHelper.getProcessCmdLine(process.pid);
                for (String cmd : cmdLine) {
                    if (cmd.contains("steamapps")) {
                        cachedProcessName = process.name;
                        saveMemory = true;
                        break;
                    }
                }
            }
            if (saveMemory) break;
        }

        if (steamPID == 0) {
            stop();
            return;
        }

        if (saveMemory) {
            for (ProcessHelper.PStat process : processes) {
                if (process.name.equals("steamwebhelper.exe")) {
                    ProcessHelper.killProcess(process.pid);
                }
            }
        }
        else cachedProcessName = null;
    }
}

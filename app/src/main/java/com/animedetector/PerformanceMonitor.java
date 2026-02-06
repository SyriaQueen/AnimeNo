package com.animedetector;

import java.util.LinkedList;
import java.util.Queue;

public class PerformanceMonitor {
    private static final int WINDOW = 30;
    
    private final Queue<Long> frameTimes = new LinkedList<>();
    private final Queue<Long> infTimes = new LinkedList<>();
    private volatile long lastFrame;
    private final Object lock = new Object();
    
    public PerformanceMonitor() {
        lastFrame = System.currentTimeMillis();
    }
    
    public void frameStart() {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            frameTimes.offer(now - lastFrame);
            lastFrame = now;
            while (frameTimes.size() > WINDOW) frameTimes.poll();
        }
    }
    
    public void frameEnd(long inf) {
        synchronized (lock) {
            infTimes.offer(inf);
            while (infTimes.size() > WINDOW) infTimes.poll();
        }
    }
    
    public float getCurrentFPS() {
        synchronized (lock) {
            if (frameTimes.isEmpty()) return 0f;
            long total = 0;
            for (Long t : frameTimes) total += t;
            float avg = (float) total / frameTimes.size();
            return avg > 0 ? 1000f / avg : 0f;
        }
    }
    
    public float getAvgInference() {
        synchronized (lock) {
            if (infTimes.isEmpty()) return 0f;
            long total = 0;
            for (Long t : infTimes) total += t;
            return (float) total / infTimes.size();
        }
    }
}

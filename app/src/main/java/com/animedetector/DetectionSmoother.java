package com.animedetector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

public class DetectionSmoother {
    private final int windowSize;
    private final Queue<OptimizedAnimeDetector.DetectionResult> history;
    private final float POSITION_THRESHOLD_SQ = 50f * 50f;
    
    private static final int GRID_SIZE = 32;
    private final Map<Integer, List<OptimizedAnimeDetector.Detection>> spatialGrid;
    private final List<OptimizedAnimeDetector.Detection> reusableList;
    
    public DetectionSmoother(int windowSize) {
        this.windowSize = windowSize;
        this.history = new LinkedList<>();
        this.spatialGrid = new HashMap<>();
        this.reusableList = new ArrayList<>();
    }
    
    public synchronized OptimizedAnimeDetector.DetectionResult smooth(
            OptimizedAnimeDetector.DetectionResult newResult) {
        
        history.offer(newResult);
        
        while (history.size() > windowSize) {
            history.poll();
        }
        
        if (history.size() < 2) {
            return newResult;
        }
        
        List<OptimizedAnimeDetector.Detection> smoothed = mergeDetections(newResult);
        
        return new OptimizedAnimeDetector.DetectionResult(
            smoothed, newResult.imageWidth, newResult.imageHeight
        );
    }
    
    private List<OptimizedAnimeDetector.Detection> mergeDetections(
            OptimizedAnimeDetector.DetectionResult latest) {
        
        List<OptimizedAnimeDetector.Detection> merged = new ArrayList<>();
        
        buildSpatialGrid(latest.imageWidth, latest.imageHeight);
        
        int minOccurrences = Math.max(1, windowSize / 2);
        
        for (OptimizedAnimeDetector.Detection current : latest.detections) {
            reusableList.clear();
            findSimilarInGrid(current, latest, reusableList);
            
            if (reusableList.size() >= minOccurrences) {
                merged.add(averageDetections(reusableList));
            } else if (current.confidence > 0.5f) {
                merged.add(current);
            }
        }
        
        return merged;
    }
    
    private void buildSpatialGrid(int imageWidth, int imageHeight) {
        spatialGrid.clear();
        
        float cellWidth = (float) imageWidth / GRID_SIZE;
        float cellHeight = (float) imageHeight / GRID_SIZE;
        
        for (OptimizedAnimeDetector.DetectionResult result : history) {
            for (OptimizedAnimeDetector.Detection det : result.detections) {
                int gridX = Math.max(0, Math.min(GRID_SIZE - 1, 
                    (int) (det.centerX / cellWidth)));
                int gridY = Math.max(0, Math.min(GRID_SIZE - 1, 
                    (int) (det.centerY / cellHeight)));
                
                int key = gridY * GRID_SIZE + gridX;
                spatialGrid.computeIfAbsent(key, k -> new ArrayList<>()).add(det);
            }
        }
    }
    
    private void findSimilarInGrid(
            OptimizedAnimeDetector.Detection target,
            OptimizedAnimeDetector.DetectionResult latest,
            List<OptimizedAnimeDetector.Detection> output) {
        
        output.add(target);
        
        float cellWidth = (float) latest.imageWidth / GRID_SIZE;
        float cellHeight = (float) latest.imageHeight / GRID_SIZE;
        
        int gridX = Math.max(0, Math.min(GRID_SIZE - 1, 
            (int) (target.centerX / cellWidth)));
        int gridY = Math.max(0, Math.min(GRID_SIZE - 1, 
            (int) (target.centerY / cellHeight)));
        
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = gridX + dx;
                int ny = gridY + dy;
                
                if (nx < 0 || nx >= GRID_SIZE || ny < 0 || ny >= GRID_SIZE) continue;
                
                int key = ny * GRID_SIZE + nx;
                List<OptimizedAnimeDetector.Detection> cell = spatialGrid.get(key);
                
                if (cell == null) continue;
                
                for (OptimizedAnimeDetector.Detection candidate : cell) {
                    float dx2 = candidate.centerX - target.centerX;
                    float dy2 = candidate.centerY - target.centerY;
                    float distSq = dx2 * dx2 + dy2 * dy2;
                    
                    if (distSq < POSITION_THRESHOLD_SQ) {
                        output.add(candidate);
                        if (output.size() >= windowSize) return;
                    }
                }
            }
        }
    }
    
    private OptimizedAnimeDetector.Detection averageDetections(
            List<OptimizedAnimeDetector.Detection> detections) {
        
        float x1 = 0, y1 = 0, x2 = 0, y2 = 0, conf = 0;
        float invCount = 1.0f / detections.size();
        
        for (OptimizedAnimeDetector.Detection det : detections) {
            x1 += det.x1;
            y1 += det.y1;
            x2 += det.x2;
            y2 += det.y2;
            conf += det.confidence;
        }
        
        return new OptimizedAnimeDetector.Detection(
            x1 * invCount, y1 * invCount,
            x2 * invCount, y2 * invCount,
            conf * invCount, 0
        );
    }
    
    public synchronized void clear() {
        history.clear();
        spatialGrid.clear();
    }
}



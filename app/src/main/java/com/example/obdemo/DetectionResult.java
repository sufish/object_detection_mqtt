package com.example.obdemo;

import android.graphics.RectF;

/**
 * Created by fuqiang on 2017/10/23.
 */

public class DetectionResult {
    private String label;
    private Float score;
    private RectF box;

    public DetectionResult(String label, Float score, RectF location) {
        this.label = label;
        this.score = score;
        this.box = location;
    }

    public String getLabel() {
        return label;
    }

    public Float getScore() {
        return score;
    }

    public RectF getBox() {
        return box;
    }
}

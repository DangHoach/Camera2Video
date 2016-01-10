
package com.example.android.camera2video.internal.compat;

import android.animation.ValueAnimator;
import android.annotation.TargetApi;
import android.os.Build;


@TargetApi(Build.VERSION_CODES.HONEYCOMB)
public class AnimatorCompatV11 extends AnimatorCompat {

    ValueAnimator animator;

    public AnimatorCompatV11(float start, float end, final AnimationFrameUpdateListener listener) {
        super();
        animator = ValueAnimator.ofFloat(start, end);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                listener.onAnimationFrame((Float) animation.getAnimatedValue());
            }
        });
    }

    @Override
    public void cancel() {
        animator.cancel();
    }

    @Override
    public boolean isRunning() {
        return animator.isRunning();
    }

    @Override
    public void setDuration(int duration) {
        animator.setDuration(duration);
    }

    @Override
    public void start() {
        animator.start();
    }
}

package com.example.android.camera2video;

import android.content.Context;
import android.content.SharedPreferences;


public class PreferenceHelper {
    /**
     * Initialize the memory camera
     *
     * @param context
     */
    public static void writeCurrentCameraid(Context context, String cameraId) {
        SharedPreferences currentPreferences = context.getSharedPreferences("current", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = currentPreferences.edit();
        editor.putString("cameraid", cameraId);
        editor.commit();
    }

    /**
     * Get Which Camera
     *
     * @param context
     * @return
     */
    public static String getCurrentCameraid(Context context) {
        SharedPreferences currentPreferences = context.getSharedPreferences("current", Context.MODE_PRIVATE);
        return currentPreferences.getString("cameraid", "0");
    }

    /**
     * To determine whether the camera parameters already been initialized
     *
     * @param context
     * @return
     */
    public static boolean checkFirstInit(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("current", Context.MODE_PRIVATE);
        int num = preferences.getInt("first_time", 0);
        if (num == 1) {
            return true;
        } else {
            return false;
        }
    }



}

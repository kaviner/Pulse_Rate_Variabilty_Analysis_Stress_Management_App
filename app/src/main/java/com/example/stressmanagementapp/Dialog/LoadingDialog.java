package com.example.stressmanagementapp.Dialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.LayoutInflater;

import com.example.stressmanagementapp.R;

public class LoadingDialog {

    private Activity activity;
    private AlertDialog dialog;

    public LoadingDialog(Activity myActivity) {
        activity = myActivity;
    }

    public void startLoadingDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder((activity));

        LayoutInflater inflater = activity.getLayoutInflater();
        builder.setView(inflater.inflate(R.layout.custom_dialog, null));
        builder.setCancelable(true);
        dialog = builder.create();
        dialog.show();
    }
    public AlertDialog getLoadingDialog(){
        return dialog;
    }
    public void dismissDialog(){
        dialog.dismiss();
    }
    public boolean isDisplaying(){
        return dialog.isShowing();
    }

}
